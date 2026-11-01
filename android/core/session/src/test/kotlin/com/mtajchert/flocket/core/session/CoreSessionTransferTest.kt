package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.common.result.errorOrNull
import com.mtajchert.flocket.core.common.result.getOrThrow
import com.mtajchert.flocket.core.common.time.Clock
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.CoreEndpoint
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.MAX_TRANSFER_BYTES
import com.mtajchert.flocket.core.model.PutReceipt
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.protocol.testing.FakeCore
import com.mtajchert.flocket.core.protocol.testing.FakeCoreConfig
import com.mtajchert.flocket.core.protocol.transport.ByteTransport
import com.mtajchert.flocket.core.protocol.transport.SiblingTransportSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class CoreSessionTransferTest {
    private val t3 = TerminalAddress("rohan/t3")
    private val png = "\u0089PNG\r\n\u001a\nhello".toByteArray(Charsets.ISO_8859_1)

    private suspend fun TestScope.eventually(
        what: String = "condition",
        cond: () -> Boolean,
    ) {
        repeat(2_000) {
            if (cond()) return
            delay(1)
        }
        fail("timed out waiting for $what")
    }

    private fun TestScope.host() = FakeHost(backgroundScope).apply { terminals += FakeCore.terminal("rohan/t3") }

    private suspend fun TestScope.online(host: FakeHost): CoreSession =
        host.session(this).also {
            it.start()
            it.awaitOnline()
        }

    /** The main connection is `cores[0]`; the transfer connection is the next one the factory hands out. */
    private val FakeHost.main get() = cores[0]
    private val FakeHost.transfer get() = cores[1]

    private fun exec(
        stdout: ByteArray,
        code: Int? = 0,
        truncated: Boolean = false,
    ) = buildJsonObject {
        put("stdout", Base64.getEncoder().encodeToString(stdout))
        put("stderr", "")
        put("truncated", truncated)
        put("timed_out", false)
        put("ms", 1)
        if (code != null) put("code", code)
    }

    private fun JsonObject.argv() = this["argv"]!!.jsonArray.map { it.jsonPrimitive.content }

    /** Answers `wc -c` with [size] (or [wcCode]) and `cat` with [content]. */
    private fun FakeCore.serveFile(
        content: ByteArray,
        size: Long = content.size.toLong(),
        wcCode: Int = 0,
        truncated: Boolean = false,
    ) {
        handlers["machine.exec"] = { p ->
            if (p.argv().first() == "sh") {
                exec(if (wcCode == 0) "   $size\n".toByteArray() else ByteArray(0), wcCode)
            } else {
                exec(content, truncated = truncated)
            }
        }
    }

    @Test
    fun `putFile uploads on the transfer connection, not the main one`() =
        runTest {
            val host = host()
            val s = online(host)
            val r = s.putFile(t3, "shot.png", png).getOrThrow()
            assertEquals(PutReceipt("/fake/files/rohan/t3/shot.png", png.size.toLong()), r)
            assertTrue(host.main.callsOf("terminal.put").isEmpty())
            val put =
                host.transfer
                    .callsOf("terminal.put")
                    .single()
                    .params
            assertEquals("rohan/t3", put["terminal"]!!.jsonPrimitive.content)
            assertEquals("shot.png", put["name"]!!.jsonPrimitive.content)
            assertArrayEquals(png, Base64.getDecoder().decode(put["data"]!!.jsonPrimitive.content))
            // The transfer connection is reused for the next transfer.
            s.putFile(t3, "two.png", png).getOrThrow()
            assertEquals(2, host.cores.size)
            assertEquals(2, host.transfer.callsOf("terminal.put").size)
            s.stop()
        }

    @Test
    fun `putFile opens a sibling on the main transport when it offers one`() =
        runTest {
            val main = FakeCore(backgroundScope)
            val sibling = FakeCore(backgroundScope)
            var factoryCalls = 0
            var siblingsOpened = 0
            val transport =
                object : ByteTransport by main.client, SiblingTransportSource {
                    override suspend fun openSibling(): ByteTransport {
                        siblingsOpened++
                        return sibling.client
                    }
                }
            val s =
                CoreSessionImpl(
                    CoreEndpoint(HostId("h1")),
                    {
                        factoryCalls++
                        AppResult.Success(transport)
                    },
                    backgroundScope,
                    Clock { testScheduler.currentTime },
                    config = SessionConfig(random = FixedRandom(0.5)),
                )
            s.start()
            s.awaitOnline()
            s.putFile(t3, "shot.png", png).getOrThrow()
            assertEquals(1, factoryCalls)
            assertEquals(1, siblingsOpened)
            assertEquals(1, sibling.callsOf("terminal.put").size)
            assertTrue(main.callsOf("terminal.put").isEmpty())
            s.stop()
        }

    @Test
    fun `putFile dropped after write is OutcomeUnknown and is not retried`() =
        runTest {
            val host = host()
            host.configure = { c ->
                c.handlers["terminal.put"] = {
                    c.dropConnection()
                    awaitCancellation()
                }
            }
            val s = online(host)
            assertEquals(AppError.OutcomeUnknown("terminal.put"), s.putFile(t3, "a.png", png).errorOrNull())
            runCurrent()
            assertEquals(1, host.cores.sumOf { it.callsOf("terminal.put").size })
            // The main connection is untouched.
            assertTrue(s.state.value.connection is ConnectionState.Online)
            s.stop()
        }

    @Test
    fun `putFile over 8 MB fails without any call`() =
        runTest {
            val host = host()
            val s = online(host)
            val big = ByteArray(MAX_TRANSFER_BYTES.toInt() + 1)
            assertEquals(AppError.InvalidInput("file", "larger than 8 MB"), s.putFile(t3, "big", big).errorOrNull())
            assertEquals(
                AppError.InvalidInput("file", "larger than 8 MB"),
                s.writeFile(null, "/tmp/big", big).errorOrNull(),
            )
            assertEquals(1, host.cores.size)
            assertTrue(host.main.callsOf("terminal.put").isEmpty())
            s.stop()
        }

    @Test
    fun `writeFile passes on and path unchanged`() =
        runTest {
            val host = host()
            val s = online(host)
            s.writeFile("rohan", "/srv/my dir/notes.txt", png).getOrThrow()
            s.writeFile(null, "/tmp/local.txt", png).getOrThrow()
            val (remote, local) = host.transfer.callsOf("file.write").map { it.params }
            assertEquals("rohan", remote["on"]!!.jsonPrimitive.content)
            assertEquals("/srv/my dir/notes.txt", remote["path"]!!.jsonPrimitive.content)
            assertArrayEquals(png, Base64.getDecoder().decode(remote["data"]!!.jsonPrimitive.content))
            assertNull(local["on"])
            assertTrue(host.main.callsOf("file.write").isEmpty())
            s.stop()
        }

    @Test
    fun `readFile checks size first and returns exact bytes`() =
        runTest {
            val host = host()
            host.configure = { it.serveFile(png) }
            val s = online(host)
            assertArrayEquals(png, s.readFile("rohan", "/srv/shot.png").getOrThrow())
            val (wc, cat) = host.transfer.callsOf("machine.exec").map { it.params }
            assertEquals(listOf("sh", "-c", "wc -c < \"\$1\"", "sh", "/srv/shot.png"), wc.argv())
            assertEquals(listOf("cat", "--", "/srv/shot.png"), cat.argv())
            assertEquals("rohan", wc["on"]!!.jsonPrimitive.content)
            assertEquals("rohan", cat["on"]!!.jsonPrimitive.content)
            assertTrue(host.main.callsOf("machine.exec").isEmpty())
            s.stop()
        }

    @Test
    fun `readFile of a missing file is FileMissing`() =
        runTest {
            val host = host()
            host.configure = { it.serveFile(png, wcCode = 1) }
            val s = online(host)
            assertEquals(AppError.FileMissing("/srv/gone.png"), s.readFile(null, "/srv/gone.png").errorOrNull())
            assertEquals(1, host.transfer.callsOf("machine.exec").size) // no cat
            s.stop()
        }

    @Test
    fun `readFile of a file over 8 MB is FileTooLarge without cat`() =
        runTest {
            val host = host()
            host.configure = { it.serveFile(png, size = MAX_TRANSFER_BYTES + 1) }
            val s = online(host)
            assertEquals(AppError.FileTooLarge(MAX_TRANSFER_BYTES), s.readFile(null, "/srv/big").errorOrNull())
            assertEquals(1, host.transfer.callsOf("machine.exec").size)
            s.stop()
        }

    @Test
    fun `readFile of truncated output is FileTooLarge`() =
        runTest {
            val host = host()
            host.configure = { it.serveFile(png, truncated = true) }
            val s = online(host)
            assertEquals(AppError.FileTooLarge(MAX_TRANSFER_BYTES), s.readFile(null, "/srv/grew").errorOrNull())
            s.stop()
        }

    @Test
    fun `readFile retries once after a drop`() =
        runTest {
            val host = host()
            val dropped = AtomicBoolean(false)
            host.configure = { c ->
                c.serveFile(png)
                val serve = c.handlers.getValue("machine.exec")
                c.handlers["machine.exec"] = { p ->
                    if (p.argv().first() == "cat" && dropped.compareAndSet(false, true)) {
                        c.dropConnection()
                        awaitCancellation()
                    }
                    serve(p)
                }
            }
            val s = online(host)
            assertArrayEquals(png, s.readFile(null, "/srv/shot.png").getOrThrow())
            assertEquals(3, host.cores.size) // main, first transfer, fresh transfer
            assertEquals(2, host.cores.sumOf { c -> c.callsOf("machine.exec").count { it.params.argv()[0] == "cat" } })
            assertTrue(s.state.value.connection is ConnectionState.Online)
            s.stop()
        }

    @Test
    fun `exec runs argv on the machine and reports the outcome`() =
        runTest {
            val host = host()
            host.configure = { c -> c.handlers["machine.exec"] = { exec("yes\n".toByteArray(), code = 1) } }
            val s = online(host)
            val out = s.exec("rohan", listOf("test", "-e", "/srv/x"), mutating = false).getOrThrow()
            assertEquals(1, out.code)
            assertArrayEquals("yes\n".toByteArray(), out.stdout)
            assertFalse(out.truncated)
            val p =
                host.cores
                    .flatMap { it.callsOf("machine.exec") }
                    .single()
                    .params
            assertEquals(listOf("test", "-e", "/srv/x"), p.argv())
            assertEquals("rohan", p["on"]!!.jsonPrimitive.content)
            s.stop()
        }

    @Test
    fun `mutating exec dropped after write is OutcomeUnknown and is not retried`() =
        runTest {
            val host = host()
            host.configure = { c ->
                c.handlers["machine.exec"] = {
                    c.dropConnection()
                    awaitCancellation()
                }
            }
            val s = online(host)
            assertEquals(
                AppError.OutcomeUnknown("machine.exec"),
                s.exec(null, listOf("chmod", "+x", "/x"), mutating = true).errorOrNull(),
            )
            s.awaitOnline()
            runCurrent()
            assertEquals(1, host.cores.sumOf { it.callsOf("machine.exec").size })
            s.stop()
        }

    @Test
    fun `transfer calls are Unsupported when the core does not advertise them`() =
        runTest {
            val host = host()
            host.coreConfig =
                FakeCoreConfig(
                    methods = FakeCoreConfig.DEFAULT_METHODS - "terminal.put" - "file.write" - "machine.exec",
                )
            val s = online(host)
            assertEquals(AppError.Unsupported("terminal.put"), s.putFile(t3, "a", png).errorOrNull())
            assertEquals(AppError.Unsupported("file.write"), s.writeFile(null, "/a", png).errorOrNull())
            assertEquals(AppError.Unsupported("machine.exec"), s.readFile(null, "/a").errorOrNull())
            assertEquals(AppError.Unsupported("machine.exec"), s.exec(null, listOf("true"), false).errorOrNull())
            assertEquals(1, host.cores.size)
            s.stop()
        }

    @Test
    fun `transfer connection closes after 30 s idle`() =
        runTest {
            val host = host()
            // The fake sends no heartbeats: keep the main watchdog out of this minute of virtual time.
            val s = host.session(this, SessionConfig(random = FixedRandom(0.5), watchdogTimeout = 10.minutes))
            s.start()
            s.awaitOnline()
            s.putFile(t3, "a.png", png).getOrThrow()
            val transfer = host.transports[1]
            advanceTimeBy(29.seconds)
            runCurrent()
            assertFalse(transfer.closed)
            // Activity restarts the idle clock.
            s.putFile(t3, "b.png", png).getOrThrow()
            advanceTimeBy(29.seconds)
            runCurrent()
            assertFalse(transfer.closed)
            advanceTimeBy(2.seconds)
            runCurrent()
            assertTrue(transfer.closed)
            assertFalse(host.transports[0].closed)
            // The next transfer opens a new one.
            s.putFile(t3, "c.png", png).getOrThrow()
            assertEquals(3, host.cores.size)
            s.stop()
        }

    @Test
    fun `stop closes the transfer connection`() =
        runTest {
            val host = host()
            val s = online(host)
            s.putFile(t3, "a.png", png).getOrThrow()
            s.stop()
            eventually("transfer closed") { host.transports[1].closed }
        }

    @Test
    fun `writes are refused while stale`() =
        runTest {
            val host = host()
            host.gates[2] = CompletableDeferred() // hold the reconnect
            val s = online(host)
            host.main.dropConnection()
            s.awaitConnection { it !is ConnectionState.Online }
            assertTrue(s.state.value.isStale)
            assertEquals(AppError.ConnectionLost, s.putFile(t3, "a.png", png).errorOrNull())
            assertEquals(AppError.ConnectionLost, s.writeFile(null, "/tmp/a", png).errorOrNull())
            assertEquals(AppError.ConnectionLost, s.exec(null, listOf("chmod", "+x", "/a"), true).errorOrNull())
            assertEquals(1, host.cores.size) // no transfer connection was opened
            host.gates.getValue(2).complete(Unit)
            s.stop()
        }
}
