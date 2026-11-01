package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.DesktopLayout
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.protocol.testing.FakeCore
import com.mtajchert.flocket.core.protocol.testing.FakeCoreConfig
import com.mtajchert.flocket.core.protocol.testing.FakeRpcError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** `herdr-app` document tracking in [CoreSessionImpl] (SPEC §9, §9.1). */
class CoreSessionLayoutTest {
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

    /** A one-space, one-tab document whose tab holds [panes]. */
    private fun doc(vararg panes: String): JsonElement =
        Json.parseToJsonElement(
            """{"spaces":[{"id":"sp","name":"main","tabs":[{"id":"tb","root":{"children":[""" +
                panes.joinToString(",") { """{"content":{"terminal":"$it"}}""" } +
                "]}}]}]}",
        )

    private fun reply(
        value: JsonElement,
        version: Long,
    ) = buildJsonObject {
        put("value", value)
        put("version", version)
    }

    private val DesktopLayout?.panes get() =
        this?.spaces?.flatMap { s ->
            s.tabs.flatMap { t -> t.panes.map { it.value } }
        }

    private fun CoreSession.panes() = state.value.desktopLayout.panes

    /** A host whose every core serves [current] for `herdr-app`. */
    private fun TestScope.hostServing(current: AtomicReference<JsonElement>) =
        FakeHost(backgroundScope).apply {
            terminals += FakeCore.terminal("t1")
            configure = { c -> c.handlers["doc.get"] = { reply(current.get(), 1) } }
        }

    private fun FakeCore.docGets() = callsOf("doc.get")

    @Test
    fun `reads the local herdr-app document after subscribing`() =
        runTest {
            val current = AtomicReference(doc("t1", "t2"))
            val host = hostServing(current)
            val s = host.session(this)
            assertNull(s.state.value.desktopLayout)
            s.start()
            s.awaitOnline()
            eventually { s.panes() == listOf("t1", "t2") }
            val call = host.core.docGets().single()
            assertEquals("herdr-app", call.params["name"]!!.jsonPrimitive.content)
            assertFalse(call.params.containsKey("on"))
            // Subscribe first, then read documents (SPEC §8.1).
            val methods = host.core.calls.map { it.method }
            assertTrue(methods.indexOf("events.subscribe") < methods.indexOf("doc.get"))
            s.stop()
        }

    @Test
    fun `a herdr-app doc event refetches, other documents and machines do not`() =
        runTest {
            val current = AtomicReference(doc("t1"))
            val host = hostServing(current)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.panes() == listOf("t1") }
            host.core.pushEvent(
                "doc",
                data =
                    buildJsonObject {
                        put("name", "flocket")
                        put("version", 3)
                    },
            )
            host.core.pushEvent(
                "doc",
                data =
                    buildJsonObject {
                        put("name", "herdr-app")
                        put("version", 9)
                    },
                machine = "rohan",
            )
            current.set(doc("t1", "t3"))
            host.core.pushEvent(
                "doc",
                data =
                    buildJsonObject {
                        put("name", "herdr-app")
                        put("version", 2)
                    },
            )
            eventually { s.panes() == listOf("t1", "t3") }
            runCurrent()
            assertEquals(2, host.core.docGets().size)
            assertFalse(host.core.docGets().any { it.params.containsKey("on") })
            s.stop()
        }

    @Test
    fun `missed refetches the document`() =
        runTest {
            val current = AtomicReference(doc("t1"))
            val host = hostServing(current)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.panes() == listOf("t1") }
            current.set(doc("t5"))
            host.core.pushMissed()
            eventually { s.panes() == listOf("t5") }
            assertEquals(2, host.core.docGets().size)
            s.stop()
        }

    @Test
    fun `a resubscribe after a desync refetches the document`() =
        runTest {
            val current = AtomicReference(doc("t1"))
            val host = hostServing(current)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.panes() == listOf("t1") }
            current.set(doc("t2"))
            val gap =
                """{"jsonrpc":"2.0","method":"event",""" +
                    """"params":{"seq":99,"type":"title","terminal":"t1","data":{"title":"x"}}}""" + "\n"
            host.core.streamOutput(host.core.eventsChannel!!, gap.toByteArray())
            eventually { s.panes() == listOf("t2") }
            assertEquals(2, host.core.callsOf("events.subscribe").size)
            s.stop()
        }

    @Test
    fun `triggers during a read coalesce into one more read`() =
        runTest {
            val current = AtomicReference(doc("t1"))
            val host = hostServing(current)
            val gate = CompletableDeferred<Unit>()
            val configure = host.configure
            host.configure = { c ->
                configure(c)
                c.gates["doc.get"] = gate
            }
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { host.core.docGets().size == 1 }
            repeat(3) { v ->
                host.core.pushEvent(
                    "doc",
                    data =
                        buildJsonObject {
                            put("name", "herdr-app")
                            put("version", v + 2)
                        },
                )
            }
            host.core.pushMissed()
            current.set(doc("t9"))
            runCurrent()
            assertEquals(1, host.core.docGets().size) // still the first read, held by the gate
            gate.complete(Unit)
            eventually { s.panes() == listOf("t9") }
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, host.core.docGets().size)
            // Idle again: the next event reads once more.
            host.core.pushEvent(
                "doc",
                data =
                    buildJsonObject {
                        put("name", "herdr-app")
                        put("version", 9)
                    },
            )
            eventually { host.core.docGets().size == 3 }
            s.stop()
        }

    @Test
    fun `a failed read keeps the previous layout, a deleted document clears it`() =
        runTest {
            val current = AtomicReference(doc("t1"))
            val host = hostServing(current)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.panes() == listOf("t1") }
            host.core.errors["doc.get"] = FakeRpcError("internal", "could not read")
            host.core.pushEvent(
                "doc",
                data =
                    buildJsonObject {
                        put("name", "herdr-app")
                        put("version", 2)
                    },
            )
            eventually { host.core.docGets().size == 2 }
            runCurrent()
            assertEquals(listOf("t1"), s.panes())
            assertEquals(ConnectionState.Online::class, s.state.value.connection::class)
            host.core.errors.remove("doc.get")
            current.set(JsonNull)
            host.core.pushEvent(
                "doc",
                data =
                    buildJsonObject {
                        put("name", "herdr-app")
                        put("version", 3)
                    },
            )
            eventually { s.state.value.desktopLayout == null }
            s.stop()
        }

    @Test
    fun `a reply from an old connection never overwrites the new one's layout`() =
        runTest {
            val oldGate = CompletableDeferred<Unit>()
            val host = FakeHost(backgroundScope)
            host.terminals += FakeCore.terminal("t1")
            var n = 0
            host.configure = { c ->
                n++
                if (n == 1) {
                    c.handlers["doc.get"] = { reply(doc("old"), 1) }
                    c.gates["doc.get"] = oldGate
                } else {
                    c.handlers["doc.get"] = { reply(doc("new"), 1) }
                }
            }
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            val first = host.core
            eventually { first.docGets().size == 1 }
            first.dropConnection()
            eventually("second connection") { host.cores.size == 2 && s.panes() == listOf("new") }
            oldGate.complete(Unit) // the old core answers now; its link is gone
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(listOf("new"), s.panes())
            s.stop()
        }

    @Test
    fun `the layout survives a disconnect and stop`() =
        runTest {
            val current = AtomicReference(doc("t1"))
            val host = hostServing(current)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.panes() == listOf("t1") }
            host.failures += AppError.ConnectionLost
            host.core.dropConnection()
            s.awaitConnection { it is ConnectionState.Reconnecting }
            assertEquals(listOf("t1"), s.panes())
            s.stop()
            assertEquals(ConnectionState.Idle, s.state.value.connection)
            assertEquals(listOf("t1"), s.panes())
            assertEquals(
                TerminalAddress("t1"),
                s.state.value.desktopLayout!!
                    .spaces[0]
                    .tabs[0]
                    .panes[0],
            )
        }
}
