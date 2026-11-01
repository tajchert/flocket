package com.mtajchert.flocket.core.protocol

import com.mtajchert.flocket.core.protocol.wire.Frame
import com.mtajchert.flocket.core.protocol.wire.FrameCodec
import com.mtajchert.flocket.core.protocol.wire.FrameDecoder
import com.mtajchert.flocket.core.protocol.wire.FrameKind
import com.mtajchert.flocket.core.protocol.wire.HelloLineReader
import com.mtajchert.flocket.core.protocol.wire.ProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameDecoderTest {
    private fun drain(d: FrameDecoder): List<Frame> = generateSequence { d.next() }.toList()

    @Test
    fun `decodes frames fed one byte at a time`() {
        val wire = FrameCodec.data(2, "hello".toByteArray()) + FrameCodec.credit(2, 5) + FrameCodec.close(2)
        val d = FrameDecoder()
        val out = mutableListOf<Frame>()
        for (b in wire) {
            d.feed(byteArrayOf(b))
            out += drain(d)
        }
        assertEquals(listOf(FrameKind.DATA, FrameKind.CREDIT, FrameKind.CLOSE), out.map { it.kind })
        assertEquals("hello", out[0].payload.decodeToString())
        assertEquals(5, FrameCodec.readLe32(out[1].payload))
        assertEquals(0, out[2].payload.size)
        assertEquals(0, d.buffered)
    }

    @Test
    fun `decodes several frames coalesced in one read`() {
        val wire = (1..50).map { FrameCodec.data(it, ByteArray(it) { i -> i.toByte() }) }.reduce(ByteArray::plus)
        val d = FrameDecoder()
        d.feed(wire)
        val frames = drain(d)
        assertEquals(50, frames.size)
        assertEquals(50, frames.last().channel)
        assertEquals(50, frames.last().payload.size)
    }

    @Test
    fun `handles a header split across reads`() {
        val wire = FrameCodec.data(7, byteArrayOf(1, 2, 3))
        val d = FrameDecoder()
        d.feed(wire, 0, 4)
        assertNull(d.next())
        d.feed(wire, 4, 6)
        assertNull(d.next())
        d.feed(wire, 10, wire.size - 10)
        val f = d.next()!!
        assertEquals(7, f.channel)
        assertArrayEquals(byteArrayOf(1, 2, 3), f.payload)
    }

    @Test
    fun `accepts zero length payload`() {
        val d = FrameDecoder()
        d.feed(FrameCodec.encode(3, FrameKind.DATA))
        assertEquals(0, d.next()!!.payload.size)
    }

    @Test
    fun `rejects an oversize length as soon as the header is complete`() {
        val d = FrameDecoder()
        val header = FrameCodec.encode(1, FrameKind.DATA).copyOf(9)
        System.arraycopy(FrameCodec.le32(16 * 1024 * 1024 + 1), 0, header, 5, 4)
        val ex = assertThrows(ProtocolException::class.java) { d.feed(header) }
        assertEquals(ProtocolException.Reason.OVERSIZE_FRAME, ex.reason)
    }

    @Test
    fun `accepts the maximum payload and rejects a length with the high bit set`() {
        val max = FrameDecoder()
        val h = ByteArray(9)
        System.arraycopy(FrameCodec.le32(16 * 1024 * 1024), 0, h, 5, 4)
        max.feed(h) // announces exactly the maximum: fine, just incomplete
        assertNull(max.next())
        val neg = ByteArray(9) { if (it >= 5) 0xFF.toByte() else 0 }
        assertThrows(ProtocolException::class.java) { FrameDecoder().feed(neg) }
    }

    @Test
    fun `caps the total buffered bytes`() {
        val d = FrameDecoder(maxPayload = 1000, maxBuffered = 100)
        d.feed(FrameCodec.encode(1, FrameKind.DATA, ByteArray(500)).copyOf(60))
        val ex = assertThrows(ProtocolException::class.java) { d.feed(ByteArray(60)) }
        assertEquals(ProtocolException.Reason.BUFFER_OVERFLOW, ex.reason)
    }

    @Test
    fun `passes unknown kinds through for the caller to ignore`() {
        val d = FrameDecoder()
        d.feed(FrameCodec.encode(1, 99, byteArrayOf(1)))
        assertEquals(99, d.next()!!.kind)
    }

    @Test
    fun `resize and focus encoders match the spec example`() {
        // 02000000 03 04000000 6400 1e00 = RESIZE ch 2, 100x30
        val expected = "02000000030400000064001e00"
        assertEquals(expected, FrameCodec.resize(2, 100, 30).joinToString("") { "%02x".format(it) })
        assertEquals("01000000040100000001", FrameCodec.focus(1, true).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `hello reader returns the line and keeps the remainder`() {
        val r = HelloLineReader()
        val res = r.feed("{\"id\":0}\n".toByteArray() + FrameCodec.close(1), 0, 9 + 9)!!
        assertEquals("{\"id\":0}", res.line.decodeToString())
        assertArrayEquals(FrameCodec.close(1), res.remainder)
    }

    @Test
    fun `hello reader waits for the newline across reads`() {
        val r = HelloLineReader()
        assertNull(r.feed("{\"id\"".toByteArray(), 0, 5))
        assertTrue(r.feed(":0}\n".toByteArray(), 0, 4) != null)
    }

    @Test
    fun `hello reader reports a banner clearly`() {
        val bytes = "Welcome to Ubuntu 24.04\n".toByteArray()
        val ex = assertThrows(ProtocolException::class.java) { HelloLineReader().feed(bytes, 0, bytes.size) }
        assertEquals(ProtocolException.Reason.GARBAGE_BEFORE_HELLO, ex.reason)
        assertTrue(ex.message!!.contains("Welcome to Ubuntu"))
    }
}
