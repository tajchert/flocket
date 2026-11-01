package com.mtajchert.flocket.core.ssh

import com.mtajchert.flocket.core.protocol.transport.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import kotlin.coroutines.cancellation.CancellationException

/** An authenticated SSH connection. Channels opened from it are independent. */
class SshSession internal constructor(
    private val client: SSHClient,
) : CommandRunner {
    val isConnected: Boolean get() = client.isConnected && client.isAuthenticated

    /**
     * Starts [command] on an exec channel (no PTY). stdout is the transport; stderr is captured.
     * Every argument is POSIX single-quoted. Failures are [TransportException]. Opening the channel
     * blocks up to the connection timeout and is not interrupted by cancellation.
     */
    suspend fun exec(command: List<String>): SshExecTransport =
        withContext(Dispatchers.IO) {
            val session =
                try {
                    client.startSession()
                } catch (e: Exception) {
                    throw TransportException("could not open channel: ${e.message}", e)
                }
            try {
                SshExecTransport(session, session.exec(ShellQuote.join(command)))
            } catch (e: Exception) {
                runCatching { session.close() }
                if (e is CancellationException) throw e
                throw TransportException("could not start command: ${e.message}", e)
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

    /** Disconnects. Safe to call from a cancelled coroutine (e.g. a `finally` block) and twice. */
    suspend fun close() {
        withContext(NonCancellable + Dispatchers.IO) { client.closeQuietly() }
    }
}
