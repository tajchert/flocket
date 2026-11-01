package com.mtajchert.flocket.core.protocol.testing

import com.mtajchert.flocket.core.protocol.transport.ByteTransport
import com.mtajchert.flocket.core.protocol.transport.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * [ByteTransport] over a local child process' stdin/stdout, e.g.
 * `ProcessTransport("hc", "connect", "--session", "flocket-test-ab12")`. stderr is captured,
 * bounded to the last [STDERR_TAIL] bytes. Reads are served from a pump thread so they are
 * cancellable.
 */
class ProcessTransport(
    vararg command: String,
    environment: Map<String, String> = emptyMap(),
) : ByteTransport {
    private val process: Process =
        ProcessBuilder(*command).also { it.environment().putAll(environment) }.start()
    private val stdin = process.outputStream
    private val chunks = Channel<ByteArray>(64)
    private val stderrTail = StringBuilder()
    private var current: ByteArray? = null
    private var pos = 0

    init {
        daemon("pt-stdout") {
            val buf = ByteArray(64 * 1024)
            val input = process.inputStream
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (chunks.trySendBlocking(buf.copyOf(n)).isFailure) break
                }
            } catch (_: java.io.IOException) {
            }
            chunks.close()
        }
        daemon("pt-stderr") {
            val buf = ByteArray(4096)
            val err = process.errorStream
            try {
                while (true) {
                    val n = err.read(buf)
                    if (n < 0) break
                    synchronized(stderrTail) {
                        stderrTail.append(String(buf, 0, n))
                        if (stderrTail.length > STDERR_TAIL) stderrTail.delete(0, stderrTail.length - STDERR_TAIL)
                    }
                }
            } catch (_: java.io.IOException) {
            }
        }
    }

    override suspend fun read(buffer: ByteArray): Int {
        var cur = current
        if (cur == null || pos >= cur.size) {
            cur = chunks.receiveCatching().getOrNull() ?: return -1
            current = cur
            pos = 0
        }
        val n = minOf(buffer.size, cur.size - pos)
        System.arraycopy(cur, pos, buffer, 0, n)
        pos += n
        return n
    }

    override suspend fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        withContext(Dispatchers.IO) {
            try {
                stdin.write(bytes, offset, length)
                stdin.flush()
            } catch (e: java.io.IOException) {
                throw TransportException("write to process failed", e)
            }
        }
    }

    override fun diagnostics(): String = synchronized(stderrTail) { stderrTail.toString() }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            runCatching { stdin.close() }
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        chunks.close()
    }

    private fun daemon(
        name: String,
        block: () -> Unit,
    ) = Thread(block, name).apply { isDaemon = true }.start()

    companion object {
        const val STDERR_TAIL = 8 * 1024
    }
}
