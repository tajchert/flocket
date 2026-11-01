package com.mtajchert.flocket.core.protocol.wire

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Frame encoders (protocol/SPEC.md §3.2). All integers are little-endian. */
object FrameCodec {
    fun encode(
        channel: Int,
        kind: Int,
        payload: ByteArray = EMPTY,
        offset: Int = 0,
        length: Int = payload.size - offset,
    ): ByteArray {
        require(length in 0..WireLimits.MAX_PAYLOAD) { "payload too large: $length" }
        val buf = ByteBuffer.allocate(WireLimits.HEADER_SIZE + length).order(ByteOrder.LITTLE_ENDIAN)
        buf
            .putInt(channel)
            .put(kind.toByte())
            .putInt(length)
            .put(payload, offset, length)
        return buf.array()
    }

    fun data(
        channel: Int,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size - offset,
    ): ByteArray = encode(channel, FrameKind.DATA, payload, offset, length)

    fun credit(
        channel: Int,
        bytes: Int,
    ): ByteArray = encode(channel, FrameKind.CREDIT, le32(bytes))

    fun close(channel: Int): ByteArray = encode(channel, FrameKind.CLOSE)

    /** The core's reason payload (tests/fakes): a JSON object. */
    fun close(
        channel: Int,
        jsonReason: ByteArray,
    ): ByteArray = encode(channel, FrameKind.CLOSE, jsonReason)

    fun resize(
        channel: Int,
        cols: Int,
        rows: Int,
    ): ByteArray {
        require(cols in 1..0xFFFF && rows in 1..0xFFFF) { "invalid size ${cols}x$rows" }
        val p =
            ByteBuffer
                .allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(cols.toShort())
                .putShort(rows.toShort())
        return encode(channel, FrameKind.RESIZE, p.array())
    }

    fun focus(
        channel: Int,
        focused: Boolean,
    ): ByteArray = encode(channel, FrameKind.FOCUS, byteArrayOf(if (focused) 1 else 0))

    fun facts(
        channel: Int,
        json: ByteArray,
    ): ByteArray = encode(channel, FrameKind.FACTS, json)

    fun le32(v: Int): ByteArray =
        ByteBuffer
            .allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(v)
            .array()

    fun readLe32(
        b: ByteArray,
        o: Int = 0,
    ): Int = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private val EMPTY = ByteArray(0)
}
