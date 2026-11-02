package com.mtajchert.flocket.feature.terminal.impl

import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.SpaceSource
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.Workspace
import com.mtajchert.flocket.core.model.WorkspaceSpace
import com.mtajchert.flocket.core.model.WorkspaceTab
import com.mtajchert.flocket.core.testing.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTabStripModelTest {
    private fun addr(value: String) = TerminalAddress(value)

    private fun record(
        address: String,
        status: AgentStatus = AgentStatus.Unknown,
        liveness: Liveness = Liveness.Running,
        name: String? = null,
        argv: String = "zsh",
    ) = SampleData.terminal(address = address, name = name, argv = listOf(argv), status = status, liveness = liveness)

    private fun reported(state: AgentState) = AgentStatus.Reported(state, state.name.lowercase(), app = "claude")

    private fun tab(
        key: String,
        vararg panes: String,
        active: String? = null,
        state: AgentState? = null,
    ) = WorkspaceTab(key, key.uppercase(), panes.map(::addr), active?.let(::addr), state, 1)

    private fun workspace(vararg tabs: WorkspaceTab) =
        Workspace(
            listOf(WorkspaceSpace("desktop:s1", "api", "/code/api", SpaceSource.Desktop, null, tabs.toList(), null, 1)),
        )

    @Test
    fun `a single tab with a single pane has no strip`() {
        val ws = workspace(tab("a", "t1"))
        assertNull(buildTabStrip(ws, listOf(record("t1")), addr("t1")))
    }

    @Test
    fun `an unknown terminal has no strip`() {
        val ws = workspace(tab("a", "t1"), tab("b", "t2"))
        assertNull(buildTabStrip(ws, listOf(record("t1")), addr("t9")))
    }

    @Test
    fun `two tabs mark the current one and target the active pane, else the first`() {
        val ws = workspace(tab("a", "t1"), tab("b", "t2", "t3", active = "t3"), tab("c", "t4", "t5"))
        val strip = buildTabStrip(ws, listOf(record("t1")), addr("t1"))!!

        assertEquals(listOf("A", "B", "C"), strip.tabs.map { it.name })
        assertEquals(listOf(true, false, false), strip.tabs.map { it.selected })
        assertEquals(listOf("t1", "t3", "t4"), strip.tabs.map { it.target })
        assertTrue("one pane in the current tab: no switcher", strip.panes.isEmpty())
    }

    @Test
    fun `strip tab carries its target pane's agent`() {
        val ws = workspace(tab("a", "t1"), tab("b", "t2", "t3", active = "t3"), tab("c", "t4"))
        val records =
            listOf(
                record("t1", argv = "codex"),
                record("t2", argv = "claude"),
                record("t3", argv = "gemini"),
                record("t4"),
            )
        val strip = buildTabStrip(ws, records, addr("t1"))!!
        assertEquals(listOf("codex", "gemini", null), strip.tabs.map { it.agentId })
    }

    @Test
    fun `the open terminal is the target of its own tab even when another pane is active`() {
        val ws = workspace(tab("a", "t1", "t2", active = "t1"), tab("b", "t3"))
        val strip = buildTabStrip(ws, listOf(record("t2")), addr("t2"))!!
        assertEquals("t2", strip.tabs.first { it.selected }.target)
    }

    @Test
    fun `one tab with several panes shows only the pane switcher`() {
        val ws = workspace(tab("a", "t1", "t2", "t3"))
        val records =
            listOf(
                record("t1", reported(AgentState.Working), argv = "claude"),
                record("t2", name = "build"),
                record("t3", reported(AgentState.Blocked)),
            )
        val strip = buildTabStrip(ws, records, addr("t2"))!!

        assertEquals(1, strip.tabs.size)
        assertEquals(listOf("t1", "t2", "t3"), strip.panes.map { it.address })
        assertEquals(listOf(false, true, false), strip.panes.map { it.selected })
        assertEquals(listOf("claude", "build", "claude"), strip.panes.map { it.label })
        assertEquals(listOf(AgentState.Working, null, AgentState.Blocked), strip.panes.map { it.state })
    }

    @Test
    fun `a finished terminal shows no state and an unknown record falls back to its address`() {
        val ws = workspace(tab("a", "t1", "t2"))
        val records = listOf(record("t1", reported(AgentState.Done), liveness = Liveness.Finished(null, 0, null, null)))
        val strip = buildTabStrip(ws, records, addr("t1"))!!
        assertNull(strip.panes[0].state)
        assertEquals("t2", strip.panes[1].label)
    }

    @Test
    fun `terminals sharing a folder get a pane switcher when no layout is known`() {
        val records = listOf(record("t1"), record("t2"), record("t3", argv = "zsh"))
        val strip = buildTabStrip(records, null, addr("t2"))!!
        assertEquals(listOf("t1", "t2", "t3"), strip.panes.map { it.address })
        assertEquals("t2", strip.panes.single { it.selected }.address)
    }

    @Test
    fun `equal inputs build equal values so the flow can deduplicate`() {
        val ws = workspace(tab("a", "t1"), tab("b", "t2"))
        val records = listOf(record("t1"), record("t2"))
        assertEquals(buildTabStrip(ws, records, addr("t1")), buildTabStrip(ws, records, addr("t1")))
        assertFalse(buildTabStrip(ws, records, addr("t1")) == buildTabStrip(ws, records, addr("t2")))
    }

    private val finished = Liveness.Finished(null, 0, null, null)

    @Test
    fun `finished terminals are left out of the pane switcher, like the sidebar hides them`() {
        val ws = workspace(tab("a", "t1", "t2", "t3", "t4"))
        val records =
            listOf(record("t1"), record("t2", liveness = finished), record("t3", liveness = finished), record("t4"))
        val strip = buildTabStrip(ws, records, addr("t1"))!!
        assertEquals(listOf("t1", "t4"), strip.panes.map { it.address })
    }

    @Test
    fun `the open terminal stays in the switcher even when it has finished`() {
        val ws = workspace(tab("a", "t1", "t2", "t3"))
        val records = listOf(record("t1"), record("t2", liveness = finished), record("t3"))
        val strip = buildTabStrip(ws, records, addr("t2"))!!
        assertEquals(listOf("t1", "t2", "t3"), strip.panes.map { it.address })
        assertTrue(strip.panes.first { it.address == "t2" }.selected)
    }

    @Test
    fun `no switcher when only one pane of the tab is still running`() {
        val ws = workspace(tab("a", "t1", "t2", "t3"))
        val records = listOf(record("t1"), record("t2", liveness = finished), record("t3", liveness = finished))
        assertNull(buildTabStrip(ws, records, addr("t1")))
    }

    @Test
    fun `a tab whose terminals all finished is hidden, and targets skip finished panes`() {
        val ws = workspace(tab("a", "t1"), tab("b", "t2"), tab("c", "t3", "t4", active = "t3"))
        val records =
            listOf(record("t1"), record("t2", liveness = finished), record("t3", liveness = finished), record("t4"))
        val strip = buildTabStrip(ws, records, addr("t1"))!!
        assertEquals(listOf("A", "C"), strip.tabs.map { it.name })
        assertEquals("t4", strip.tabs.first { it.name == "C" }.target)
    }

    @Test
    fun `tab numbers stay those of the full workspace`() {
        val ws =
            workspace(
                tab("a", "t1").copy(name = "", number = 1),
                tab("b", "t2").copy(name = "", number = 2),
                tab("c", "t3").copy(name = "", number = 3),
            )
        val records = listOf(record("t1"), record("t2", liveness = finished), record("t3"))
        val strip = buildTabStrip(ws, records, addr("t1"))!!
        assertEquals(listOf(1, 3), strip.tabs.map { it.number })
    }

    @Test
    fun `the header names the space and points at the first other terminal that needs the user`() {
        val ws = workspace(tab("a", "t1"), tab("b", "t2", "t3"), tab("c", "t4", "t5"))
        val terminals =
            listOf(
                record("t1", reported(AgentState.Blocked)),
                record("t2", reported(AgentState.Working)),
                record("t3", reported(AgentState.Error)),
                record("t4", reported(AgentState.Blocked), liveness = finished),
                record("t5", reported(AgentState.Blocked)),
            )
        val header = buildHeader(ws, terminals, addr("t1"))

        assertEquals("api", header.spaceName)
        assertEquals(1, header.spaceNumber)
        // The open one and finished ones never count.
        assertEquals(2, header.otherNeedsYou)
        assertEquals("t3", header.nextNeedsYou)
    }

    @Test
    fun `nothing else blocked means no next target`() {
        val ws = workspace(tab("a", "t1"), tab("b", "t2"))
        val header = buildHeader(ws, listOf(record("t1"), record("t2", reported(AgentState.Done))), addr("t1"))
        assertEquals(0, header.otherNeedsYou)
        assertNull(header.nextNeedsYou)
    }

    @Test
    fun `reply chips follow the status`() {
        assertEquals(
            listOf(ReplyKey.One, ReplyKey.Two, ReplyKey.Three),
            ReplyKey.forStatus(reported(AgentState.Blocked)),
        )
        assertEquals(listOf(ReplyKey.Escape), ReplyKey.forStatus(reported(AgentState.Working)))
        assertEquals(
            emptyList<ReplyKey>(),
            ReplyKey.forStatus(
                AgentStatus.Reported(
                    AgentState.Blocked,
                    "blocked",
                    kind = com.mtajchert.flocket.core.model.BlockKind.Auth,
                ),
            ),
        )
        assertEquals(emptyList<ReplyKey>(), ReplyKey.forStatus(AgentStatus.Unknown))
        assertEquals(emptyList<ReplyKey>(), ReplyKey.forStatus(reported(AgentState.Done)))
    }
}
