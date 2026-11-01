package com.mtajchert.flocket.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.SSHClient
import java.util.concurrent.TimeUnit

/** An authenticated SSH connection. Channels opened from it are independent. */
class SshSession internal constructor(
    private val client: SSHClient,
) : CommandRunner {
    val isConnected: Boolean get() = client.isConnected && client.isAuthenticated

    /** Starts [command] on an exec channel (no PTY). stdout is the transport; stderr is captured. */
    suspend fun exec(command: List<String>): SshExecTransport =
        withContext(Dispatchers.IO) {
            val session = client.startSession()
            try {
                SshExecTransport(session, session.exec(ShellQuote.join(command)))
            } catch (e: Exception) {
                runCatching { session.close() }
                throw e
            }
        }

    override suspend fun run(command: List<String>): CommandResult {
        val t = exec(command)
        try {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (true) {
                val n = t.readRaw(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return CommandResult(out.toString(Charsets.UTF_8), t.diagnostics(), t.exitStatus())
        } finally {
            t.close()
        }
    }

    suspend fun close() {
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(TimeUnit.SECONDS.toMillis(3)) { client.closeQuietly() }
        }
    }
}
