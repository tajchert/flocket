package com.mtajchert.flocket.core.protocol

import com.mtajchert.flocket.core.protocol.testing.FakeCore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

class CoreCallsTransferTest {
    private val bytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 0, 0x0a, 0xff.toByte())

    @Test
    fun `terminalPut sends the full address, name and base64 data and parses the result`() =
        runTest {
            val core = FakeCore(backgroundScope)
            val c = CoreConnection.open(core.client, backgroundScope)
            val r = c.terminalPut("rohan/t3", "my shot.png", bytes, CallTimeouts.transfer(5))
            assertEquals("/fake/files/rohan/t3/my shot.png", r.path)
            assertEquals(5L, r.bytes)
            val p = core.calls.single { it.method == "terminal.put" }.params
            assertEquals("rohan/t3", p["terminal"]!!.jsonPrimitive.content)
            assertEquals("my shot.png", p["name"]!!.jsonPrimitive.content)
            assertArrayEquals(bytes, Base64.getDecoder().decode(p["data"]!!.jsonPrimitive.content))
            c.close()
        }

    @Test
    fun `fileWrite sends path, data and on, and omits on for the local machine`() =
        runTest {
            val core = FakeCore(backgroundScope)
            val c = CoreConnection.open(core.client, backgroundScope)
            val remote = c.fileWrite("/srv/a b/f.bin", bytes, "rohan", CallTimeouts.transfer(5))
            assertEquals("/srv/a b/f.bin", remote.path)
            assertEquals(5L, remote.bytes)
            c.fileWrite("/tmp/f", bytes, null, CallTimeouts.transfer(5))
            val (first, second) = core.calls.filter { it.method == "file.write" }
            assertEquals("/srv/a b/f.bin", first.params["path"]!!.jsonPrimitive.content)
            assertEquals("rohan", first.params["on"]!!.jsonPrimitive.content)
            assertArrayEquals(bytes, Base64.getDecoder().decode(first.params["data"]!!.jsonPrimitive.content))
            assertNull(second.params["on"])
            c.close()
        }

    @Test
    fun `transfer timeout is 30 s plus 1 s per 100 KiB`() {
        assertEquals(30.seconds, CallTimeouts.transfer(0))
        assertEquals(31.seconds, CallTimeouts.transfer(100L * 1024))
        assertEquals(40.seconds, CallTimeouts.transfer(1000L * 1024))
        assertEquals(30.seconds + 81.92.seconds, CallTimeouts.transfer(8L * 1024 * 1024))
    }
}
