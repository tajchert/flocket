package com.mtajchert.flocket.core.ssh

import com.mtajchert.flocket.core.protocol.transport.ByteTransport
import com.mtajchert.flocket.core.protocol.transport.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** [ByteTransport] over one SSH exec channel. stdout only; stderr goes to [diagnostics]. */
class SshExecTransport internal constructor(
    private val session: Session,
    private val command: Session.Command,
) : ByteTransport {
    private val stderr = ByteRing(STDERR_CAPACITY)
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drain: Job =
        scope.launch {
            val buf = ByteArray(1024)
            try {
                val input = command.errorStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    stderr.append(buf, n)
                }
            } catch (_: IOException) {
            }
        }

    override suspend fun read(buffer: ByteArray): Int {
        val n = readRaw(buffer)
        if (n < 0) {
            val status = exitStatus()
            if (status != null && status != 0) {
                throw TransportException("remote command exited with status $status", exitStatus = status)
            }
        }
        return n
    }

    /** Like [read] but never throws for a non-zero exit status. */
    internal suspend fun readRaw(buffer: ByteArray): Int {
        if (closed.get()) return -1
        val n =
            try {
                withContext(Dispatchers.IO) { command.inputStream.read(buffer) }
            } catch (e: IOException) {
                if (closed.get()) return -1
                throw TransportException("read failed: ${e.message}", e)
            }
        if (n < 0) {
            withTimeoutOrNull(2_000) { withContext(Dispatchers.IO) { drain.join() } }
            runCatching { command.join(1, TimeUnit.SECONDS) }
        }
        return n
    }

    override suspend fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (closed.get()) throw TransportException("transport closed")
        try {
            withContext(Dispatchers.IO) {
                command.outputStream.write(bytes, offset, length)
                command.outputStream.flush()
            }
        } catch (e: IOException) {
            throw TransportException("write failed: ${e.message}", e, exitStatus())
        }
    }

    override fun diagnostics(): String = stderr.text()

    /** Exit status once the remote command finished, else null. */
    fun exitStatus(): Int? = command.exitStatus

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { command.close() }
            runCatching { session.close() }
            scope.cancel()
        }
    }

    private companion object {
        const val STDERR_CAPACITY = 8 * 1024
    }
}
