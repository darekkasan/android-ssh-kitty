package com.kisshkitty.core.ssh

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
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

            // Open interactive shell session with PTY
            val session = client.startSession()
            session.allocateDefaultPTY()
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

    fun writeToTerminal(data: ByteArray) {
        outputStream?.write(data)
        outputStream?.flush()
    }

    fun readFromTerminal(): ByteArray? {
        return try {
            val available = inputStream?.available() ?: 0
            if (available > 0) {
                val buffer = ByteArray(available)
                inputStream?.read(buffer)
                buffer
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun resizeTerminal(cols: Int, rows: Int) {
        // PTY resize not supported in current implementation
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
