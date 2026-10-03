package com.kisshkitty.core.ssh

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.InputStream
import java.io.OutputStream
import java.security.Security
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshConnectionManager @Inject constructor() {

    private var currentClient: SSHClient? = null
    private var currentSession: Session? = null
    private var currentShell: Session.Shell? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    init {
        // Fix for Android: replace Android's BC provider with Java's BC provider
        // Android's BC doesn't support X25519 which sshj needs
        fixBouncyCastle()
    }

    private fun fixBouncyCastle() {
        try {
            // Remove Android's BC provider
            val androidBC = Security.getProvider("BC")
            if (androidBC != null) {
                Security.removeProvider("BC")
            }

            // Try to add Java's BC provider if available
            // sshj will use its own bundled BC if available
            SecurityUtils.setRegisterBouncyCastle(true)

            Log.d("SshConnectionManager", "BouncyCastle provider fixed for Android")
        } catch (e: Exception) {
            Log.e("SshConnectionManager", "Failed to fix BouncyCastle: ${e.message}")
        }
    }

    suspend fun connect(config: SshConfig): Result<SshConnection> = withContext(Dispatchers.IO) {
        try {
            // Prefer AEAD ciphers: encryption and authentication in one pass
            // instead of AES-CTR plus a separate HMAC. With sshj's pure-Java
            // crypto that is roughly half the CPU per received byte, which
            // matters for megabytes/s of base64 image data.
            val sshjConfig = DefaultConfig()
            val preferred = listOf(
                "chacha20-poly1305@openssh.com",
                "aes128-gcm@openssh.com",
                "aes256-gcm@openssh.com"
            )
            sshjConfig.cipherFactories = sshjConfig.cipherFactories.sortedBy { factory ->
                preferred.indexOf(factory.name).let { if (it < 0) preferred.size else it }
            }
            val client = SSHClient(sshjConfig)
            client.addHostKeyVerifier(config.hostKeyVerifier)

            // Configure timeouts
            client.connectTimeout = config.timeout

            // Connect
            when {
                config.keyPath != null -> {
                    client.connect(config.host, config.port)
                    client.authPublickey(config.username, config.keyPath)
                }
                config.password != null -> {
                    client.connect(config.host, config.port)
                    client.authPassword(config.username, config.password)
                }
                else -> throw IllegalStateException("Either password or keyPath must be provided")
            }

            // Small keystroke packets must not wait on Nagle/delayed-ACK.
            try {
                client.socket.tcpNoDelay = true
            } catch (e: Exception) {
                Log.w("SshConnectionManager", "TCP_NODELAY failed", e)
            }

            // Open interactive shell with a real terminal type so remote
            // apps (vim, chafa, colors) detect capabilities properly.
            // True window size follows via resizeTerminal().
            // Keep the window at sshj's 2MB default: a bigger one lets a
            // flood (mpv video) queue up megabytes that must all be parsed
            // before the prompt/echo returns after the stream stops.
            client.connection.maxPacketSize = 64 * 1024
            val session = client.startSession()
            session.allocatePTY("xterm-256color", 80, 24, 0, 0, mapOf(PTYMode.ECHO to 1))
            val shell = session.startShell()

            currentClient = client
            currentSession = session
            currentShell = shell
            inputStream = shell.inputStream
            outputStream = shell.outputStream

            Result.success(SshConnection(client, session, shell))
        } catch (e: Exception) {
            Log.e("SshConnectionManager", "SSH connection failed", e)
            Result.failure(e)
        }
    }

    // All channel writes go through one mutex. sshj channels are not
    // safe for concurrent writers: interleaved keystroke coroutines
    // corrupt the packet stream and sshd kills the connection
    // ("Bad packet length" / "Connection corrupted" server-side).
    // Window changes share the same transport, so they take it too.
    private val writeMutex = Mutex()

    suspend fun writeToTerminal(data: ByteArray) {
        val queued = System.nanoTime()
        writeMutex.withLock {
            val waitMs = (System.nanoTime() - queued) / 1_000_000
            withContext(Dispatchers.IO) {
                try {
                    val w0 = System.nanoTime()
                    outputStream?.write(data)
                    outputStream?.flush()
                    val writeMs = (System.nanoTime() - w0) / 1_000_000
                    // Bench hook: slow input path (lock wait or blocked write).
                    if (waitMs > 50 || writeMs > 50) {
                        Log.d("KisshBench", "input slow: lockWait=${waitMs}ms write=${writeMs}ms")
                    }
                } catch (e: Exception) {
                    Log.e("SshConnectionManager", "Write failed", e)
                }
            }
        }
    }

    /** Bytes already received but not yet read (diagnostics: backlog). */
    fun pendingBytes(): Int = try { inputStream?.available() ?: 0 } catch (e: Exception) { 0 }

    /** Single reusable read buffer: only the reader loop touches it. */
    val readBuffer = ByteArray(MAX_BATCH_BYTES)

    /**
     * Blocks until output arrives, then drains whatever else is already
     * buffered (up to [MAX_BATCH_BYTES]) into [readBuffer] as one batch.
     * Batching lets the parser skip superseded video frames instead of
     * building every one under backlog. Returns the byte count, or -1 on
     * EOF/error. Call from an IO thread; disconnect() closes the stream,
     * which unblocks a pending read.
     */
    fun readFromTerminal(): Int {
        return try {
            val input = inputStream ?: return -1
            var total = input.read(readBuffer, 0, MAX_READ_BYTES)
            if (total <= 0) return -1
            while (total < MAX_BATCH_BYTES && input.available() > 0) {
                val n = input.read(
                    readBuffer, total,
                    minOf(MAX_READ_BYTES, MAX_BATCH_BYTES - total)
                )
                if (n <= 0) break
                total += n
            }
            total
        } catch (e: Exception) {
            Log.e("SshConnectionManager", "Read failed", e)
            -1
        }
    }

    suspend fun resizeTerminal(cols: Int, rows: Int, widthPx: Int = 0, heightPx: Int = 0) {
        // Best effort: tell the server the real window size (SIGWINCH,
        // TIOCGWINSZ) so full-screen apps and image tools lay out
        // correctly. Serialized with channel writes: concurrent transport
        // writes corrupt the stream and get the connection killed.
        // The channel may be closing; never crash on it.
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    currentShell?.changeWindowDimensions(cols, rows, widthPx, heightPx)
                } catch (e: Exception) {
                    Log.e("SshConnectionManager", "Window change failed", e)
                }
            }
        }
    }

    fun disconnect() {
        try {
            inputStream?.close()
            outputStream?.close()
            currentShell?.close()
            currentSession?.close()
            currentClient?.disconnect()
        } catch (e: Exception) {
            // Ignore cleanup errors
        } finally {
            currentClient = null
            currentSession = null
            currentShell = null
            inputStream = null
            outputStream = null
        }
    }

    fun isConnected(): Boolean {
        return currentClient?.isConnected == true
    }
}

data class SshConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    val keyPath: String? = null,
    val timeout: Int = 30000,
    val hostKeyVerifier: PromiscuousVerifier = PromiscuousVerifier()
)

data class SshConnection(
    val client: SSHClient,
    val session: Session,
    val shell: Session.Shell
)

private const val MAX_READ_BYTES = 262144
private const val MAX_BATCH_BYTES = 4 * 1024 * 1024
