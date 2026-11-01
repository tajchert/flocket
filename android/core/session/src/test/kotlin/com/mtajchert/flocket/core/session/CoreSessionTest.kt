package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.common.result.errorOrNull
import com.mtajchert.flocket.core.common.result.getOrThrow
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.protocol.testing.FakeCore
import com.mtajchert.flocket.core.protocol.testing.FakeCoreConfig
import com.mtajchert.flocket.core.protocol.testing.FakeRpcError
import com.mtajchert.flocket.core.protocol.wire.FrameKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class CoreSessionTest {
    private val t1 = TerminalAddress("t1")

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

    private fun TestScope.hostWith(vararg addresses: String) =
        FakeHost(backgroundScope).apply {
            addresses.forEach {
                terminals +=
                    FakeCore.terminal(it)
            }
        }

    private suspend fun CoreSession.attachOk(
        mode: AttachMode = AttachMode.Passive,
        display: DisplayFacts? = null,
    ): TerminalStream = attach(t1, AttachRequest(80, 24, mode, display = display)).getOrThrow()

    private fun FakeCore.attachParams() = callsOf("terminal.attach").last().params

    private fun String.count(s: String) = windowed(s.length).count { it == s }

    @Test
    fun `connects and publishes the snapshot in core order`() =
        runTest {
            val host = hostWith("t1", "rohan/t7")
            val s = host.session(this)
            s.start()
            s.start() // idempotent
            val online = s.awaitOnline() as ConnectionState.Online
            assertEquals("9.9.9", online.core.version)
            val st = s.state.value
            assertEquals(listOf("t1", "rohan/t7"), st.terminals.map { it.address.value })
            assertEquals("rohan", st.terminal(TerminalAddress("rohan/t7"))!!.address.machine)
            assertNotNull(st.confirmedAtMs)
            assertFalse(st.isStale)
            assertEquals(1, host.calls.get())
            assertEquals(1, host.core.callsOf("machine.list").size) // unknown_method there: non-fatal
            s.stop()
            assertEquals(ConnectionState.Idle, s.state.value.connection)
        }

    @Test
    fun `events update state and missed replaces the snapshot`() =
        runTest {
            val host = hostWith("t1")
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            host.core.pushEvent("title", "t1", buildJsonObject { put("title", "vim") })
            eventually {
                s.state.value
                    .terminal(t1)
                    ?.title == "vim"
            }
            host.core.terminals.clear()
            host.core.terminals += FakeCore.terminal("t5")
            host.core.pushMissed()
            eventually {
                s.state.value.terminals
                    .map { it.address.value } == listOf("t5")
            }
            host.core.pushEvent("title", "t5", buildJsonObject { put("title", "after") })
            eventually {
                s.state.value
                    .terminal(TerminalAddress("t5"))
                    ?.title == "after"
            }
            s.stop()
        }

    @Test
    fun `a seq gap resubscribes and re-bootstraps from the fresh snapshot`() =
        runTest {
            val host = hostWith("t1")
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            val line =
                """{"jsonrpc":"2.0","method":"event",""" +
                    """"params":{"seq":99,"type":"title","terminal":"t1","data":{"title":"x"}}}""" + "\n"
            host.core.streamOutput(host.core.eventsChannel!!, line.toByteArray())
            eventually("resubscribe") { host.core.callsOf("events.subscribe").size == 2 }
            assertEquals(
                "",
                s.state.value
                    .terminal(t1)!!
                    .title,
            )
            host.core.pushEvent("title", "t1", buildJsonObject { put("title", "y") })
            eventually {
                s.state.value
                    .terminal(t1)
                    ?.title == "y"
            }
            assertTrue(s.state.value.connection is ConnectionState.Online)
            s.stop()
        }

    @Test
    fun `drop reconnects with backoff and the durable stream continues with a fresh paint`() =
        runTest {
            val host = hostWith("t1")
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            val core1 = host.core
            repeat(3) { core1.pushEvent("title", "t1", buildJsonObject { put("title", "old$it") }) }
            eventually {
                s.state.value
                    .terminal(t1)
                    ?.title == "old2"
            }
            val stream = s.attachOk()
            val out = collectText(backgroundScope, stream.output)
            eventually("first paint") { out.contains("first-paint") }
            assertEquals(JsonPrimitive(true), core1.attachParams()["resumed"])

            core1.dropConnection()
            val rc = s.awaitConnection { it is ConnectionState.Reconnecting } as ConnectionState.Reconnecting
            assertEquals(1, rc.attempt)
            assertEquals(AppError.ConnectionLost, rc.lastError)
            assertEquals(1_000L, rc.nextAttemptAtMs - testScheduler.currentTime)
            assertTrue(s.state.value.isStale)
            assertEquals(
                listOf("t1"),
                s.state.value.terminals
                    .map { it.address.value },
            ) // kept visible
            eventually { !stream.connected.value }
            assertEquals(AppError.ConnectionLost, stream.write("lost\r".toByteArray()).errorOrNull())
            assertNull(stream.end.value)

            s.awaitOnline()
            val core2 = host.core
            assertTrue(core2 !== core1)
            eventually("reattach") { stream.connected.value }
            assertEquals(JsonPrimitive(true), core2.attachParams()["resumed"])
            eventually("second paint") { out.toString().count("first-paint") == 2 }
            // seq restarted at 0 on the new core process: its first event must apply.
            core2.pushEvent("title", "t1", buildJsonObject { put("title", "new") })
            eventually {
                s.state.value
                    .terminal(t1)
                    ?.title == "new"
            }

            assertTrue(stream.write("hi\r".toByteArray()) is AppResult.Success)
            eventually("echo") { out.contains("hi\r") }
            assertEquals("hi\r", core2.inputOf(core2.attachChannelOf("t1")!!).decodeToString()) // "lost" never replayed
            assertNull(stream.end.value)
            stream.detach()
            assertEquals(StreamEnd.Detached, stream.end.value)
            s.stop()
        }

    @Test
    fun `mutating call cut off after write is OutcomeUnknown and never re-sent`() =
        runTest {
            val host = hostWith("t1")
            host.configure = { c ->
                c.handlers["terminal.kill"] = {
                    c.dropConnection()
                    awaitCancellation()
                }
            }
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            assertEquals(AppError.OutcomeUnknown("terminal.kill"), s.kill(t1).errorOrNull())
            s.awaitConnection { it is ConnectionState.Reconnecting }
            s.awaitOnline()
            runCurrent()
            assertEquals(2, host.cores.size)
            assertEquals(1, host.cores.sumOf { it.callsOf("terminal.kill").size })
            s.stop()
        }

    @Test
    fun `writes need Online and reads retry once after a reconnect`() =
        runTest {
            val host = hostWith("t1")
            val dropped = AtomicBoolean(false)
            host.configure = { c ->
                c.handlers["terminal.read"] = {
                    if (dropped.compareAndSet(false, true)) {
                        c.dropConnection()
                        awaitCancellation()
                    }
                    buildJsonObject { put("text", "screen") }
                }
            }
            val s = host.session(this)
            assertEquals(AppError.ConnectionLost, s.kill(t1).errorOrNull())
            s.start()
            s.awaitOnline()
            assertEquals("screen", s.readText(t1).getOrThrow())
            assertEquals(2, host.cores.sumOf { it.callsOf("terminal.read").size })
            s.stop()
        }

    @Test
    fun `run sends cwd and terminal identity and maps templates to argv`() =
        runTest {
            val host = hostWith("t1")
            host.configure = { c -> c.handlers["terminal.run"] = { FakeCore.terminal("t2") } }
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            val rec =
                s
                    .run(
                        RunRequest(template = "claude", cwd = "/work", labels = mapOf("flocket.group" to "a")),
                    ).getOrThrow()
            assertEquals("t2", rec.address.value)
            val p =
                host.core
                    .callsOf("terminal.run")
                    .single()
                    .params
            assertEquals(listOf("claude"), p["argv"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("/work", p["cwd"]!!.jsonPrimitive.content)
            val env = p["env"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            assertEquals(CoreSessionImpl.RUN_ENV, env)
            assertEquals("a", p["labels"]!!.jsonObject["flocket.group"]!!.jsonPrimitive.content)
            s.stop()
        }

    private fun execReply(
        home: String,
        code: Int? = 0,
    ) = buildJsonObject {
        put(
            "stdout",
            java.util.Base64
                .getEncoder()
                .encodeToString(home.toByteArray()),
        )
        put("stderr", "")
        put("truncated", false)
        put("timed_out", false)
        put("ms", 3)
        if (code != null) put("code", code)
    }

    private fun cwdsOf(core: FakeCore) = core.callsOf("terminal.run").map { it.params["cwd"]!!.jsonPrimitive.content }

    @Test
    fun `run expands tilde and tilde slash to the home folder and caches it`() =
        runTest {
            val host = hostWith("t1")
            host.configure = { c ->
                c.handlers["machine.exec"] = { execReply("/home/me/") }
                c.handlers["terminal.run"] = { FakeCore.terminal("t2") }
            }
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            s.run(RunRequest(argv = listOf("sh"), cwd = "~")).getOrThrow()
            s.run(RunRequest(argv = listOf("sh"), cwd = "~/src")).getOrThrow()
            s.run(RunRequest(argv = listOf("sh"), cwd = "/abs")).getOrThrow()
            assertEquals(listOf("/home/me/", "/home/me/src", "/abs"), cwdsOf(host.core))
            val exec = host.core.callsOf("machine.exec")
            assertEquals(1, exec.size)
            assertEquals(
                listOf("sh", "-c", "printf %s \"\$HOME\""),
                exec
                    .single()
                    .params["argv"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.content },
            )
            assertNull(exec.single().params["on"])
            s.stop()
        }

    @Test
    fun `absolute cwd makes no machine exec call`() =
        runTest {
            val host = hostWith("t1")
            host.configure = { c -> c.handlers["terminal.run"] = { FakeCore.terminal("t2") } }
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            s.run(RunRequest(argv = listOf("sh"), cwd = "/work")).getOrThrow()
            assertTrue(host.core.callsOf("machine.exec").isEmpty())
            s.stop()
        }

    @Test
    fun `remote machine home is resolved on that machine and cached separately`() =
        runTest {
            val host = hostWith("t1")
            host.configure = { c ->
                c.handlers["machine.exec"] = { p ->
                    execReply(if (p["on"] == null) "/home/local" else "/home/rohan")
                }
                c.handlers["terminal.run"] = { FakeCore.terminal("t2") }
            }
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            s.run(RunRequest(argv = listOf("sh"), cwd = "~/a", machine = "rohan")).getOrThrow()
            s.run(RunRequest(argv = listOf("sh"), cwd = "~/b", machine = "rohan")).getOrThrow()
            s.run(RunRequest(argv = listOf("sh"), cwd = "~/c")).getOrThrow()
            assertEquals(listOf("/home/rohan/a", "/home/rohan/b", "/home/local/c"), cwdsOf(host.core))
            val exec = host.core.callsOf("machine.exec")
            assertEquals(2, exec.size)
            assertEquals("rohan", exec[0].params["on"]!!.jsonPrimitive.content)
            s.stop()
        }

    @Test
    fun `run with unresolvable home is InvalidInput cwd and never calls terminal run`() =
        runTest {
            val host = hostWith("t1")
            host.configure = { c ->
                c.handlers["machine.exec"] = { execReply("", code = 1) }
                c.handlers["terminal.run"] = { FakeCore.terminal("t2") }
            }
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            val e = s.run(RunRequest(argv = listOf("sh"), cwd = "~")).errorOrNull()
            assertTrue(e is AppError.InvalidInput && e.field == "cwd")
            host.core.errors["machine.exec"] = FakeRpcError("spawn_failed")
            val e2 = s.run(RunRequest(argv = listOf("sh"), cwd = "~/x")).errorOrNull()
            assertTrue(e2 is AppError.InvalidInput && e2.field == "cwd")
            val e3 = s.run(RunRequest(argv = listOf("sh"), cwd = "~bob/x")).errorOrNull()
            assertTrue(e3 is AppError.InvalidInput && e3.field == "cwd")
            assertTrue(host.core.callsOf("terminal.run").isEmpty())
            s.stop()
        }

    @Test
    fun `missing optional methods are Unsupported`() =
        runTest {
            val host = hostWith("t1")
            host.coreConfig =
                FakeCoreConfig(methods = FakeCoreConfig.DEFAULT_METHODS - "templates.list" - "terminal.interrupt")
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            assertEquals(AppError.Unsupported("templates.list"), s.templates().errorOrNull())
            assertEquals(AppError.Unsupported("terminal.interrupt"), s.interrupt(t1).errorOrNull())
            assertTrue(host.core.callsOf("templates.list").isEmpty())
            s.stop()
        }

    @Test
    fun `host key problems need attention until retryNow`() =
        runTest {
            val host = hostWith("t1")
            host.failures += AppError.HostKeyUnknown("h", "ssh-ed25519", "SHA256:abc")
            val s = host.session(this)
            s.start()
            val na = s.awaitConnection { it is ConnectionState.NeedsAttention } as ConnectionState.NeedsAttention
            assertTrue(na.error is AppError.HostKeyUnknown)
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(1, host.calls.get())
            assertTrue(s.state.value.connection is ConnectionState.NeedsAttention)
            s.retryNow()
            s.awaitOnline()
            assertEquals(2, host.calls.get())
            s.stop()
        }

    @Test
    fun `backoff grows on repeated failures and retryNow skips the wait`() =
        runTest {
            val host = hostWith("t1")
            repeat(3) { host.failures += AppError.Unreachable("h") }
            val s = host.session(this)
            s.start()
            val delays = mutableListOf<Long>()
            repeat(3) { i ->
                val rc =
                    s.awaitConnection {
                        it is ConnectionState.Reconnecting && it.attempt == i + 1
                    } as ConnectionState.Reconnecting
                delays += rc.nextAttemptAtMs - testScheduler.currentTime
                if (i == 2) s.retryNow()
            }
            assertEquals(listOf(1_000L, 2_000L, 4_000L), delays)
            val t = testScheduler.currentTime
            s.awaitOnline()
            assertEquals(t, testScheduler.currentTime) // retryNow: no 4 s wait
            s.stop()
        }

    @Test
    fun `watchdog drops a silent link and reconnects, heartbeats keep it alive`() =
        runTest {
            val host = hostWith("t1")
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            advanceTimeBy(40_000)
            runCurrent()
            host.core.pushHeartbeat()
            runCurrent()
            advanceTimeBy(40_000)
            runCurrent()
            assertEquals(1, host.cores.size)
            assertTrue(s.state.value.connection is ConnectionState.Online)
            advanceTimeBy(5_001) // 45 s since the heartbeat: dead link
            runCurrent()
            assertTrue(s.state.value.connection is ConnectionState.Reconnecting)
            s.awaitOnline()
            assertEquals(2, host.cores.size)
            s.stop()
        }

    @Test
    fun `a slow old connect completing after a newer one is closed and ignored`() =
        runTest {
            val host = hostWith("t1")
            val gate = CompletableDeferred<Unit>()
            host.gates[1] = gate
            val s = host.session(this)
            s.start()
            runCurrent()
            assertEquals(ConnectionState.Connecting(1), s.state.value.connection)
            s.stop()
            s.start()
            s.awaitOnline()
            val live = host.transports.single()
            gate.complete(Unit) // the old attempt's transport arrives late
            runCurrent()
            assertEquals(2, host.transports.size)
            val late = host.transports.last()
            assertTrue("late transport closed", late.closed)
            assertFalse(live.closed)
            assertTrue(
                host.cores
                    .last()
                    .calls
                    .isEmpty(),
            )
            assertTrue(s.state.value.connection is ConnectionState.Online)
            s.stop()
        }

    @Test
    fun `rapid stop and start cycles leave exactly one connection`() =
        runTest {
            val host = hostWith("t1")
            val s = host.session(this)
            repeat(4) { i ->
                s.start()
                if (i % 2 == 0) runCurrent()
                s.stop()
            }
            s.start()
            s.start()
            s.awaitOnline()
            runCurrent()
            assertEquals(1, host.transports.count { !it.closed })
            s.stop()
            runCurrent()
            assertEquals(0, host.transports.count { !it.closed })
        }

    @Test
    fun `hosts are independent`() =
        runTest {
            val bad = FakeHost(backgroundScope).apply { repeat(20) { failures += AppError.Unreachable("bad") } }
            val good =
                hostWith("t1").apply {
                    configure =
                        { c -> c.handlers["terminal.read"] = { buildJsonObject { put("text", "ok") } } }
                }
            val sb = bad.session(this, id = "bad")
            val sg = good.session(this, id = "good")
            sb.start()
            sg.start()
            sg.awaitOnline()
            sb.awaitConnection { it is ConnectionState.Reconnecting }
            assertEquals("ok", sg.readText(t1).getOrThrow())
            assertTrue(
                sb.state.value.terminals
                    .isEmpty(),
            )
            sb.stop()
            assertTrue(sg.state.value.connection is ConnectionState.Online)
            sg.stop()
        }

    @Test
    fun `passive attach sends no RESIZE, FOCUS or FACTS until the user interacts`() =
        runTest {
            val host = hostWith("t1")
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            val stream = s.attachOk(display = DisplayFacts("#FFFFFF", "#000000", cellWidthPx = 9, cellHeightPx = 18))
            val p = host.core.attachParams()
            assertEquals(JsonPrimitive(true), p["resumed"])
            assertEquals(80, p["cols"]!!.jsonPrimitive.content.toInt())
            assertEquals("#ffffff", p["display"]!!.jsonObject["fg"]!!.jsonPrimitive.content)
            assertEquals(
                listOf(9, 18),
                p["display"]!!.jsonObject["cell"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() },
            )
            val ch = host.core.attachChannelOf("t1")!!
            stream.updateDisplay(DisplayFacts("#eeeeee", "#000000"))
            stream.focus(false)
            runCurrent()
            assertEquals(emptyList<String>(), host.core.controlKinds(ch))

            assertTrue(stream.write("x".toByteArray()) is AppResult.Success)
            runCurrent()
            assertEquals(listOf("FACTS"), host.core.controlKinds(ch))
            stream.resize(100, 30)
            stream.focus(true)
            runCurrent()
            assertEquals(listOf("FACTS", "RESIZE", "FOCUS"), host.core.controlKinds(ch))
            s.stop()
        }

    @Test
    fun `owner attach takes the size, observe never sends input or controls`() =
        runTest {
            val host = hostWith("t1")
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            val owner = s.attachOk(AttachMode.Owner)
            assertNull(host.core.attachParams()["resumed"])
            owner.resize(90, 30)
            runCurrent()
            assertEquals(listOf("RESIZE"), host.core.controlKinds(host.core.attachChannelOf("t1")!!))
            owner.detach()
            runCurrent()

            val obs = s.attachOk(AttachMode.Observe)
            assertEquals(JsonPrimitive(true), host.core.attachParams()["observe"])
            assertTrue(obs.write("x".toByteArray()).errorOrNull() is AppError.InvalidInput)
            obs.resize(100, 40)
            obs.focus(true)
            runCurrent()
            assertEquals(emptyList<String>(), host.core.controlKinds(host.core.attachChannelOf("t1")!!))
            s.stop()
        }

    @Test
    fun `size follows the attach reply and size events`() =
        runTest {
            val host = FakeHost(backgroundScope).apply { terminals += FakeCore.terminal("t1", cols = 120, rows = 40) }
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            val stream = s.attachOk()
            assertEquals(TerminalSize(120, 40), stream.size.value)
            host.core.pushEvent(
                "size",
                "t1",
                buildJsonObject {
                    put("cols", 100)
                    put("rows", 30)
                },
            )
            eventually { stream.size.value == TerminalSize(100, 30) }
            s.stop()
        }

    @Test
    fun `program exit ends the stream, other terminals' events do not`() =
        runTest {
            val host = hostWith("t1")
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            val stream = s.attachOk()
            val out = collectText(backgroundScope, stream.output)
            host.core.closeStream(
                host.core.attachChannelOf("t1")!!,
                buildJsonObject {
                    put("exit_code", 3)
                    put("finished_ms", 5)
                    put("reason", "exited")
                },
            )
            eventually { stream.end.value != null }
            assertEquals(StreamEnd.ProgramEnded("exited", 3, null), stream.end.value)
            assertFalse(stream.connected.value)
            assertTrue(out.contains("first-paint"))
            s.stop()
        }

    @Test
    fun `reattach of a terminal that vanished ends the stream as Unknown`() =
        runTest {
            val host = hostWith("t1")
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            val stream = s.attachOk()
            host.terminals.clear()
            host.core.dropConnection()
            s.awaitConnection { it is ConnectionState.Reconnecting }
            s.awaitOnline()
            eventually { stream.end.value != null }
            assertEquals(StreamEnd.Unknown, stream.end.value)
            s.stop()
        }

    @Test
    fun `stop parks streams with CLOSE, kills nothing, and start re-attaches them`() =
        runTest {
            val host = hostWith("t1")
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            val stream = s.attachOk()
            val out = collectText(backgroundScope, stream.output)
            eventually { out.contains("first-paint") }
            val core1 = host.core
            val ch1 = core1.attachChannelOf("t1")!!
            s.stop()
            assertEquals(ConnectionState.Idle, s.state.value.connection)
            assertTrue(core1.streamFrames.any { it.channel == ch1 && it.kind == FrameKind.CLOSE })
            assertTrue(core1.calls.none { it.method == "terminal.kill" || it.method == "terminal.rm" })
            assertFalse(stream.connected.value)
            assertNull(stream.end.value)
            assertEquals(AppError.ConnectionLost, stream.write("x".toByteArray()).errorOrNull())
            assertTrue(host.transports.all { it.closed })

            s.start()
            s.awaitOnline()
            eventually("reattach") { stream.connected.value }
            assertEquals(JsonPrimitive(true), host.core.attachParams()["resumed"])
            eventually("fresh paint") { out.toString().count("first-paint") == 2 }
            assertTrue(host.core.inputOf(host.core.attachChannelOf("t1")!!).isEmpty())
            s.stop()
        }

    @Test
    fun `transient reattach failure on a live link is retried with backoff, not left parked`() =
        runTest {
            val host = hostWith("t1")
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            val stream = s.attachOk()
            host.configure = { c -> c.errors["terminal.attach"] = FakeRpcError("internal") }
            host.core.dropConnection()
            s.awaitConnection { it is ConnectionState.Reconnecting }
            s.awaitOnline()
            val core2 = host.core
            eventually("failed reattach") { core2.callsOf("terminal.attach").isNotEmpty() }
            runCurrent()
            assertFalse(stream.connected.value)
            assertNull(stream.end.value)
            core2.errors.remove("terminal.attach")
            advanceTimeBy(1_100) // first retry after ~1 s (fixed jitter)
            eventually("retried reattach") { stream.connected.value }
            assertEquals(JsonPrimitive(true), core2.attachParams()["resumed"])
            assertTrue(core2.controlKinds(core2.attachChannelOf("t1")!!).isEmpty())
            assertTrue(s.state.value.connection is ConnectionState.Online)
            s.stop()
        }

    @Test
    fun `unavailable on reattach parks a running terminal but ends a waiting kept one`() =
        runTest {
            val waiting = JsonObject(FakeCore.terminal("t2") + ("pid" to JsonNull))
            val host = hostWith("t1").apply { terminals += waiting }
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            val running = s.attachOk()
            val kept = s.attach(TerminalAddress("t2"), AttachRequest(80, 24)).getOrThrow()
            host.configure = { c -> c.errors["terminal.attach"] = FakeRpcError("unavailable") }
            host.core.dropConnection()
            s.awaitConnection { it is ConnectionState.Reconnecting }
            s.awaitOnline()
            eventually("kept stream ended") { kept.end.value != null }
            assertTrue(kept.end.value is StreamEnd.ProgramLost)
            // e.g. a remote machine's line is down: the program still runs, keep the handle.
            assertNull(running.end.value)
            host.core.errors.remove("terminal.attach")
            advanceTimeBy(1_100)
            eventually("running stream re-attached") { running.connected.value }
            s.stop()
        }

    @Test
    fun `terminal input written before the link died is OutcomeUnknown, never retried`() =
        runTest {
            val host = hostWith("t1")
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            val stream = s.attachOk()
            val transport = host.transports.last()
            transport.failWritesAfterStart = true
            val r = stream.write("rm -rf build\r".toByteArray())
            assertEquals(AppError.OutcomeUnknown("terminal.input"), r.errorOrNull())
            s.stop()
        }
}
