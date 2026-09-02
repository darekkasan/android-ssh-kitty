package com.kisshkitty.core.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshConnectionManager @Inject constructor() {

    private var currentClient: SSHClient? = null
    private var currentSession: Session? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    suspend fun connect(config: SshConfig): Result<SshConnection> = try {
        val client = SSHClient(config.hostKeyVerifier)

        // Configure timeouts
        client.connectTimeout = config.timeout

        // Connect
        when {
            config.keyPath != null -> {
                val keyFile = File(config.keyPath)
                val keyProvider = client.loadKeyProvider(keyFile)
                client.connect(config.host, config.port)
                client.authPublickey(config.username, keyProvider)
            }
            config.password != null -> {
                client.connect(config.host, config.port)
                client.authPassword(config.username, config.password)
            }
            else -> throw IllegalStateException("Either password or keyPath must be provided")
        }

        // Open shell session
        val session = client.startSession()
        session.allocateDefaultPty()
        val shell = session.openShell()

        currentClient = client
        currentSession = session
        inputStream = shell.inputStream
        outputStream = shell.outputStream

        Result.success(SshConnection(client, session, shell))
    } catch (e: Exception) {
        Result.failure(e)
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
        currentSession?.let { session ->
            session.allocatePty("xterm-256color", cols, rows, 0, 0)
        }
    }

    fun disconnect() {
        try {
            inputStream?.close()
            outputStream?.close()
            currentSession?.close()
            currentClient?.disconnect()
        } catch (e: Exception) {
            // Ignore cleanup errors
        } finally {
            currentClient = null
            currentSession = null
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
