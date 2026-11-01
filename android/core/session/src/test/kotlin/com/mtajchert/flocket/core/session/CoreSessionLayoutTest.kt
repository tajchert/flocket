package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.common.result.errorOrNull
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.DesktopLayout
import com.mtajchert.flocket.core.model.LayoutRef
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.protocol.testing.FakeCore
import com.mtajchert.flocket.core.protocol.testing.FakeCoreConfig
import com.mtajchert.flocket.core.protocol.testing.FakeRpcError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
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

    private fun FakeCore.docGets() =
        callsOf("doc.get").filter {
            it.params["name"]?.jsonPrimitive?.contentOrNull ==
                "herdr-app"
        }

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

    // ---- rename (SPEC §9.1, ADR-0008) -----------------------------------------------------------

    /** A two-space document: `sp` with tabs `tb` and `tb2`, then `sp2`; [tabs] overrides `sp`'s tab ids. */
    private fun layoutDoc(vararg tabs: String = arrayOf("tb", "tb2")): JsonElement =
        Json.parseToJsonElement(
            """{"spaces":[{"id":"sp","name":"main","look":{"color":"#4f9dff"},"tabs":[""" +
                tabs.joinToString(",") { """{"id":"$it","name":"","root":{"content":{"terminal":"t1"}}}""" } +
                """]},{"id":"sp2","name":"infra","tabs":[]}],"future":1}""",
        )

    private fun nameOf(
        value: JsonElement,
        space: Int,
        tab: Int? = null,
    ): String {
        val s = value.jsonObject["spaces"]!!.jsonArray[space].jsonObject
        val o = if (tab == null) s else s["tabs"]!!.jsonArray[tab].jsonObject
        return o["name"]!!.jsonPrimitive.content
    }

    /**
     * A host whose cores serve `herdr-app` from [doc] at [version] and accept a `doc.set` only at the current
     * version (then bump it), like the real core; [onSet] runs first and may throw a [FakeRpcError].
     */
    private fun TestScope.hostWithLayout(
        doc: AtomicReference<JsonElement>,
        version: AtomicInteger = AtomicInteger(5),
        onSet: suspend (FakeCore, JsonObject) -> Unit = { _, _ -> },
    ) = FakeHost(backgroundScope).apply {
        terminals += FakeCore.terminal("t1")
        configure = { c ->
            c.handlers["doc.get"] = { reply(doc.get(), version.get().toLong()) }
            c.handlers["doc.set"] = { p ->
                onSet(c, p)
                val v = p["version"]!!.jsonPrimitive.long
                if (v != version.get().toLong()) {
                    throw FakeRpcError("conflict", "document \"herdr-app\" is at version ${version.get()}, not $v")
                }
                doc.set(p["value"]!!)
                buildJsonObject { put("version", version.incrementAndGet()) }
            }
        }
    }

    private fun FakeCore.docSets() = callsOf("doc.set")

    /** Online, with the session's own first `herdr-app` read done, so tests count only the rename's calls. */
    private suspend fun TestScope.startWithLayout(s: CoreSession) {
        s.start()
        s.awaitOnline()
        eventually("first layout read") { s.state.value.desktopLayout != null }
        runCurrent()
    }

    @Test
    fun `rename sends doc set with the read version and the edited document`() =
        runTest {
            val doc = AtomicReference(layoutDoc())
            val host = hostWithLayout(doc)
            val s = host.session(this)
            startWithLayout(s)
            val before = doc.get()
            assertEquals(AppResult.Success(Unit), s.renameInLayout(LayoutRef(null, "sp", "tb2"), " tests "))
            val set = host.core.docSets().single()
            assertEquals("herdr-app", set.params["name"]!!.jsonPrimitive.content)
            assertEquals(5L, set.params["version"]!!.jsonPrimitive.long)
            assertFalse(set.params.containsKey("on"))
            val value = set.params["value"]!!
            assertEquals("tests", nameOf(value, 0, 1))
            // Only that name changed: compare as JSON with the name put back.
            val restored =
                HerdrAppLayoutEdit.rename(value, "sp", "tb2", "") as HerdrAppLayoutEdit.Result.Edited
            assertEquals(before, restored.value)
            // A space rename edits the space's own name.
            assertEquals(AppResult.Success(Unit), s.renameInLayout(LayoutRef(null, "sp2"), "ops"))
            val second = host.core.docSets()[1]
            assertEquals(6L, second.params["version"]!!.jsonPrimitive.long)
            assertEquals("ops", nameOf(second.params["value"]!!, 1))
            assertEquals("tests", nameOf(second.params["value"]!!, 0, 1))
            s.stop()
        }

    @Test
    fun `rename of a federated space reads and writes with on`() =
        runTest {
            val doc = AtomicReference(layoutDoc())
            val host = hostWithLayout(doc)
            val s = host.session(this)
            startWithLayout(s)
            val localReads = host.core.docGets().size
            assertEquals(AppResult.Success(Unit), s.renameInLayout(LayoutRef("rohan", "sp"), "remote"))
            val get =
                host.core
                    .docGets()
                    .drop(localReads)
                    .single()
            assertEquals("rohan", get.params["on"]!!.jsonPrimitive.content)
            val set = host.core.docSets().single()
            assertEquals("rohan", set.params["on"]!!.jsonPrimitive.content)
            assertEquals("remote", nameOf(set.params["value"]!!, 0))
            s.stop()
        }

    @Test
    fun `conflict re-reads and re-applies, at most 3 attempts`() =
        runTest {
            val doc = AtomicReference(layoutDoc())
            val version = AtomicInteger(5)
            val conflicts = AtomicInteger(2)
            // The desktop writes in between: renames tab `tb` and reorders the tabs; we re-apply by id.
            val host =
                hostWithLayout(doc, version) { _, _ ->
                    if (conflicts.getAndDecrement() > 0) {
                        doc.set(
                            HerdrAppLayoutEdit
                                .rename(layoutDoc("tb2", "tb"), "sp", "tb", "desk${version.get()}")
                                .let { (it as HerdrAppLayoutEdit.Result.Edited).value },
                        )
                        version.incrementAndGet()
                    }
                }
            val s = host.session(this)
            startWithLayout(s)
            val reads = host.core.docGets().size
            assertEquals(AppResult.Success(Unit), s.renameInLayout(LayoutRef(null, "sp", "tb2"), "mine"))
            assertEquals(3, host.core.docGets().size - reads)
            val sets = host.core.docSets()
            assertEquals(3, sets.size)
            assertEquals(listOf(5L, 6L, 7L), sets.map { it.params["version"]!!.jsonPrimitive.long })
            // Applied to tab tb2 (now first), keeping the desktop's own rename of tb (now second).
            assertEquals("mine", nameOf(doc.get(), 0, 0))
            assertEquals("desk6", nameOf(doc.get(), 0, 1))
            s.stop()
        }

    @Test
    fun `three conflicts in a row fail with conflict`() =
        runTest {
            val doc = AtomicReference(layoutDoc())
            val host =
                hostWithLayout(
                    doc,
                ) { _, _ -> throw FakeRpcError("conflict", "document \"herdr-app\" is at version 9, not 5") }
            val s = host.session(this)
            startWithLayout(s)
            val reads = host.core.docGets().size
            val error = s.renameInLayout(LayoutRef(null, "sp", "tb"), "x").errorOrNull()
            assertEquals(AppError.Rpc.CONFLICT, (error as AppError.Rpc).kind)
            assertEquals(3, host.core.docGets().size - reads)
            assertEquals(3, host.core.docSets().size)
            s.stop()
        }

    @Test
    fun `conflict then tab removed is gone`() =
        runTest {
            val doc = AtomicReference(layoutDoc())
            val version = AtomicInteger(5)
            val host =
                hostWithLayout(doc, version) { _, _ ->
                    // The desktop closes tab tb2 in the same moment.
                    doc.set(layoutDoc("tb"))
                    version.incrementAndGet()
                }
            val s = host.session(this)
            startWithLayout(s)
            val reads = host.core.docGets().size
            val error = s.renameInLayout(LayoutRef(null, "sp", "tb2"), "x").errorOrNull()
            assertEquals(AppError.Rpc.NOT_FOUND, (error as AppError.Rpc).kind)
            assertEquals(2, host.core.docGets().size - reads)
            assertEquals(1, host.core.docSets().size)
            assertEquals(layoutDoc("tb"), doc.get())
            s.stop()
        }

    @Test
    fun `a missing space or a document that is not a layout is not found, without a write`() =
        runTest {
            val doc = AtomicReference(layoutDoc())
            val host = hostWithLayout(doc)
            val s = host.session(this)
            startWithLayout(s)
            assertEquals(
                AppError.Rpc.NOT_FOUND,
                (s.renameInLayout(LayoutRef(null, "gone"), "x").errorOrNull() as AppError.Rpc).kind,
            )
            doc.set(JsonNull)
            assertEquals(
                AppError.Rpc.NOT_FOUND,
                (s.renameInLayout(LayoutRef(null, "sp"), "x").errorOrNull() as AppError.Rpc).kind,
            )
            assertTrue(host.core.docSets().isEmpty())
            s.stop()
        }

    @Test
    fun `link dropped after doc set is OutcomeUnknown and never resent`() =
        runTest {
            val doc = AtomicReference(layoutDoc())
            val dropped = AtomicInteger(0)
            val host =
                hostWithLayout(doc) { c, _ ->
                    if (dropped.getAndIncrement() == 0) {
                        c.dropConnection()
                        awaitCancellation()
                    }
                }
            val s = host.session(this)
            startWithLayout(s)
            assertEquals(AppError.OutcomeUnknown("doc.set"), s.renameInLayout(LayoutRef(null, "sp"), "x").errorOrNull())
            s.awaitConnection { it is ConnectionState.Reconnecting }
            s.awaitOnline()
            runCurrent()
            assertEquals(2, host.cores.size)
            assertEquals(1, host.cores.sumOf { it.docSets().size })
            // The new link re-reads the document (SPEC §9: after subscribing).
            eventually { host.cores[1].docGets().isNotEmpty() }
            s.stop()
        }

    @Test
    fun `rename while disconnected fails fast without a call`() =
        runTest {
            val doc = AtomicReference(layoutDoc())
            val host = hostWithLayout(doc)
            val s = host.session(this)
            assertEquals(AppError.ConnectionLost, s.renameInLayout(LayoutRef(null, "sp"), "x").errorOrNull())
            assertTrue(host.cores.isEmpty())
        }

    @Test
    fun `remote unavailable is passed through without a write`() =
        runTest {
            val doc = AtomicReference(layoutDoc())
            val host = hostWithLayout(doc)
            val configure = host.configure
            host.configure = { c ->
                configure(c)
                val local = c.handlers["doc.get"]!!
                c.handlers["doc.get"] = { p ->
                    if (p["on"]?.jsonPrimitive?.content ==
                        "rohan"
                    ) {
                        throw FakeRpcError("unavailable", "rohan is not connected")
                    }
                    local(p)
                }
            }
            val s = host.session(this)
            startWithLayout(s)
            val error = s.renameInLayout(LayoutRef("rohan", "sp", "tb"), "x").errorOrNull()
            assertEquals(AppError.Rpc.UNAVAILABLE, (error as AppError.Rpc).kind)
            assertTrue(host.core.docSets().isEmpty())
            s.stop()
        }
}
