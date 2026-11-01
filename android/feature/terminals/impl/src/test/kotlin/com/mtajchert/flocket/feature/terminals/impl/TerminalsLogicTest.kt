package com.mtajchert.flocket.feature.terminals.impl

import com.mtajchert.flocket.core.model.AgentState
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

class TerminalsLogicTest {
    private fun tab(
        key: String,
        name: String,
        vararg panes: String,
        top: AgentState? = null,
    ) = WorkspaceTab(key, name, panes.map(::TerminalAddress), null, top, 1)

    private fun space(
        key: String,
        name: String,
        folder: String?,
        top: AgentState?,
        vararg tabs: WorkspaceTab,
        machine: String? = null,
        source: SpaceSource = SpaceSource.Desktop,
    ) = WorkspaceSpace(key, name, folder, source, machine, tabs.toList(), top, 1)

    // Studio sample: t1 working, t2 blocked, t3 done, t4 idle, t5 no status, t6 finished; rohan/t7 error.
    private val workspace =
        Workspace(
            listOf(
                space(
                    "desktop:a",
                    "Flocket",
                    "/Users/mtajchert/code/flocket",
                    AgentState.Blocked,
                    tab("1", "agents", "t1", "t2", top = AgentState.Blocked),
                    tab("2", "scratch", "t4", "t5", "t6", top = AgentState.Idle),
                ),
                space("desktop:b", "Docs", "/Users/mtajchert/code/docs", AgentState.Done, tab("1", "", "t3")),
                space(
                    "folder:rohan:/srv",
                    "srv",
                    "/srv",
                    AgentState.Error,
                    tab("all", "", "rohan/t7"),
                    machine = "rohan",
                    source = SpaceSource.Folder,
                ),
            ),
        )

    private fun state(controls: ListControls = ListControls()) =
        buildUiState(listOf(SampleData.overview.first()), controls, refreshing = false) { workspace }

    private val SampleHost get() = SampleData.studio.id

    private fun TerminalsUiState.local() =
        groups.first { it.hostId == SampleHost }.sections.first { it.machine == null }

    @Test
    fun `rows are grouped space then tab and attention first sorts spaces and rows`() {
        val local = state().local()
        assertEquals(listOf("Flocket", "Docs"), local.spaces.map { it.name })
        assertEquals(listOf("agents", "scratch"), local.spaces[0].tabs.map { it.name })
        // blocked before working inside the first tab; finished t6 is hidden by default
        assertEquals(
            listOf("t2", "t1"),
            local.spaces[0]
                .tabs[0]
                .items
                .map { it.address },
        )
        assertEquals(
            listOf("t4", "t5"),
            local.spaces[0]
                .tabs[1]
                .items
                .map { it.address },
        )
    }

    @Test
    fun `without attention first the layout order is kept`() {
        val local = state(ListControls(attentionFirst = false)).local()
        assertEquals(
            listOf("t1", "t2"),
            local.spaces[0]
                .tabs[0]
                .items
                .map { it.address },
        )
        assertEquals(listOf("Flocket", "Docs"), local.spaces.map { it.name })
    }

    @Test
    fun `spaces sort by urgency when attention first is on`() {
        val swapped = Workspace(workspace.spaces.take(2).reversed())
        val s = buildUiState(listOf(SampleData.overview.first()), ListControls(), false) { swapped }
        assertEquals(
            listOf("Flocket", "Docs"),
            s.groups
                .first()
                .sections
                .first()
                .spaces
                .map { it.name },
        )
        val plain =
            buildUiState(listOf(SampleData.overview.first()), ListControls(attentionFirst = false), false) { swapped }
        assertEquals(
            listOf("Docs", "Flocket"),
            plain.groups
                .first()
                .sections
                .first()
                .spaces
                .map { it.name },
        )
    }

    @Test
    fun `filters drop empty tabs and spaces`() {
        val local = state(ListControls(filter = TerminalFilter.Working)).local()
        assertEquals(listOf("Flocket"), local.spaces.map { it.name })
        assertEquals(1, local.spaces[0].tabs.size)
        assertEquals(listOf("t1"), local.items.map { it.address })
        assertFalse(local.spaces[0].showTabLabels)
    }

    @Test
    fun `tab labels show only for spaces with more than one tab`() {
        val local = state().local()
        assertTrue(local.spaces[0].showTabLabels)
        assertFalse(local.spaces[1].showTabLabels)
    }

    @Test
    fun `finished rows appear when asked for and count stays honest`() {
        val local = state(ListControls(showFinished = true)).local()
        assertTrue(
            "t6" in
                local.spaces[0]
                    .tabs[1]
                    .items
                    .map { it.address },
        )
        assertEquals(5, local.spaces[0].count)
    }

    @Test
    fun `folder subtitle appears only when it adds to the name`() {
        val local = state().local()
        assertEquals("~/code/flocket", local.spaces[0].folderSubtitle)
        val folderOnly =
            buildUiState(listOf(SampleData.overview.first()), ListControls(), false) {
                Workspace(
                    listOf(
                        space(
                            "folder::/a/flocket",
                            "flocket",
                            "/a/flocket",
                            null,
                            tab("all", "", "t1"),
                            source = SpaceSource.Folder,
                        ),
                    ),
                )
            }
        assertNull(folderOnly.local().spaces[0].folderSubtitle)
    }

    @Test
    fun `federated spaces stay in their machine section after the host's own`() {
        val sections = state().groups.first().sections
        assertEquals(listOf(null, "rohan"), sections.map { it.machine })
        assertEquals(listOf("rohan/t7"), sections[1].items.map { it.address })
    }

    @Test
    fun `row folder is hidden when the space header already shows it`() {
        val rows = state().local().spaces[0].items
        assertTrue(rows.all { it.cwd.isEmpty() })
        val other = Workspace(listOf(space("desktop:x", "Other", "/elsewhere", null, tab("1", "", "t1"))))
        val s = buildUiState(listOf(SampleData.overview.first()), ListControls(), false) { other }
        assertEquals(
            "~/code/flocket",
            s
                .local()
                .spaces[0]
                .items
                .single()
                .cwd,
        )
    }
}
