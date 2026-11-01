package com.mtajchert.flocket.core.protocol.wire

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Incremental frame decoder. Feed it whatever the transport returned (fragments, several frames,
 * a header split across reads) and pull complete frames with [next].
 *
 * Bounds: a header announcing more than [maxPayload] bytes, or more than [maxBuffered] undecoded
 * bytes, throws [ProtocolException] (the stream is corrupt; drop the connection). Memory for a
 * payload is only committed as bytes actually arrive.
 *
 * Not thread safe: use it from the reader coroutine only.
 */
class FrameDecoder(
    private val maxPayload: Int = WireLimits.MAX_PAYLOAD,
    private val maxBuffered: Int = WireLimits.MAX_BUFFERED,
) {
    private var buf = ByteArray(INITIAL)
    private var start = 0
    private var end = 0

    /** Undecoded bytes currently held. */
    val buffered: Int get() = end - start

    fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ) {
        if (length == 0) return
        if (buffered.toLong() + length > maxBuffered) {
            throw ProtocolException(
                "undecoded buffer exceeds $maxBuffered bytes",
                ProtocolException.Reason.BUFFER_OVERFLOW,
            )
        }
        ensureRoom(length)
        System.arraycopy(bytes, offset, buf, end, length)
        end += length
        // Fail fast on a bad header even if the payload never arrives.
        if (buffered >= WireLimits.HEADER_SIZE) payloadLength()
    }

    /** Next complete frame, or null when more bytes are needed. */
    fun next(): Frame? {
        if (buffered < WireLimits.HEADER_SIZE) return null
        val len = payloadLength()
        if (buffered < WireLimits.HEADER_SIZE + len) return null
        val bb = ByteBuffer.wrap(buf, start, WireLimits.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val channel = bb.int
        val kind = bb.get().toInt() and 0xFF
        val payload = buf.copyOfRange(start + WireLimits.HEADER_SIZE, start + WireLimits.HEADER_SIZE + len)
        start += WireLimits.HEADER_SIZE + len
        if (start == end) {
            start = 0
            end = 0
            if (buf.size > SHRINK_ABOVE) buf = ByteArray(INITIAL)
        }
        return Frame(channel, kind, payload)
    }

    private fun payloadLength(): Int {
        val len = ByteBuffer.wrap(buf, start + 5, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (len < 0 || len > maxPayload) {
            throw ProtocolException(
                "frame payload ${len.toLong() and 0xFFFFFFFFL} exceeds $maxPayload",
                ProtocolException.Reason.OVERSIZE_FRAME,
            )
        }
        return len
    }

    private fun ensureRoom(extra: Int) {
        if (end + extra <= buf.size) return
        if (buffered + extra <= buf.size) {
            System.arraycopy(buf, start, buf, 0, buffered)
        } else {
            val bigger = ByteArray(maxOf(buf.size * 2, buffered + extra))
            System.arraycopy(buf, start, bigger, 0, buffered)
            buf = bigger
        }
        end = buffered
        start = 0
    }

    private companion object {
        const val INITIAL = 4096
        const val SHRINK_ABOVE = 1 shl 20
    }
}
