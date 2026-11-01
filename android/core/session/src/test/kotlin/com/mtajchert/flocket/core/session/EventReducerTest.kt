package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.CoreEndpoint
import com.mtajchert.flocket.core.model.ForegroundProcess
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalRecord
import com.mtajchert.flocket.core.protocol.CoreNotification
import com.mtajchert.flocket.core.protocol.dto.TerminalDto
import com.mtajchert.flocket.core.protocol.testing.FakeCore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventReducerTest {
    private val endpoint = CoreEndpoint(HostId("h1"))
    private val reducer = EventReducer(endpoint)

    private fun dto(
        address: String,
        state: String = "live",
    ) = TerminalDto.parse(FakeCore.terminal(address, state = state))

    private fun base(seq: Long = 10) =
        reducer.snapshot(emptyList(), listOf(dto("t1"), dto("t1b"), dto("rohan/t7")), seq)

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun event(
        seq: Long,
        type: String,
        terminal: String? = "t1",
        data: String = "{}",
    ) = CoreNotification.Event(seq, type, terminal, null, obj(data))

    private fun ReducerState.t(address: String): TerminalRecord? =
        terminals.firstOrNull {
            it.address ==
                TerminalAddress(address)
        }

    private fun reduce(
        n: CoreNotification,
        state: ReducerState = base(),
        now: Long = 5_000,
    ) = reducer.reduce(state, n, now)

    @Test
    fun `snapshot keeps core order local first then remote`() {
        assertEquals(listOf("t1", "t1b", "rohan/t7"), base().terminals.map { it.address.value })
        assertEquals(10, base().lastSeq)
        assertEquals("rohan", base().t("rohan/t7")!!.address.machine)
    }

    @Test
    fun `created appends new terminals and upserts existing ones in place`() {
        val created = reduce(event(11, "created", "t2", FakeCore.terminal("t2").toString()))
        assertEquals(listOf("t1", "t1b", "rohan/t7", "t2"), created.state.terminals.map { it.address.value })
        assertEquals(11, created.state.lastSeq)

        val upsert = reduce(event(11, "created", "t1b", FakeCore.terminal("t1b", name = "renamed").toString()))
        assertEquals(listOf("t1", "t1b", "rohan/t7"), upsert.state.terminals.map { it.address.value })
        assertEquals("renamed", upsert.state.t("t1b")!!.name)
    }

    @Test
    fun `remote created and removed keep the full machine prefix`() {
        val created = reduce(event(11, "created", "rohan/t8", FakeCore.terminal("rohan/t8").toString()))
        assertEquals(
            "rohan/t8",
            created.state.terminals
                .last()
                .address.value,
        )
        val removed = reduce(event(12, "removed", "rohan/t7"), created.state)
        assertNull(removed.state.t("rohan/t7"))
        assertTrue(removed.state.t("t7") == null && removed.state.t("rohan/t8") != null)
    }

    @Test
    fun `restarted replaces the record with the fresh shell`() {
        val lost =
            reduce(
                event(
                    11,
                    "lost",
                    data = """{"reason":"holder_lost","exit_code":null,"signal":null,"finished_ms":null}""",
                ),
            )
        assertEquals(Liveness.Waiting("holder_lost"), lost.state.t("t1")!!.liveness)
        val fresh = FakeCore.terminal("t1", name = "fresh").toString()
        val r = reduce(event(12, "restarted", data = fresh), lost.state)
        assertEquals(Liveness.Running, r.state.t("t1")!!.liveness)
        assertEquals("fresh", r.state.t("t1")!!.name)
    }

    @Test
    fun `lost keeps the record live, clears pid and foreground`() {
        val fg = reduce(event(11, "foreground", data = """{"pid":7,"name":"vim","argv":["vim"],"cwd":"/w"}"""))
        assertEquals(ForegroundProcess(7, "vim", listOf("vim"), "/w"), fg.state.t("t1")!!.foreground)
        val lost = reduce(event(12, "lost", data = """{"reason":"signaled"}"""), fg.state)
        assertEquals(Liveness.Waiting("signaled"), lost.state.t("t1")!!.liveness)
        assertNull(lost.state.t("t1")!!.foreground)
    }

    @Test
    fun `finished sets finished liveness and drops foreground`() {
        val r =
            reduce(event(11, "finished", data = """{"reason":"exited","exit_code":3,"signal":null,"finished_ms":99}"""))
        assertEquals(Liveness.Finished("exited", 3, null, 99), r.state.t("t1")!!.liveness)
    }

    @Test
    fun `keep, turn, title, labels and size update their fields`() {
        var s = base()
        s = reduce(event(11, "keep", data = """{"keep":true}"""), s).state
        s = reduce(event(12, "turn", data = """{"turn":4,"state":"done"}"""), s).state
        s = reduce(event(13, "title", data = """{"title":"vim x"}"""), s).state
        s = reduce(event(14, "labels", data = """{"labels":{"where":"repo"}}"""), s).state
        s = reduce(event(15, "size", data = """{"cols":100,"rows":30}"""), s).state
        val t = s.t("t1")!!
        assertEquals(true, t.keep)
        assertEquals(4L, t.turn)
        assertEquals("vim x", t.title)
        assertEquals("repo", t.where)
        assertEquals(100 to 30, t.cols to t.rows)
        assertEquals(15, s.lastSeq)
    }

    @Test
    fun `labels with record adds a terminal that newly matches`() {
        val rec = FakeCore.terminal("t9").toString()
        val r = reduce(event(11, "labels", "t9", """{"labels":{"a":"b"},"record":$rec}"""))
        assertEquals(mapOf("a" to "b"), r.state.t("t9")!!.labels)
    }

    @Test
    fun `foreground null clears it`() {
        val fg = reduce(event(11, "foreground", data = """{"pid":7,"name":"vim","argv":["vim"],"cwd":"/w"}"""))
        // The protocol layer turns JSON null data into an empty object.
        val cleared = reduce(event(12, "foreground"), fg.state)
        assertNull(cleared.state.t("t1")!!.foreground)
    }

    @Test
    fun `status null is Unknown and the word change is stamped locally`() {
        val working =
            reduce(
                event(
                    11,
                    "status",
                    data = """{"status":{"state":"working","app":"claude"},"records":{},"reported":true}""",
                ),
                now = 1_000,
            )
        val t = working.state.t("t1")!!
        assertEquals(AgentState.Working, (t.status as AgentStatus.Reported).state)
        assertEquals(1_000L, t.statusChangedAtMs)
        assertEquals("claude", t.agentLabel)

        val sameWord =
            reduce(
                event(12, "status", data = """{"status":{"state":"working","progress":50}}"""),
                working.state,
                now = 2_000,
            )
        assertEquals(1_000L, sameWord.state.t("t1")!!.statusChangedAtMs)

        val cleared =
            reduce(
                event(13, "status", data = """{"status":null,"records":{},"reported":true}"""),
                sameWord.state,
                now = 3_000,
            )
        assertEquals(AgentStatus.Unknown, cleared.state.t("t1")!!.status)
        assertEquals(3_000L, cleared.state.t("t1")!!.statusChangedAtMs)
    }

    @Test
    fun `unknown status word is Other, not idle`() {
        val r = reduce(event(11, "status", data = """{"status":{"state":"pondering"}}"""))
        val st = r.state.t("t1")!!.status as AgentStatus.Reported
        assertEquals(AgentState.Other, st.state)
        assertEquals("pondering", st.rawState)
    }

    @Test
    fun `duplicate and old seqs are dropped`() {
        val s = base()
        for (seq in listOf(9L, 10L)) {
            val r = reduce(event(seq, "title", data = """{"title":"x"}"""), s)
            assertEquals(s, r.state)
            assertNull(r.effect)
        }
    }

    @Test
    fun `gap without missed asks for a resync and does not apply`() {
        val s = base()
        val r = reduce(event(12, "title", data = """{"title":"x"}"""), s)
        assertTrue(r.effect is ReduceEffect.Resync)
        assertEquals(s, r.state)
    }

    @Test
    fun `heartbeat ahead of last seq asks for a resync, equal is fine`() {
        assertNull(reduce(CoreNotification.Heartbeat(10)).effect)
        assertTrue(reduce(CoreNotification.Heartbeat(11)).effect is ReduceEffect.Resync)
    }

    @Test
    fun `malformed event data asks for a resync`() {
        val r = reduce(event(11, "created", "t5", """{"address":5}"""))
        assertTrue(r.effect is ReduceEffect.Resync)
        assertEquals(10, r.state.lastSeq)
    }

    @Test
    fun `missed replaces the terminal map and resets seq, then later events apply`() {
        val missed = reduce(CoreNotification.Missed(2134, listOf(dto("t2"), dto("rohan/t1"))))
        assertEquals(ReduceEffect.SnapshotReplaced, missed.effect)
        assertEquals(listOf("t2", "rohan/t1"), missed.state.terminals.map { it.address.value })
        assertEquals(2134, missed.state.lastSeq)
        assertEquals(missed.state, reduce(event(2100, "removed", "t2"), missed.state).state)
        assertNull(reduce(event(2135, "removed", "t2"), missed.state).state.t("t2"))
    }

    @Test
    fun `unknown event types are ignored but advance seq`() {
        val r = reduce(event(11, "teleported", data = """{"x":1}"""))
        assertEquals(base().terminals, r.state.terminals)
        assertEquals(11, r.state.lastSeq)
        assertNull(r.effect)
    }

    @Test
    fun `events for unknown terminals are ignored`() {
        val r = reduce(event(11, "title", "t99", """{"title":"x"}"""))
        assertEquals(base().terminals, r.state.terminals)
    }

    @Test
    fun `doc events report the document and machine`() {
        val r =
            reducer.reduce(
                base(),
                CoreNotification.Event(11, "doc", null, "rohan", obj("""{"name":"herdr-app","version":5}""")),
                0,
            )
        assertEquals(ReduceEffect.DocChanged("herdr-app", "rohan"), r.effect)
        assertEquals(11, r.state.lastSeq)
    }

    @Test
    fun `snapshot keeps the local status stamp when the word is unchanged`() {
        val working = reduce(event(11, "status", data = """{"status":{"state":"working"}}"""), now = 1_000).state
        val sameJson = FakeCore.terminal("t1").toMutableMap().apply { put("status", obj("""{"state":"working"}""")) }
        val snap = reducer.snapshot(working.terminals, listOf(TerminalDto.parse(JsonObject(sameJson))), 50)
        assertEquals(1_000L, snap.terminals.single().statusChangedAtMs)
        val nullStatus =
            reducer.snapshot(
                working.terminals,
                listOf(
                    TerminalDto.parse(
                        JsonObject(sameJson + ("status" to JsonNull)),
                    ),
                ),
                51,
            )
        assertNull(nullStatus.terminals.single().statusChangedAtMs)
    }

    @Test
    fun `backoff grows exponentially, caps at 30s and applies jitter`() {
        val mid = SessionConfig(random = FixedRandom(0.5))
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (1..7).map { mid.backoffDelayMs(it) },
        )
        assertEquals(800L, SessionConfig(random = FixedRandom(0.0)).backoffDelayMs(1))
        assertEquals(36_000L, SessionConfig(random = FixedRandom(1.0)).backoffDelayMs(10))
    }
}

/** Deterministic jitter source: `nextDouble()` always returns [value]. */
class FixedRandom(
    private val value: Double,
) : kotlin.random.Random() {
    override fun nextBits(bitCount: Int): Int = 0

    override fun nextDouble(): Double = value
}
