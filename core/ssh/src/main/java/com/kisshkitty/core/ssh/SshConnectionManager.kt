package com.kisshkitty.core.ssh

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
            val client = SSHClient()
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

            // Open interactive shell with a real terminal type so remote
            // apps (vim, chafa, colors) detect capabilities properly.
            // True window size follows via resizeTerminal().
            // Image streams are bulk base64: a bigger channel window keeps
            // the pipe full on high-latency links (sshj default: 2MB/32KB).
            client.connection.windowSize = 16L * 1024 * 1024
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
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    outputStream?.write(data)
                    outputStream?.flush()
                } catch (e: Exception) {
                    Log.e("SshConnectionManager", "Write failed", e)
                }
            }
        }
    }

    /** Single reusable read buffer: only the reader loop touches it. */
    val readBuffer = ByteArray(MAX_READ_BYTES)

    /**
     * Blocks until output arrives, then reads once into [readBuffer].
     * Returns the byte count, or -1 on EOF/error. Call from an IO thread;
     * disconnect() closes the stream, which unblocks a pending read.
     * No polling delay: a frame is parsed the moment it arrives.
     */
    fun readFromTerminal(): Int {
        return try {
            val input = inputStream ?: return -1
            input.read(readBuffer, 0, MAX_READ_BYTES)
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
