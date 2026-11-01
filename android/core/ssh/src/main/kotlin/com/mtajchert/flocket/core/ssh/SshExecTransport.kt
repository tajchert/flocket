package com.mtajchert.flocket.core.ssh

import com.mtajchert.flocket.core.protocol.transport.ByteTransport
import com.mtajchert.flocket.core.protocol.transport.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * [ByteTransport] over one SSH exec channel. stdout only; stderr goes to [diagnostics].
 *
 * Blocking SSHJ streams are never touched by the caller's coroutine:
 * - stdout is pumped by a background job into a bounded queue (SSH flow control still applies), so
 *   [read] suspends on the queue and a cancelled [read] returns promptly without losing bytes; the
 *   transport stays usable.
 * - [write] runs the blocking write on a background job. Cancelling it returns promptly, but a frame
 *   may have been half-written, so the transport is closed (pending [read]s return -1).
 * - At EOF [read] returns -1 if the command exited with status 0 (or no status was reported) and
 *   throws [TransportException] for a non-zero [TransportException.exitStatus] or a signal
 *   (exitStatus null, signal in the message); a broken channel also throws. After [close], [read]
 *   returns -1 and [write] throws.
 */
class SshExecTransport internal constructor(
    private val session: Session,
    private val command: Session.Command,
) : ByteTransport {
    private val stderr = ByteRing(STDERR_CAPACITY)
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Single reader at a time (ByteTransport contract). Volatile because a cancelled receive hands
    // its chunk back via onUndeliveredElement, possibly on another thread.
    @Volatile private var pending: ByteArray? = null

    @Volatile private var pendingOffset = 0

    /**
     * stdout chunks; closed at EOF (no cause) or on an I/O failure (cause = [TransportException]).
     * A chunk lost to a cancelled [read] is put back so no byte is dropped.
     */
    private val chunks =
        Channel<ByteArray>(QUEUE_CHUNKS) { undelivered ->
            pendingOffset = 0
            pending = undelivered
        }

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
            } catch (_: Exception) {
                // Channel closed or broken; stdout reports the failure.
            }
        }

    init {
        scope.launch {
            var failure: TransportException? = null
            try {
                val buf = ByteArray(CHUNK)
                val input = command.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) chunks.send(buf.copyOf(n))
                }
                // exit-status / stderr usually arrive right around stdout EOF; give them a moment.
                withTimeoutOrNull(EXIT_WAIT_MS) { drain.join() }
                runCatching { command.join(1, TimeUnit.SECONDS) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!closed.get()) failure = TransportException("read failed: ${e.message}", e, exitStatus())
            } finally {
                chunks.close(failure)
            }
        }
    }

    override suspend fun read(buffer: ByteArray): Int {
        val n = readRaw(buffer)
        if (n < 0 && !closed.get()) exitFailure()?.let { throw it }
        return n
    }

    /** Like [read] but never throws for a non-zero exit status. */
    internal suspend fun readRaw(buffer: ByteArray): Int {
        if (buffer.isEmpty()) return 0
        if (closed.get()) return -1
        val chunk =
            pending ?: run {
                val r = chunks.receiveCatching()
                if (closed.get()) return -1
                if (r.isClosed) {
                    r.exceptionOrNull()?.let { throw it }
                    return -1
                }
                pendingOffset = 0
                r.getOrThrow()
            }
        val n = minOf(buffer.size, chunk.size - pendingOffset)
        System.arraycopy(chunk, pendingOffset, buffer, 0, n)
        pendingOffset += n
        pending = if (pendingOffset < chunk.size) chunk else null
        return n
    }

    override suspend fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (closed.get()) throw TransportException("transport closed")
        val job =
            scope.async {
                command.outputStream.write(bytes, offset, length)
                command.outputStream.flush()
            }
        try {
            job.await()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                // The caller was cancelled mid-write; the stream may hold a partial frame.
                close()
                throw e
            }
            throw TransportException("transport closed", e)
        } catch (e: Exception) {
            if (closed.get()) throw TransportException("transport closed", e)
            throw TransportException("write failed: ${e.message}", e, exitStatus())
        }
    }

    override fun diagnostics(): String = stderr.text()

    /** Exit status once the remote command finished, else null. */
    fun exitStatus(): Int? = command.exitStatus

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        chunks.close()
        scope.cancel()
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { command.close() }
            runCatching { session.close() }
        }
    }

    private fun exitFailure(): TransportException? {
        val status = command.exitStatus
        if (status != null && status != 0) {
            return TransportException("remote command exited with status $status", exitStatus = status)
        }
        val signal = command.exitSignal
        if (status == null && signal != null) {
            return TransportException("remote command killed by signal $signal")
        }
        return null
    }

    private companion object {
        const val STDERR_CAPACITY = 8 * 1024
        const val CHUNK = 32 * 1024
        const val QUEUE_CHUNKS = 8
        const val EXIT_WAIT_MS = 2_000L
    }
}
