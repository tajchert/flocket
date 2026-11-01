package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.model.DesktopLayout
import com.mtajchert.flocket.core.protocol.testing.FakeCore
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/** Federated machines' `herdr-app` documents (`doc.get {on}`) in [CoreSessionImpl] (SPEC §9, §9.1, §10). */
class CoreSessionMachineLayoutTest {
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

    private fun doc(vararg panes: String): JsonElement =
        Json.parseToJsonElement(
            """{"spaces":[{"id":"sp","name":"main","tabs":[{"id":"tb","root":{"children":[""" +
                panes.joinToString(",") { """{"content":{"terminal":"$it"}}""" } +
                "]}}]}]}",
        )

    private val DesktopLayout?.panes get() =
        this?.spaces?.flatMap { s ->
            s.tabs.flatMap { t -> t.panes.map { it.value } }
        }

    private fun CoreSession.panesOn(machine: String) = state.value.machineLayouts[machine].panes

    /**
     * A host whose cores list [machines] (name -> wire state) and serve [docs] per machine (key "" = local).
     * [gates] hold one machine's reads; [failures] make one machine's reads fail with that error kind.
     */
    private class Remote(
        val machines: MutableMap<String, String> = ConcurrentHashMap(),
        val docs: MutableMap<String, JsonElement> = ConcurrentHashMap(),
        val gates: MutableMap<String, CompletableDeferred<Unit>> = ConcurrentHashMap(),
        val failures: MutableMap<String, String> = ConcurrentHashMap(),
    )

    private fun TestScope.host(r: Remote) =
        FakeHost(backgroundScope).apply {
            terminals += FakeCore.terminal("t1")
            configure = { c ->
                c.handlers["machine.list"] = {
                    buildJsonObject {
                        put(
                            "machines",
                            buildJsonArray {
                                r.machines.toSortedMap().forEach { (name, state) ->
                                    add(
                                        buildJsonObject {
                                            put("name", name)
                                            put("state", state)
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
                c.handlers["doc.get"] = { p ->
                    val on = p["on"]?.jsonPrimitive?.contentOrNull ?: ""
                    r.gates[on]?.await()
                    r.failures[on]?.let { throw FakeRpcError(it) }
                    buildJsonObject {
                        put("value", r.docs[on] ?: JsonNull)
                        put("version", 1)
                    }
                }
            }
        }

    private fun FakeCore.docGetsOn(machine: String?) =
        callsOf("doc.get").filter { it.params["on"]?.jsonPrimitive?.contentOrNull == machine }

    private suspend fun FakeCore.docEvent(machine: String?) =
        pushEvent(
            "doc",
            data =
                buildJsonObject {
                    put("name", "herdr-app")
                    put("version", 2)
                },
            machine = machine,
        )

    @Test
    fun `reads each connected machine's herdr-app with on`() =
        runTest {
            val r = Remote()
            r.machines += mapOf("rohan" to "connected", "gondor" to "unreachable")
            r.docs += mapOf("" to doc("t1"), "rohan" to doc("t7"), "gondor" to doc("t9"))
            val host = host(r)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.panesOn("rohan") == listOf("t7") && s.state.value.desktopLayout != null }
            runCurrent()
            assertEquals(setOf("rohan"), s.state.value.machineLayouts.keys)
            assertEquals(1, host.core.docGetsOn("rohan").size)
            assertEquals(0, host.core.docGetsOn("gondor").size)
            assertEquals(1, host.core.docGetsOn(null).size)
            s.stop()
        }

    @Test
    fun `a remote doc event refetches that machine only`() =
        runTest {
            val r = Remote()
            r.machines += mapOf("rohan" to "connected", "mordor" to "connected")
            r.docs += mapOf("rohan" to doc("t7"), "mordor" to doc("t2"))
            val host = host(r)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.panesOn("rohan") == listOf("t7") && s.panesOn("mordor") == listOf("t2") }
            r.docs["rohan"] = doc("t7", "t8")
            host.core.docEvent("rohan")
            eventually { s.panesOn("rohan") == listOf("t7", "t8") }
            runCurrent()
            assertEquals(2, host.core.docGetsOn("rohan").size)
            assertEquals(1, host.core.docGetsOn("mordor").size)
            assertEquals(1, host.core.docGetsOn(null).size)
            s.stop()
        }

    @Test
    fun `reads coalesce per machine and a held machine blocks neither others nor the local read`() =
        runTest {
            val r = Remote()
            r.machines += mapOf("rohan" to "connected", "mordor" to "connected")
            r.docs += mapOf("" to doc("t1"), "rohan" to doc("t7"), "mordor" to doc("t2"))
            val gate = CompletableDeferred<Unit>()
            r.gates["rohan"] = gate
            val host = host(r)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.panesOn("mordor") == listOf("t2") && s.state.value.desktopLayout != null }
            repeat(3) { host.core.docEvent("rohan") }
            r.docs["mordor"] = doc("t3")
            host.core.docEvent("mordor")
            r.docs[""] = doc("t4")
            host.core.docEvent(null)
            eventually { s.panesOn("mordor") == listOf("t3") && s.state.value.desktopLayout.panes == listOf("t4") }
            assertEquals(1, host.core.docGetsOn("rohan").size) // still the first read, held
            assertNull(s.state.value.machineLayouts["rohan"])
            r.docs["rohan"] = doc("t8")
            gate.complete(Unit)
            eventually { s.panesOn("rohan") == listOf("t8") }
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, host.core.docGetsOn("rohan").size)
            s.stop()
        }

    @Test
    fun `a machine that leaves the list loses its layout and a renamed one is read fresh`() =
        runTest {
            val r = Remote()
            r.machines["rohan"] = "connected"
            r.docs += mapOf("rohan" to doc("t7"), "edoras" to doc("t5"))
            val host = host(r)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.panesOn("rohan") == listOf("t7") }
            r.machines.remove("rohan")
            r.machines["edoras"] = "connected" // machine.rename rohan -> edoras
            host.core.pushMissed()
            eventually { s.panesOn("edoras") == listOf("t5") }
            assertEquals(setOf("edoras"), s.state.value.machineLayouts.keys)
            s.stop()
        }

    @Test
    fun `a machine that is not connected keeps its last layout and is read again when it returns`() =
        runTest {
            val r = Remote()
            r.machines["rohan"] = "connected"
            r.docs["rohan"] = doc("t7")
            val host = host(r)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.panesOn("rohan") == listOf("t7") }
            r.machines["rohan"] = "unreachable"
            r.docs["rohan"] = doc("t8")
            host.core.pushMissed()
            eventually {
                s.state.value.machines
                    .single()
                    .rawState == "unreachable"
            }
            runCurrent()
            assertEquals(1, host.core.docGetsOn("rohan").size)
            assertEquals(listOf("t7"), s.panesOn("rohan"))
            r.machines["rohan"] = "connected" // on return a `missed` arrives (SPEC §10)
            host.core.pushMissed()
            eventually { s.panesOn("rohan") == listOf("t8") }
            s.stop()
        }

    @Test
    fun `failed remote reads keep the previous layout silently`() =
        runTest {
            val r = Remote()
            r.machines["rohan"] = "connected"
            r.docs["rohan"] = doc("t7")
            val host = host(r)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.panesOn("rohan") == listOf("t7") }
            r.docs["rohan"] = doc("t8")
            for ((i, kind) in listOf("machine_unknown", "unavailable", "bad_request", "timeout").withIndex()) {
                r.failures["rohan"] = kind
                host.core.docEvent("rohan")
                eventually { host.core.docGetsOn("rohan").size == i + 2 }
                runCurrent()
                assertEquals(listOf("t7"), s.panesOn("rohan"))
            }
            assertEquals(
                com.mtajchert.flocket.core.model.ConnectionState.Online::class,
                s.state.value.connection::class,
            )
            r.failures.clear()
            r.docs["rohan"] = JsonNull // deleted document: no entry
            host.core.docEvent("rohan")
            eventually { "rohan" !in s.state.value.machineLayouts }
            s.stop()
        }

    @Test
    fun `a reply for a machine removed meanwhile does not resurrect its entry`() =
        runTest {
            val r = Remote()
            r.machines["rohan"] = "connected"
            r.docs["rohan"] = doc("t7")
            val host = host(r)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.panesOn("rohan") == listOf("t7") }
            val gate = CompletableDeferred<Unit>()
            r.gates["rohan"] = gate
            r.docs["rohan"] = doc("t8")
            host.core.docEvent("rohan")
            eventually { host.core.docGetsOn("rohan").size == 2 }
            r.machines.remove("rohan") // machine.rm
            host.core.pushMissed()
            eventually {
                s.state.value.machines
                    .isEmpty() &&
                    s.state.value.machineLayouts
                        .isEmpty()
            }
            gate.complete(Unit)
            advanceTimeBy(1_000)
            runCurrent()
            assertFalse("rohan" in s.state.value.machineLayouts)
            s.stop()
        }

    @Test
    fun `a remote reply from an old connection is ignored`() =
        runTest {
            val r = Remote()
            r.machines["rohan"] = "connected"
            val host = host(r)
            val oldGate = CompletableDeferred<Unit>()
            val base = host.configure
            var n = 0
            host.configure = { c ->
                base(c)
                n++
                val old = n == 1
                val serve = c.handlers["doc.get"]!!
                c.handlers["doc.get"] = { p ->
                    if (old && p.containsKey("on")) {
                        oldGate.await()
                        buildJsonObject {
                            put("value", doc("old"))
                            put("version", 1)
                        }
                    } else {
                        serve(p)
                    }
                }
            }
            r.docs["rohan"] = doc("new")
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            val first = host.core
            eventually { first.docGetsOn("rohan").size == 1 }
            first.dropConnection()
            eventually("second connection") { host.cores.size == 2 && s.panesOn("rohan") == listOf("new") }
            oldGate.complete(Unit)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(listOf("new"), s.panesOn("rohan"))
            s.stop()
        }

    // ---- machine.rename / machine.rm send no `missed` (SPEC §10): re-list from terminal events ----------

    private fun CoreSession.machineNames() = state.value.machines.map { it.name }

    private fun FakeCore.machineLists() = callsOf("machine.list").size

    @Test
    fun `a rename without missed re-lists machines and reads the new name`() =
        runTest {
            val r = Remote()
            r.machines["rohan"] = "connected"
            r.docs += mapOf("rohan" to doc("t7"), "edoras" to doc("t5"))
            val host = host(r)
            host.terminals += FakeCore.terminal("rohan/t1")
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.panesOn("rohan") == listOf("t7") }
            assertEquals(1, host.core.machineLists())
            r.machines.remove("rohan")
            r.machines["edoras"] = "connected"
            // What hc 0.1.16 sends for `machine.rename rohan edoras`: removed + created, no missed.
            host.core.pushEvent("removed", "rohan/t1")
            host.core.pushEvent("created", "edoras/t1", FakeCore.terminal("edoras/t1"))
            eventually { s.machineNames() == listOf("edoras") && s.panesOn("edoras") == listOf("t5") }
            assertEquals(setOf("edoras"), s.state.value.machineLayouts.keys)
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(host.core.machineLists() in 2..3) // coalesced: never one per event
            s.stop()
        }

    @Test
    fun `a machine rm without missed drops the machine and its layout`() =
        runTest {
            val r = Remote()
            r.machines["rohan"] = "connected"
            r.docs["rohan"] = doc("t7")
            val host = host(r)
            host.terminals += FakeCore.terminal("rohan/t1")
            host.terminals += FakeCore.terminal("rohan/t2")
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.panesOn("rohan") == listOf("t7") }
            r.machines.remove("rohan")
            host.core.pushEvent("removed", "rohan/t1") // rohan still has t2: no re-list yet
            runCurrent()
            assertEquals(1, host.core.machineLists())
            host.core.pushEvent("removed", "rohan/t2")
            eventually {
                s.machineNames().isEmpty() &&
                    s.state.value.machineLayouts
                        .isEmpty()
            }
            assertEquals(2, host.core.machineLists())
            s.stop()
        }

    @Test
    fun `terminals of listed machines and local ones never re-list`() =
        runTest {
            val r = Remote()
            r.machines["rohan"] = "connected"
            val host = host(r)
            host.terminals += FakeCore.terminal("rohan/t1")
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { host.core.docGetsOn("rohan").size == 1 }
            host.core.pushEvent("created", "rohan/t2", FakeCore.terminal("rohan/t2"))
            host.core.pushEvent("created", "t2", FakeCore.terminal("t2"))
            host.core.pushEvent("removed", "t1")
            host.core.pushEvent("removed", "rohan/t2")
            eventually {
                s.state.value.terminals
                    .map { it.address.value } == listOf("rohan/t1", "t2")
            }
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, host.core.machineLists())
            s.stop()
        }

    @Test
    fun `a doc event from an unlisted machine re-lists and then reads it`() =
        runTest {
            val r = Remote()
            r.docs["edoras"] = doc("t5")
            val host = host(r)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { host.core.docGetsOn(null).size == 1 }
            r.machines["edoras"] = "connected"
            host.core.docEvent("edoras")
            eventually { s.panesOn("edoras") == listOf("t5") }
            assertEquals(2, host.core.machineLists())
            assertEquals(1, host.core.docGetsOn("edoras").size)
            s.stop()
        }

    @Test
    fun `re-lists coalesce per link`() =
        runTest {
            val r = Remote()
            val host = host(r)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { host.core.docGetsOn(null).size == 1 }
            val gate = CompletableDeferred<Unit>()
            host.core.gates["machine.list"] = gate
            r.machines["rohan"] = "connected"
            r.machines["edoras"] = "connected"
            host.core.pushEvent("created", "rohan/t1", FakeCore.terminal("rohan/t1"))
            eventually { host.core.machineLists() == 2 }
            host.core.pushEvent("created", "edoras/t1", FakeCore.terminal("edoras/t1"))
            host.core.pushMissed()
            host.core.docEvent("rohan")
            runCurrent()
            assertEquals(2, host.core.machineLists()) // the second read is still held
            gate.complete(Unit)
            eventually { s.machineNames() == listOf("edoras", "rohan") }
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(3, host.core.machineLists()) // exactly one more for everything meanwhile
            s.stop()
        }
}
