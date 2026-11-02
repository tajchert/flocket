package com.mtajchert.flocket.feature.terminals.impl

import com.mtajchert.flocket.core.model.AgentCatalog
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.SpaceSource
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalSort
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

    private val grouped = ListControls(sort = TerminalSort.Grouped)

    private fun state(controls: ListControls = grouped) =
        buildUiState(listOf(SampleData.overview.first()), controls, refreshing = false) { workspace }

    private val SampleHost get() = SampleData.studio.id

    private fun TerminalsUiState.local() =
        groups.first { it.hostId == SampleHost }.sections.first { it.machine == null }

    @Test
    fun `grouped mode groups rows space then tab and sorts rows by rank`() {
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
    fun `grouped mode orders spaces, tabs and rows by desktop rank, stable`() {
        // A Working space listed before a Done one: Done ranks higher, so it comes first.
        val working =
            space("desktop:w", "Work", null, AgentState.Working, tab("1", "w", "t1", top = AgentState.Working))
        val done = space("desktop:d", "Fin", null, AgentState.Done, tab("1", "d", "t3", top = AgentState.Done))
        val idleA = space("desktop:i1", "IdleA", null, AgentState.Idle, tab("1", "", "t4", top = AgentState.Idle))
        val idleB = space("desktop:i2", "IdleB", null, AgentState.Idle, tab("1", "", "t5", top = AgentState.Idle))
        val multi =
            space(
                "desktop:m",
                "Multi",
                null,
                AgentState.Blocked,
                tab("1", "first", "t4", top = AgentState.Idle),
                tab("2", "second", "t2", "t1", top = AgentState.Blocked),
            )
        val ws = Workspace(listOf(working, idleA, done, idleB, multi))
        val local =
            buildUiState(listOf(SampleData.overview.first()), grouped, false) { ws }.local()
        assertEquals(listOf("Multi", "Fin", "Work", "IdleA", "IdleB"), local.spaces.map { it.name })
        // inside Multi the blocked tab comes first; inside it blocked t2 before working t1
        assertEquals(listOf("second", "first"), local.spaces[0].tabs.map { it.name })
        assertEquals(
            listOf("t2", "t1"),
            local.spaces[0]
                .tabs[0]
                .items
                .map { it.address },
        )
        assertTrue(local.priority.isEmpty())
    }

    private fun TerminalsUiState.priority(machine: String? = null) =
        groups
            .first { it.hostId == SampleHost }
            .sections
            .first { it.machine == machine }
            .priority

    @Test
    fun `priority mode puts rows into desktop sections in order`() {
        val sections = state(ListControls()).local().priority
        assertEquals(
            listOf(
                PriorityBucket.NeedsYou,
                PriorityBucket.Done,
                PriorityBucket.Working,
                PriorityBucket.Idle,
                PriorityBucket.NoStatus,
            ),
            sections.map { it.bucket },
        )
        assertEquals(
            listOf("t2", "t3", "t1", "t4", "t5"),
            sections.map {
                it.rows
                    .single()
                    .item.address
            },
        )
        assertTrue(state(ListControls()).local().spaces.isEmpty())
    }

    @Test
    fun `the priority list is one list across hosts in desktop section order`() {
        val ui = buildUiState(SampleData.overview, ListControls(), refreshing = false)
        val all = prioritySections(ui.groups)
        assertEquals(
            PriorityBucket.entries.filter { b -> all.any { it.bucket == b } },
            all.map { it.bucket },
        )
        assertEquals(PriorityBucket.NoStatus, all.last().bucket)
        assertEquals(
            ui.groups.sumOf { it.visibleCount },
            all.sumOf { it.rows.size },
        )
        val one = prioritySections(ui.groups, ui.groups.first().hostId)
        assertTrue(one.all { s -> s.rows.all { it.hostId == ui.groups.first().hostId } })
    }

    @Test
    fun `row item carries the matched agent id`() {
        val claude =
            SampleData.terminal(
                address = "t1",
                name = null,
                argv = listOf("node", "/usr/bin/claude"),
                status = AgentStatus.Reported(AgentState.Working, "working", app = "claude-code"),
            )
        val unknownApp =
            SampleData.terminal(
                address = "t2",
                name = null,
                argv = listOf("node", "/srv/mytool.js"),
                status = AgentStatus.Reported(AgentState.Working, "working", app = "mytool"),
            )
        assertEquals("claude", claude.toItem(writable = true).agentId)
        assertNull(unknownApp.toItem(writable = true).agentId)
    }

    private fun terminal(
        address: String,
        state: AgentState?,
    ) = SampleData.terminal(
        address = address,
        name = null,
        argv = listOf("sh"),
        status = state?.let { AgentStatus.Reported(it, it.name.lowercase()) } ?: AgentStatus.Unknown,
    )

    @Test
    fun `priority rows keep core order inside a section`() {
        val session =
            SampleData.overview.first().session.copy(
                terminals =
                    listOf(
                        terminal("t9", AgentState.Working),
                        terminal("t3", AgentState.Idle),
                        terminal("t1", AgentState.Working),
                        terminal("t2", AgentState.Error),
                        terminal("t5", AgentState.Blocked),
                    ),
            )
        val ov = listOf(SampleData.overview.first().copy(session = session))
        val ws = Workspace(listOf(space("desktop:a", "A", null, null, tab("1", "", "t9", "t3", "t1", "t2", "t5"))))
        val sections = buildUiState(ov, ListControls(), false) { ws }.local().priority
        assertEquals(
            listOf(PriorityBucket.NeedsYou, PriorityBucket.Working, PriorityBucket.Idle),
            sections.map { it.bucket },
        )
        assertEquals(listOf("t2", "t5"), sections[0].rows.map { it.item.address })
        assertEquals(listOf("t9", "t1"), sections[1].rows.map { it.item.address })
    }

    @Test
    fun `priority row place names space and tab, tab only when the space has several`() {
        val rows =
            state(
                ListControls(),
            ).local().priority.flatMap { it.rows }.associateBy { it.item.address }
        val t1 = rows.getValue("t1").place // Flocket has two tabs
        assertEquals("Flocket", t1.spaceName)
        assertEquals(1, t1.spaceNumber)
        assertEquals("agents", t1.tabName)
        assertEquals("1", t1.tabKey)
        assertTrue(t1.showTab)
        assertEquals("scratch", rows.getValue("t4").place.tabName)
        val t3 = rows.getValue("t3").place // Docs has a single tab
        assertEquals("Docs", t3.spaceName)
        assertFalse(t3.showTab)
    }

    @Test
    fun `a space's automatic name is its folder's name, via the model's folderName`() {
        val rows =
            state(
                ListControls(),
            ).local().priority.flatMap { it.rows }.associateBy { it.item.address }
        assertEquals("flocket", rows.getValue("t1").place.spaceAutoName)
        assertEquals("docs", rows.getValue("t3").place.spaceAutoName)
    }

    @Test
    fun `empty priority sections are hidden`() {
        val sections = state(ListControls()).local().priority
        val session =
            SampleData.overview
                .first()
                .session
                .copy(terminals = listOf(terminal("t1", AgentState.Idle)))
        val ov = listOf(SampleData.overview.first().copy(session = session))
        val only =
            buildUiState(
                ov,
                ListControls(),
                false,
            ) { Workspace(listOf(space("d:a", "A", null, null, tab("1", "", "t1")))) }
        assertEquals(listOf(PriorityBucket.Idle), only.local().priority.map { it.bucket })
    }

    @Test
    fun `priority mode with Working filter and finished hidden`() {
        val s = state(ListControls(filter = TerminalFilter.Working))
        assertEquals(listOf(PriorityBucket.Working), s.local().priority.map { it.bucket })
        assertEquals(listOf("t1"), s.local().items.map { it.address })
        val host = s.groups.first { it.hostId == SampleHost }
        // the host total is the unfiltered terminal count, not what the filter shows
        assertEquals(
            SampleData.overview
                .first()
                .session.terminals
                .count { it.liveness !is Liveness.Finished },
            host.total,
        )
        assertEquals(1, host.visibleCount)
    }

    @Test
    fun `federated terminals get their own machine section in priority mode`() {
        val sections = state(ListControls()).groups.first().sections
        assertEquals(listOf(null, "rohan"), sections.map { it.machine })
        assertEquals(listOf(PriorityBucket.NeedsYou), sections[1].priority.map { it.bucket })
    }

    @Test
    fun `filters drop empty tabs and spaces`() {
        val local = state(ListControls(filter = TerminalFilter.Working, sort = TerminalSort.Grouped)).local()
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
    fun `finished terminals are never listed and do not count`() {
        val s = state(ListControls(sort = TerminalSort.Grouped))
        val local = s.local()
        assertFalse("t6" in local.items.map { it.address })
        assertEquals(4, local.spaces[0].count)
        assertFalse("t6" in state().local().items.map { it.address })
    }

    @Test
    fun `folder subtitle appears only when it adds to the name`() {
        val local = state().local()
        assertEquals("~/code/flocket", local.spaces[0].folderSubtitle)
        val folderOnly =
            buildUiState(listOf(SampleData.overview.first()), grouped, false) {
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
        val s = buildUiState(listOf(SampleData.overview.first()), grouped, false) { other }
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

    @Test
    fun `a telling title drops the spinner glyph and ignores paths, prompts and the agent's own name`() {
        val claude = AgentCatalog.of(SampleData.terminal(address = "t1", name = null, argv = listOf("claude")))
        assertEquals("QA", tellingTitle("\u2733 QA", claude, "claude-code"))
        assertEquals("Collapse button", tellingTitle("\u280b Collapse button", claude, "claude-code"))
        assertNull(tellingTitle("~/code/flocket", claude, "claude-code"))
        assertNull(tellingTitle("/usr/bin", null, "zsh"))
        assertNull(tellingTitle("me@studio: ~", null, "zsh"))
        assertNull(tellingTitle("\u2733 Claude Code", claude, "claude-code"))
        assertNull(tellingTitle("claude-code", claude, "claude-code"))
        assertNull(tellingTitle("  ", null, "zsh"))
    }

    @Test
    fun `row detail is a named tab, else the program's title, else the terminal's name`() {
        val item =
            SampleData
                .terminal(
                    address = "t1",
                    name = "refactor-auth",
                    argv = listOf("claude"),
                ).toItem(writable = true)
        assertEquals("agents", rowDetail("agents", item.copy(liveTitle = "QA")))
        assertEquals("QA", rowDetail("", item.copy(liveTitle = "QA")))
        assertEquals("refactor-auth", rowDetail("", item))
        assertNull(rowDetail("", item.copy(name = null)))
    }

    @Test
    fun `rows of one space with nothing else to tell them apart get their tab numbers`() {
        val unnamed =
            SampleData.overview.first().session.let { s ->
                s.copy(terminals = s.terminals.map { it.copy(name = null) })
            }
        val ws =
            Workspace(
                listOf(
                    WorkspaceSpace(
                        "desktop:a",
                        "Flocket",
                        null,
                        SpaceSource.Desktop,
                        null,
                        listOf(
                            WorkspaceTab("1", "", listOf(TerminalAddress("t1")), null, null, 1),
                            WorkspaceTab("2", "", listOf(TerminalAddress("t2")), null, null, 2),
                            WorkspaceTab("3", "review", listOf(TerminalAddress("t3")), null, null, 3),
                        ),
                        null,
                        1,
                    ),
                    space("desktop:b", "Docs", null, null, tab("1", "", "t4")),
                ),
            )
        val s = buildUiState(listOf(SampleData.overview.first().copy(session = unnamed)), ListControls(), false) { ws }
        val details = s.local().items.associate { it.address to it.detail }
        assertEquals("1", details["t1"])
        assertEquals("2", details["t2"])
        assertEquals("review", details["t3"])
        assertNull(details["t4"]) // alone in its space: the project says enough
    }
}
