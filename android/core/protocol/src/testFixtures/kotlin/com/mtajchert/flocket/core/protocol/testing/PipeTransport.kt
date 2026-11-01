package com.mtajchert.flocket.core.protocol.testing

import com.mtajchert.flocket.core.protocol.transport.ByteTransport
import com.mtajchert.flocket.core.protocol.transport.TransportException
import kotlinx.coroutines.channels.Channel

/**
 * In-memory [ByteTransport]. [pair] returns two connected ends: bytes written to one are read
 * from the other, one write = one read chunk (so tests control read boundaries). Closing either
 * end gives the other EOF once its queue is drained and makes further writes fail.
 */
class PipeTransport private constructor() : ByteTransport {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private lateinit var peer: PipeTransport
    private var current: ByteArray? = null
    private var pos = 0

    @Volatile
    private var closed = false

    /** What [diagnostics] returns (simulated remote stderr). */
    @Volatile
    var stderr: String = ""

    /** Total bytes written by this end. */
    @Volatile
    var bytesWritten: Long = 0
        private set

    override suspend fun read(buffer: ByteArray): Int {
        var cur = current
        if (cur == null || pos >= cur.size) {
            cur =
                inbox.receiveCatching().let { r -> r.getOrNull() ?: r.exceptionOrNull()?.let { throw it } ?: return -1 }
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
        if (closed || peer.closed) throw TransportException("pipe closed")
        bytesWritten += length
        val sent = peer.inbox.trySend(bytes.copyOfRange(offset, offset + length))
        if (sent.isFailure) throw TransportException("pipe closed")
    }

    override fun diagnostics(): String = stderr

    override suspend fun close() {
        closed = true
        inbox.close()
        peer.inbox.close()
    }

    /** Simulate a broken link: the other end's reads fail with [error]. */
    fun breakLink(error: Throwable = TransportException("link broken")) {
        closed = true
        peer.inbox.close(error)
        inbox.close()
    }

    companion object {
        fun pair(): Pair<PipeTransport, PipeTransport> {
            val a = PipeTransport()
            val b = PipeTransport()
            a.peer = b
            b.peer = a
            return a to b
        }
    }
}
