package com.mtajchert.flocket.core.protocol

import com.mtajchert.flocket.core.protocol.rpc.ProtocolJson
import com.mtajchert.flocket.core.protocol.rpc.RpcMessage
import com.mtajchert.flocket.core.protocol.wire.Frame
import com.mtajchert.flocket.core.protocol.wire.FrameDecoder
import com.mtajchert.flocket.core.protocol.wire.HelloLineReader
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays real bytes captured from hc 0.1.15 (protocol/fixtures) through the decoders. */
class FixtureReplayTest {
    private class Replay(
        val helloLine: String,
        val frames: List<Frame>,
    )

    /** Plain hello line first, then frames; chunk boundaries exactly as captured. */
    private fun replay(chunks: List<ByteArray>): Replay {
        val reader = HelloLineReader()
        val decoder = FrameDecoder()
        var hello: String? = null
        val frames = mutableListOf<Frame>()
        for (c in chunks) {
            if (hello == null) {
                val r = reader.feed(c, 0, c.size) ?: continue
                hello = r.line.decodeToString()
                decoder.feed(r.remainder)
            } else {
                decoder.feed(c)
            }
            while (true) frames += decoder.next() ?: break
        }
        assertEquals("no partial frame may remain", 0, decoder.buffered)
        return Replay(hello!!, frames)
    }

    private fun decodedFrames(
        name: String,
        dir: String,
    ) = Fixtures.lines(name).filter {
        it["mode"]?.jsonPrimitive?.content == "framed" &&
            it["dir"]?.jsonPrimitive?.content == dir
    }

    private fun assertMatches(
        frames: List<Frame>,
        expected: List<kotlinx.serialization.json.JsonObject>,
    ) {
        assertEquals(expected.size, frames.size)
        frames.zip(expected).forEachIndexed { i, (f, e) ->
            assertEquals("channel #$i", e["channel"]!!.jsonPrimitive.int, f.channel)
            assertEquals("kind #$i", e["kind"]!!.jsonPrimitive.int, f.kind)
            assertEquals("len #$i", e["len"]!!.jsonPrimitive.int, f.payload.size)
            e["hex"]?.let {
                assertEquals("hex #$i", it.jsonPrimitive.content, f.payload.joinToString("") { b -> "%02x".format(b) })
            }
            if (f.channel == 0 && f.kind == 0) {
                assertEquals(e["json"], ProtocolJson.parseToJsonElement(f.payload.decodeToString()))
            }
        }
    }

    @Test
    fun `framed session core output decodes to the recorded frames`() {
        val r = replay(Fixtures.chunks("framed-session.chunks.jsonl", "in"))
        val reply = ProtocolJson.decodeFromString(RpcMessage.serializer(), r.helloLine)
        assertTrue(reply.result != null)
        assertMatches(r.frames, decodedFrames("framed-session.decoded.jsonl", "in"))
    }

    @Test
    fun `framed session client output decodes to the recorded frames`() {
        val r = replay(Fixtures.chunks("framed-session.chunks.jsonl", "out"))
        assertTrue(r.helloLine.contains("\"hello\""))
        assertMatches(r.frames, decodedFrames("framed-session.decoded.jsonl", "out"))
    }

    @Test
    fun `framed session decodes when everything arrives in a single read`() {
        val one = Fixtures.chunks("framed-session.chunks.jsonl", "in").reduce(ByteArray::plus)
        val r = replay(listOf(one))
        assertMatches(r.frames, decodedFrames("framed-session.decoded.jsonl", "in"))
    }

    @Test
    fun `framed session decodes when delivered byte by byte`() {
        val all = Fixtures.chunks("framed-session.chunks.jsonl", "in").reduce(ByteArray::plus)
        val r = replay(all.map { byteArrayOf(it) })
        assertMatches(r.frames, decodedFrames("framed-session.decoded.jsonl", "in"))
    }

    @Test
    fun `pipelined hello reply and frame in one read leave the frame to the decoder`() {
        // The recorded run delivered them separately; merge both reads to force the pipelined shape.
        val merged = Fixtures.chunks("pipelined.chunks.jsonl", "in").reduce(ByteArray::plus)
        val r = replay(listOf(merged))
        assertMatches(r.frames, decodedFrames("pipelined.decoded.jsonl", "in"))
        assertEquals(1, r.frames.size)
    }

    @Test
    fun `pipelined client write of hello plus frame decodes`() {
        val r = replay(Fixtures.chunks("pipelined.chunks.jsonl", "out"))
        assertMatches(r.frames, decodedFrames("pipelined.decoded.jsonl", "out"))
    }

    @Test
    fun `first paint matches the attach payload in the framed session`() {
        val hex = Fixtures.text("attach-first-paint.hex")
        val bytes = Fixtures.hex(hex)
        assertEquals(listOf<Byte>(0x1b, '['.code.toByte(), '!'.code.toByte(), 'p'.code.toByte()), bytes.take(4))
        val paint =
            decodedFrames("framed-session.decoded.jsonl", "in").first {
                it["channel"]!!.jsonPrimitive.int == 2 &&
                    it["kind"]!!.jsonPrimitive.int == 0
            }
        assertEquals(hex, paint["hex"]!!.jsonPrimitive.content)
    }

    @Test
    fun `plain session hello error shows the unsupported generation kind`() {
        val line =
            Fixtures
                .lines("plain-session.decoded.jsonl")
                .mapNotNull {
                    it["msg"]?.toString()
                }.first { it.contains("unsupported_generation") }
        val msg = ProtocolJson.decodeFromString(RpcMessage.serializer(), line)
        assertEquals("unsupported_generation", msg.error!!.data!!.kind)
    }
}
