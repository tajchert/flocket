package com.mtajchert.flocket.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentViewTest {
    private fun rec(
        state: AgentState?,
        turn: Long = 0,
        labels: Map<String, String> = emptyMap(),
    ) = TerminalRecord(
        ref = TerminalRef(CoreEndpoint(HostId("h1")), TerminalAddress("t1")),
        labels = labels,
        liveness = Liveness.Running,
        status = if (state == null) AgentStatus.Unknown else AgentStatus.Reported(state, state.name.lowercase()),
        turn = turn,
        cols = 80,
        rows = 24,
        createdAtMs = 0,
    )

    private fun seen(v: String) = mapOf(HerdrLabels.SEEN to v)

    @Test
    fun `unknown status is not an agent and has no shown status`() {
        val r = rec(null)
        assertFalse(r.isAgent)
        assertNull(r.shownStatus())
        assertNull(r.childShownStatus())
    }

    @Test
    fun `working is working whatever seen says`() {
        assertEquals(ShownStatus.Working, rec(AgentState.Working, 5, seen("0")).shownStatus())
        assertTrue(rec(AgentState.Working).isAgent)
    }

    @Test
    fun `blocked and error need you`() {
        assertEquals(ShownStatus.NeedsYou, rec(AgentState.Blocked, 3, seen("3")).shownStatus())
        assertEquals(ShownStatus.NeedsYou, rec(AgentState.Error, 3, seen("3")).shownStatus())
    }

    @Test
    fun `idle with turn above seen is done`() {
        assertEquals(ShownStatus.Done, rec(AgentState.Idle, 2, seen("1")).shownStatus())
    }

    @Test
    fun `idle with turn equal to seen is idle`() {
        assertEquals(ShownStatus.Idle, rec(AgentState.Idle, 2, seen("2")).shownStatus())
    }

    @Test
    fun `done with seen caught up is idle`() {
        assertEquals(ShownStatus.Idle, rec(AgentState.Done, 2, seen("2")).shownStatus())
        assertEquals(ShownStatus.Done, rec(AgentState.Done, 2, seen("1")).shownStatus())
    }

    @Test
    fun `seen of zero differs from a missing label`() {
        assertEquals(0L, rec(AgentState.Idle, labels = seen("0")).seenTurn)
        assertEquals(ShownStatus.Done, rec(AgentState.Idle, 1, seen("0")).shownStatus())
        assertNull(rec(AgentState.Idle, 1).seenTurn)
        assertEquals(ShownStatus.Idle, rec(AgentState.Idle, 1).shownStatus())
    }

    @Test
    fun `missing seen falls back to the raw word`() {
        assertEquals(ShownStatus.Done, rec(AgentState.Done, 4).shownStatus())
        assertEquals(ShownStatus.Idle, rec(AgentState.Idle, 4).shownStatus())
        assertEquals(ShownStatus.Idle, rec(AgentState.Other, 4).shownStatus())
    }

    @Test
    fun `unparsable seen falls back to the raw word`() {
        assertNull(rec(AgentState.Done, labels = seen("abc")).seenTurn)
        assertEquals(ShownStatus.Done, rec(AgentState.Done, 1, seen("abc")).shownStatus())
        assertEquals(ShownStatus.Idle, rec(AgentState.Idle, 9, seen("")).shownStatus())
    }

    @Test
    fun `pinned needs a non-empty label`() {
        assertFalse(rec(AgentState.Idle).isPinned)
        assertFalse(rec(AgentState.Idle, labels = mapOf(HerdrLabels.PINNED to "")).isPinned)
        assertTrue(rec(AgentState.Idle, labels = mapOf(HerdrLabels.PINNED to "1")).isPinned)
    }

    @Test
    fun `child error needs you and child done is idle`() {
        assertEquals(ShownStatus.NeedsYou, rec(AgentState.Error).childShownStatus())
        assertEquals(ShownStatus.NeedsYou, rec(AgentState.Blocked).childShownStatus())
        assertEquals(ShownStatus.Idle, rec(AgentState.Done, 3, seen("0")).childShownStatus())
        assertEquals(ShownStatus.Working, rec(AgentState.Working).childShownStatus())
        assertEquals(ShownStatus.Idle, rec(AgentState.Idle, 3, seen("0")).childShownStatus())
        assertNull(rec(AgentState.Other).childShownStatus())
    }

    @Test
    fun `ranks are ordered`() {
        assertEquals(listOf(4, 3, 2, 1), ShownStatus.entries.map { it.rank })
    }
}
