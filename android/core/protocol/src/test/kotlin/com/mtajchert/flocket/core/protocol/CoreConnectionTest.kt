package com.mtajchert.flocket.core.protocol

import app.cash.turbine.test
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.time.Clock
import com.mtajchert.flocket.core.protocol.dto.DisplayDto
import com.mtajchert.flocket.core.protocol.rpc.RpcException
import com.mtajchert.flocket.core.protocol.testing.FakeCore
import com.mtajchert.flocket.core.protocol.testing.FakeCoreConfig
import com.mtajchert.flocket.core.protocol.testing.FakeRpcError
import com.mtajchert.flocket.core.protocol.wire.FrameCodec
import com.mtajchert.flocket.core.protocol.wire.FrameKind
import com.mtajchert.flocket.core.protocol.wire.ProtocolException
import com.mtajchert.flocket.core.protocol.wire.WireLimits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CoreConnectionTest {
    private val t = 5.seconds

    private suspend inline fun <reified E : Throwable> thrown(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw e
        }
        throw AssertionError("expected ${E::class.simpleName}")
    }

    private suspend fun TestScope.open(
        core: FakeCore,
        options: ConnectionOptions = ConnectionOptions(),
    ): CoreConnection = CoreConnection.open(core.client, backgroundScope, options)

    private fun fake(
        scope: CoroutineScope,
        config: FakeCoreConfig = FakeCoreConfig(),
    ) = FakeCore(scope, config).also {
        it.terminals += FakeCore.terminal("t1", "one")
        it.terminals += FakeCore.terminal("t2")
    }

    // ---- hello / compatibility -----------------------------------------------------------------

    @Test
    fun `hello succeeds and reports core info`() =
        runTest {
            val conn = open(fake(backgroundScope))
            assertEquals("fakehost", conn.info.name)
            assertEquals(1, conn.info.generation)
            assertTrue(conn.info.supports("terminal.attach"))
        }

    private fun hello(version: String) =
        com.mtajchert.flocket.core.protocol.dto.HelloResult(
            features = CoreConnection.REQUIRED_FEATURES.toList(),
            framed = true,
            generation = CoreConnection.GENERATION,
            methods = CoreConnection.REQUIRED_METHODS.toList(),
            version = version,
        )

    @Test
    fun `minimum core version 0_1_16 is enforced and newer or unparsable versions pass`() =
        runTest {
            for (ok in listOf("0.1.16", "0.1.17", "0.2.0", "1.0.0", "0.1.16-rc1", "0.1.16+abc", "")) {
                assertEquals(ok, CoreConnection.checkCompatible(hello(ok)).version)
            }
            val ex = thrown<CoreIncompatibleException> { CoreConnection.checkCompatible(hello("0.1.15")) }
            val err = ex.error as AppError.CoreIncompatible
            assertEquals("0.1.15", err.coreVersion)
            assertTrue(err.reason, err.reason.contains("0.1.15") && err.reason.contains("0.1.16"))
        }

    @Test
    fun `a real 0_1_15 fixture hello is rejected as too old`() =
        runTest {
            val result =
                Fixtures
                    .lines("framed-session.decoded.jsonl")[1]
                    .getValue("msg")
                    .jsonObject
                    .getValue("result")
            val hello =
                com.mtajchert.flocket.core.protocol.rpc.ProtocolJson.decodeFromJsonElement(
                    com.mtajchert.flocket.core.protocol.dto.HelloResult
                        .serializer(),
                    result,
                )
            assertEquals("0.1.15", hello.version)
            thrown<CoreIncompatibleException> { CoreConnection.checkCompatible(hello) }
        }

    @Test
    fun `a newer unknown version is accepted`() =
        runTest {
            val conn = open(fake(backgroundScope, FakeCoreConfig(version = "99.12.7-beta+x")))
            assertEquals("99.12.7-beta+x", conn.info.version)
        }

    @Test
    fun `unsupported generation is reported as CoreIncompatible`() =
        runTest {
            val ex =
                thrown<CoreIncompatibleException> {
                    runBlockingOpen(
                        fake(backgroundScope, FakeCoreConfig(generation = 2)),
                    )
                }
            assertTrue(ex.error is AppError.CoreIncompatible)
        }

    @Test
    fun `missing required method is reported as CoreIncompatible naming the method`() =
        runTest {
            val cfg = FakeCoreConfig(methods = FakeCoreConfig.DEFAULT_METHODS - "terminal.attach")
            val ex = thrown<CoreIncompatibleException> { runBlockingOpen(fake(backgroundScope, cfg)) }
            assertTrue(ex.error.reason.contains("terminal.attach"))
            assertEquals("9.9.9", ex.error.coreVersion)
        }

    @Test
    fun `missing required feature is reported as CoreIncompatible`() =
        runTest {
            val cfg = FakeCoreConfig(features = listOf("framed", "attach", "events"))
            val ex = thrown<CoreIncompatibleException> { runBlockingOpen(fake(backgroundScope, cfg)) }
            assertTrue(ex.error.reason.contains("status"))
        }

    @Test
    fun `a core that does not switch to framed mode is incompatible`() =
        runTest {
            val ex =
                thrown<CoreIncompatibleException> {
                    runBlockingOpen(
                        fake(backgroundScope, FakeCoreConfig(framed = false)),
                    )
                }
            assertTrue(ex.error.reason.contains("framed"))
        }

    @Test
    fun `garbage before hello is reported as a banner problem and closes the transport`() =
        runTest {
            val core = fake(backgroundScope, FakeCoreConfig(banner = "Last login: Sat Nov 1 10:00:00 2026\n"))
            val ex = thrown<ProtocolException> { runBlockingOpen(core) }
            assertEquals(ProtocolException.Reason.GARBAGE_BEFORE_HELLO, ex.reason)
            assertTrue(ex.message!!.contains("Last login"))
        }

    @Test
    fun `eof before hello surfaces stderr diagnostics`() =
        runTest {
            val (client, server) =
                com.mtajchert.flocket.core.protocol.testing.PipeTransport
                    .pair()
            client.stderr = "hc: command not found"
            backgroundScope.launch {
                server.close()
            }
            val ex = thrown<ConnectionClosedException> { runBlockingOpen(client) }
            assertEquals("hc: command not found", ex.diagnostics)
        }

    private suspend fun TestScope.runBlockingOpen(core: FakeCore) = CoreConnection.open(core.client, backgroundScope)

    private suspend fun TestScope.runBlockingOpen(
        transport: com.mtajchert.flocket.core.protocol.testing.PipeTransport,
    ) = CoreConnection.open(transport, backgroundScope)

    // ---- calls ---------------------------------------------------------------------------------

    @Test
    fun `replies may arrive out of order and are matched by id`() =
        runTest {
            val core = fake(backgroundScope)
            val gate = CompletableDeferred<Unit>()
            core.gates["terminal.get"] = gate
            val conn = open(core)
            val slow = async { conn.call("terminal.get", buildJsonObject { put("terminal", "t1") }, t) }
            runCurrent()
            val fast = conn.call("terminal.list", timeout = t)
            assertFalse(slow.isCompleted)
            assertNotNull(fast)
            gate.complete(Unit)
            assertTrue(slow.await().toString().contains("\"t1\""))
        }

    @Test
    fun `rpc errors carry kind message hint and code`() =
        runTest {
            val core = fake(backgroundScope)
            core.errors["terminal.get"] = FakeRpcError("not_found", "no such terminal: t9", hint = "try hc ls")
            val conn = open(core)
            val ex = thrown<RpcException> { conn.call("terminal.get", timeout = t) }
            assertEquals("not_found", ex.kind)
            assertEquals("no such terminal: t9", ex.message)
            assertEquals("try hc ls", ex.hint)
            assertEquals(-32000, ex.code)
            assertEquals(AppError.Rpc("not_found", "no such terminal: t9", "try hc ls"), ex.toAppError("get"))
        }

    @Test
    fun `unknown method maps to the unknown_method kind`() =
        runTest {
            val conn = open(fake(backgroundScope))
            val ex = runCatching { conn.call("nope.nothing", timeout = t) }.exceptionOrNull() as RpcException
            assertEquals("unknown_method", ex.kind)
        }

    @Test
    fun `a call that gets no reply times out and reports it was written`() =
        runTest {
            val core = fake(backgroundScope)
            core.delays["terminal.list"] = 60.seconds
            val conn = open(core)
            val ex =
                runCatching {
                    conn.call(
                        "terminal.list",
                        timeout = 2.seconds,
                    )
                }.exceptionOrNull() as RpcTimeoutException
            assertTrue(ex.written)
            assertEquals(AppError.OutcomeUnknown("list"), ex.toAppError("list", mutating = true))
            assertEquals(AppError.Timeout("list"), ex.toAppError("list"))
        }

    @Test
    fun `pending calls are bounded`() =
        runTest {
            val core = fake(backgroundScope)
            core.gates["terminal.list"] = CompletableDeferred()
            val conn = open(core, ConnectionOptions(maxPendingCalls = 2))
            repeat(2) { backgroundScope.launch { runCatching { conn.call("terminal.list", timeout = 60.seconds) } } }
            runCurrent()
            thrown<TooManyPendingCallsException> { conn.call("terminal.list", timeout = t) }
        }

    @Test
    fun `connection drop fails pending calls with written true`() =
        runTest {
            val core = fake(backgroundScope)
            core.gates["terminal.list"] = CompletableDeferred()
            val conn = open(core)
            val pending = async { runCatching { conn.call("terminal.list", timeout = 30.seconds) }.exceptionOrNull() }
            runCurrent()
            core.dropConnection()
            val ex = pending.await() as ConnectionClosedException
            assertTrue(ex.written)
            assertTrue(conn.isClosed)
            assertNotNull(conn.end.value)
        }

    @Test
    fun `calls after the connection closed fail with written false`() =
        runTest {
            val conn = open(fake(backgroundScope))
            conn.close()
            val ex =
                runCatching {
                    conn.call(
                        "terminal.list",
                        timeout = t,
                    )
                }.exceptionOrNull() as ConnectionClosedException
            assertFalse(ex.written)
            assertEquals(AppError.ConnectionLost, ex.toAppError("x", mutating = true))
        }

    @Test
    fun `a write failure closes the connection and marks the call written`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            core.client.breakLink()
            val ex = runCatching { conn.call("terminal.list", timeout = t) }.exceptionOrNull()
            assertTrue(ex is ConnectionClosedException)
            assertTrue(conn.isClosed)
        }

    @Test
    fun `last received timestamp follows incoming bytes`() =
        runTest {
            var now = 1000L
            val core = fake(backgroundScope)
            val conn = open(core, ConnectionOptions(clock = Clock { now }))
            val sub = conn.subscribeEvents()
            assertEquals(1000L, conn.lastReceivedAtMs)
            now = 16_000L
            backgroundScope.launch { sub.notifications.first() }
            core.pushHeartbeat()
            runCurrent()
            assertEquals(16_000L, conn.lastReceivedAtMs)
        }

    // ---- events --------------------------------------------------------------------------------

    @Test
    fun `subscribe returns the snapshot and notifications reassembled across frames`() =
        runTest {
            val core = fake(backgroundScope, FakeCoreConfig(streamChunk = 7))
            val conn = open(core)
            val sub = conn.subscribeEvents()
            assertEquals(listOf("t1", "t2"), sub.terminals.map { it.address })
            assertEquals("one", sub.terminals[0].name)
            sub.notifications.test {
                core.pushEvent("title", "t1", buildJsonObject { put("title", "héllo wörld") })
                core.pushHeartbeat()
                core.pushMissed(50)
                val e = awaitItem() as CoreNotification.Event
                assertEquals("title", e.type)
                assertEquals("t1", e.terminal)
                assertEquals("héllo wörld", (e.data["title"] as kotlinx.serialization.json.JsonPrimitive).content)
                assertEquals(CoreNotification.Heartbeat(1), awaitItem())
                val m = awaitItem() as CoreNotification.Missed
                assertEquals(51, m.seq)
                assertEquals(2, m.terminals.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `event channel gets a credit boost and credit for every consumed byte`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            val sub = conn.subscribeEvents()
            runCurrent()
            assertEquals(1_048_576L, core.creditReceived(sub.channel))
            sub.notifications.test {
                core.pushEvent("size", "t1", buildJsonObject { put("cols", 1) })
                awaitItem()
                runCurrent()
                val sent = core.sentOn(sub.channel)
                assertEquals(1_048_576L + sent, core.creditReceived(sub.channel))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `unknown event types and unparseable lines do not break the stream`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            val sub = conn.subscribeEvents()
            sub.notifications.test {
                core.streamOutput(sub.channel, "not json at all\n".toByteArray())
                core.pushEvent("brand_new_type", "t1")
                val e = awaitItem() as CoreNotification.Event
                assertEquals("brand_new_type", e.type)
                assertEquals(JsonObject(emptyMap()), e.data.takeIf { it.isEmpty() })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `connection loss ends the notification flow with a ConnectionLost reason`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            val sub = conn.subscribeEvents()
            sub.notifications.test {
                core.dropConnection()
                awaitComplete()
            }
            assertTrue(sub.end.value is ChannelEnd.ConnectionLost)
        }

    @Test
    fun `unsubscribe sends CLOSE and ends the flow`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            val sub = conn.subscribeEvents()
            sub.close()
            runCurrent()
            assertEquals(ChannelEnd.LocalDetach, sub.end.value)
            assertTrue(core.streamFrames.any { it.channel == sub.channel && it.kind == FrameKind.CLOSE })
        }

    // ---- attach --------------------------------------------------------------------------------

    @Test
    fun `attach delivers the first paint and echoes input`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            val att =
                conn.attach(
                    AttachParams("t1", 80, 24, display = DisplayDto(fg = "#ffffff", cell = listOf(9, 18))),
                )
            assertEquals("t1", att.terminal.address)
            assertEquals(
                mapOf("terminal" to "t1"),
                mapOf(
                    "terminal" to
                        core.calls
                            .last()
                            .params["terminal"]!!
                            .let { (it as kotlinx.serialization.json.JsonPrimitive).content },
                ),
            )
            assertTrue(
                core.calls
                    .last()
                    .params
                    .containsKey("display"),
            )
            att.output.test {
                assertEquals("\u001b[!pfirst-paint\r\n$ ", awaitItem().decodeToString())
                att.send("ls\r".toByteArray())
                assertEquals("ls\r", awaitItem().decodeToString())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals("ls\r", core.inputOf(att.channel).decodeToString())
        }

    @Test
    fun `attach sends observe resumed and history only when set`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            conn.attach(AttachParams("t1", 80, 24))
            val plain = core.calls.last().params
            assertFalse(plain.containsKey("observe") || plain.containsKey("resumed") || plain.containsKey("history"))
            conn.attach(AttachParams("t2", 80, 24, observe = true, resumed = true, history = 50))
            val p = core.calls.last().params
            assertTrue(p.containsKey("observe") && p.containsKey("resumed") && p["history"].toString() == "50")
        }

    @Test
    fun `doc get sends on only for a remote machine`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            val local = conn.docGet("herdr-app")
            assertEquals(0L, local.version)
            assertFalse(
                core.calls
                    .last()
                    .params
                    .containsKey("on"),
            )
            conn.docGet("herdr-app", on = "rohan")
            val p = core.calls.last().params
            assertEquals("doc.get", core.calls.last().method)
            assertEquals("\"herdr-app\"", p["name"].toString())
            assertEquals("\"rohan\"", p["on"].toString())
        }

    @Test
    fun `attach errors surface as rpc exceptions`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            val ex = runCatching { conn.attach(AttachParams("t99", 80, 24)) }.exceptionOrNull() as RpcException
            assertEquals("not_found", ex.kind)
        }

    @Test
    fun `credit is returned only after the collector processed the chunk`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            val att = conn.attach(AttachParams("t1", 80, 24))
            val release = CompletableDeferred<Unit>()
            val got = CompletableDeferred<Int>()
            backgroundScope.launch {
                att.output.collect {
                    got.complete(it.size)
                    release.await()
                }
            }
            val size = got.await()
            runCurrent()
            // The only credit so far is the window boost sent with the attach.
            val boost = WireLimits.ATTACH_CREDIT_BOOST.toLong()
            assertEquals("no credit while the collector is still busy", boost, core.creditReceived(att.channel))
            release.complete(Unit)
            runCurrent()
            // Batched credit is flushed at the end of the burst (nothing else queued).
            assertEquals(boost + size, core.creditReceived(att.channel))
        }

    @Test
    fun `credit is batched per 32 KiB or per burst, never per frame`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            val att = conn.attach(AttachParams("t1", 80, 24))
            val got =
                java.util.concurrent.atomic
                    .AtomicLong()
            backgroundScope.launch { att.output.collect { got.addAndGet(it.size.toLong()) } }
            runCurrent()
            val before = core.creditReceived(att.channel)
            val writesBefore = core.creditFrames(att.channel)
            val firstPaint = got.get()
            // 64 frames of 1 KiB arrive in one burst.
            repeat(64) { core.streamOutput(att.channel, ByteArray(1024)) }
            runCurrent()
            assertEquals(64L * 1024, got.get() - firstPaint)
            assertEquals("every consumed byte is credited", 64L * 1024, core.creditReceived(att.channel) - before)
            val frames = core.creditFrames(att.channel) - writesBefore
            assertTrue("credit was batched ($frames frames)", frames in 1..4)
        }

    @Test
    fun `a stalled attach consumer does not block calls or the core limit`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            val att = conn.attach(AttachParams("t1", 80, 24))
            // Nobody collects att.output: 2 MiB of output is offered, the core may only send the window.
            core.streamOutput(att.channel, ByteArray(2 shl 20))
            runCurrent()
            assertEquals((WireLimits.WINDOW + WireLimits.ATTACH_CREDIT_BOOST).toLong(), core.sentOn(att.channel))
            val list = conn.call("terminal.list", timeout = t)
            assertNotNull(list)
            assertNull(att.closeReason.value)
        }

    @Test
    fun `frames that arrive before the attach reply are buffered`() =
        runTest {
            val core = fake(backgroundScope, FakeCoreConfig(paintBeforeReply = true))
            val conn = open(core)
            val att = conn.attach(AttachParams("t1", 80, 24))
            assertEquals("\u001b[!pfirst-paint\r\n$ ", att.output.first().decodeToString())
        }

    @Test
    fun `program exit CLOSE is parsed after the output was consumed`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            val att = conn.attach(AttachParams("t1", 80, 24))
            core.streamOutput(att.channel, "bye\r\n".toByteArray())
            core.closeStream(
                att.channel,
                buildJsonObject {
                    put("exit_code", 3)
                    put("finished_ms", 123L)
                    put("reason", "exited")
                },
            )
            val chunks = mutableListOf<String>()
            att.output.collect { chunks += it.decodeToString() }
            assertEquals("bye\r\n", chunks.joinToString("").takeLast(5))
            assertEquals(ChannelEnd.ProgramFinished("exited", 3, null, 123L), att.closeReason.value)
        }

    @Test
    fun `lost and empty CLOSE payloads are told apart`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            val a = conn.attach(AttachParams("t1", 80, 24))
            val b = conn.attach(AttachParams("t2", 80, 24))
            core.closeStream(a.channel, buildJsonObject { put("reason", "holder_lost") })
            core.closeStream(b.channel, null)
            a.output.collect {}
            b.output.collect {}
            assertEquals(ChannelEnd.ProgramLost("holder_lost", null, null), a.closeReason.value)
            assertEquals(ChannelEnd.ClosedByCore, b.closeReason.value)
        }

    @Test
    fun `detach sends CLOSE ends the flow and discards later data`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            val att = conn.attach(AttachParams("t1", 80, 24))
            runCurrent()
            att.close()
            runCurrent()
            assertEquals(ChannelEnd.LocalDetach, att.closeReason.value)
            assertTrue(core.streamFrames.any { it.channel == att.channel && it.kind == FrameKind.CLOSE })
            thrown<ChannelClosedException> { att.send(byteArrayOf(1)) }
            att.close() // idempotent
        }

    @Test
    fun `big input is split into frames of at most 64 KiB in order`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            val att = conn.attach(AttachParams("t1", 80, 24))
            val payload = ByteArray(150 * 1024) { (it % 251).toByte() }
            att.send(payload)
            runCurrent()
            val frames = core.streamFrames.filter { it.channel == att.channel && it.kind == FrameKind.DATA }
            assertEquals(listOf(65536, 65536, 150 * 1024 - 2 * 65536), frames.map { it.payload.size })
            assertArrayEquals(payload, core.inputOf(att.channel))
        }

    @Test
    fun `too much queued input fails fast without sending`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core, ConnectionOptions(maxQueuedOutputBytes = 100))
            val att = conn.attach(AttachParams("t1", 80, 24))
            thrown<InputQueueFullException> { att.send(ByteArray(200)) }
            runCurrent()
            assertTrue(core.streamFrames.none { it.kind == FrameKind.DATA })
            assertEquals(
                AppError.InvalidInput("input", "outgoing input queue exceeds 100 bytes"),
                InputQueueFullException(100).toAppError("x"),
            )
        }

    @Test
    fun `resize focus and facts are sent and a zero size never is`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            val att = conn.attach(AttachParams("t1", 80, 24))
            att.resize(0, 24)
            att.resize(80, 0)
            att.resize(100, 30)
            att.focus(true)
            att.facts(DisplayDto(fg = "#fff"))
            runCurrent()
            val kinds =
                core.streamFrames
                    .filter {
                        it.channel == att.channel && it.kind != FrameKind.CREDIT
                    }.map { it.kind }
            assertEquals(listOf(FrameKind.RESIZE, FrameKind.FOCUS, FrameKind.FACTS), kinds)
        }

    @Test
    fun `an attach reply that arrives after the timeout gets a CLOSE`() =
        runTest {
            val core = fake(backgroundScope)
            core.delays["terminal.attach"] = 10.seconds
            val conn = open(core)
            val ex = runCatching { conn.attach(AttachParams("t1", 80, 24, timeout = 2.seconds)) }.exceptionOrNull()
            assertTrue(ex is RpcTimeoutException)
            advanceTimeBy(11.seconds)
            advanceUntilIdle()
            val closes = core.streamFrames.filter { it.kind == FrameKind.CLOSE }
            assertEquals(1, closes.size)
            assertEquals(1, closes[0].channel)
        }

    @Test
    fun `connection loss ends attach output with ConnectionLost`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            val att = conn.attach(AttachParams("t1", 80, 24))
            core.dropConnection()
            att.output.collect {}
            assertTrue(att.closeReason.value is ChannelEnd.ConnectionLost)
        }

    @Test
    fun `typed helpers decode terminal list and get`() =
        runTest {
            val conn = open(fake(backgroundScope))
            val list = conn.terminalList()
            assertEquals(2, list.terminals.size)
            assertNull(list.machines)
            assertEquals("t2", conn.terminalGet("t2").address)
        }

    // ---- review fixes: stream ownership ---------------------------------------------------------

    @Test
    fun `an attach reply the client cannot decode closes the channel again`() =
        runTest {
            val core = fake(backgroundScope)
            core.handlers["terminal.attach"] = {
                buildJsonObject {
                    put("channel", 7)
                    put("terminal", 5)
                }
            }
            val conn = open(core)
            val ex = runCatching { conn.attach(AttachParams("t1", 80, 24)) }.exceptionOrNull()
            assertNotNull(ex)
            runCurrent()
            assertTrue(core.streamFrames.any { it.channel == 7 && it.kind == FrameKind.CLOSE })
            assertFalse(conn.isClosed)
        }

    @Test
    fun `a subscribe reply with a malformed snapshot closes the channel again`() =
        runTest {
            val core = fake(backgroundScope)
            core.handlers["events.subscribe"] = {
                buildJsonObject {
                    put("seq", 1)
                    put("channel", 9)
                    put("terminals", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("nope")) })
                }
            }
            val conn = open(core)
            assertNotNull(runCatching { conn.subscribeEvents() }.exceptionOrNull())
            runCurrent()
            assertTrue(core.streamFrames.any { it.channel == 9 && it.kind == FrameKind.CLOSE })
        }

    @Test
    fun `cancelling the only output collector detaches the viewer`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            val att = conn.attach(AttachParams("t1", 80, 24))
            val job = launch { att.output.collect {} }
            runCurrent()
            job.cancel()
            runCurrent()
            assertEquals(ChannelEnd.LocalDetach, att.closeReason.value)
            assertTrue(core.streamFrames.any { it.channel == att.channel && it.kind == FrameKind.CLOSE })
        }

    @Test
    fun `cancelling the only notification collector unsubscribes`() =
        runTest {
            val core = fake(backgroundScope)
            val conn = open(core)
            val sub = conn.subscribeEvents()
            val job = launch { sub.notifications.collect {} }
            runCurrent()
            job.cancel()
            runCurrent()
            assertEquals(ChannelEnd.LocalDetach, sub.end.value)
            assertTrue(core.streamFrames.any { it.channel == sub.channel && it.kind == FrameKind.CLOSE })
        }

    @Test
    fun `stray frames on a finished channel do not use up the early frame buffer`() =
        runTest {
            val core = fake(backgroundScope, FakeCoreConfig(paintBeforeReply = true))
            val conn = open(core, ConnectionOptions(maxEarlyChannels = 1))
            val first = conn.attach(AttachParams("t1", 80, 24))
            core.closeStream(first.channel)
            runCurrent()
            assertEquals(ChannelEnd.ClosedByCore, first.closeReason.value)
            core.writeRaw(FrameCodec.data(first.channel, "late".toByteArray()))
            runCurrent()
            val second = conn.attach(AttachParams("t2", 80, 24))
            val paint = withTimeout(5.seconds) { second.output.first() }
            assertEquals("\u001b[!pfirst-paint\r\n$ ", paint.decodeToString())
        }

    @Test
    fun `attach replies racing their timeout never leave a channel open`() =
        runBlocking(Dispatchers.Default) {
            // A child of runBlocking, joined below: nothing from this real-thread test may outlive it, or a late
            // failure is reported by the next runTest as UncaughtExceptionsBeforeTest (seen on slow CI runners).
            val leaked = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val scope =
                CoroutineScope(
                    coroutineContext + SupervisorJob(coroutineContext[Job]) +
                        CoroutineExceptionHandler { _, e -> leaked += e },
                )
            val core = fake(scope)
            val conn = CoreConnection.open(core.client, scope)
            repeat(200) { i ->
                val att = runCatching { conn.attach(AttachParams("t1", 80, 24, timeout = (1 + i % 3).milliseconds)) }
                att.getOrNull()?.close()
            }
            val allocated = { core.calls.count { it.method == "terminal.attach" } }
            val closed = {
                core.streamFrames
                    .filter { it.kind == FrameKind.CLOSE }
                    .map { it.channel }
                    .toSet()
            }
            val settled =
                withTimeoutOrNull(10.seconds) {
                    while ((1..allocated()).any { it !in closed() }) delay(10)
                    true
                }
            assertEquals("channels left open: ${(1..allocated()).filter { it !in closed() }}", true, settled)
            scope.coroutineContext[Job]!!.cancelAndJoin()
            assertEquals("coroutines failed: ${leaked.map { it.stackTraceToString() }}", 0, leaked.size)
        }
}
