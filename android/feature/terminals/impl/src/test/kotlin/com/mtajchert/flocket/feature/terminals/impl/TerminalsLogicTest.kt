package com.mtajchert.flocket.feature.terminals.impl

import com.mtajchert.flocket.core.data.HostOverview
import com.mtajchert.flocket.core.model.AgentCatalog
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.ShownStatus
import com.mtajchert.flocket.core.model.SpaceSource
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalRecord
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
    fun `grouped mode groups rows space then tab in layout order`() {
        val local = state().local()
        assertEquals(listOf("Flocket", "Docs"), local.spaces.map { it.name })
        assertEquals(listOf("agents", "scratch"), local.spaces[0].tabs.map { it.name })
        // layout order inside the first tab (no urgency sorting); finished t6 is hidden by default
        assertEquals(
            listOf("t1", "t2"),
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
    fun `grouped mode keeps the layout order of spaces, tabs and rows`() {
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
        assertEquals(listOf("Work", "IdleA", "Fin", "IdleB", "Multi"), local.spaces.map { it.name })
        assertEquals(listOf("first", "second"), local.spaces[4].tabs.map { it.name })
        assertEquals(
            listOf("t2", "t1"),
            local.spaces[4]
                .tabs[1]
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
    fun `the row's preview line drops the agent's spinner, so every frame reads the same`() {
        fun headline(title: String) =
            SampleData
                .terminal(
                    address = "t29",
                    name = null,
                    argv = listOf("codex"),
                    status = AgentStatus.Reported(AgentState.Working, "codex", title = title),
                ).copy(title = title)
                .toItem(writable = true)
                .headline
        val frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏⠃".map { headline("$it Download MyInsta | myinsta") }
        assertEquals(setOf("Download MyInsta | myinsta"), frames.toSet())
        // The terminal's own title, when the status says nothing, is cleaned the same way.
        val plain =
            SampleData
                .terminal(address = "t30", name = null, argv = listOf("codex"))
                .copy(title = "⠙ codexdir")
                .toItem(writable = true)
        assertEquals("codexdir", plain.headline)
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

    // ---- agents-panel list logic -------------------------------------------------------------

    private fun agent(
        address: String,
        state: AgentState,
        turn: Long = 0,
        seen: Long? = null,
        since: Long? = null,
        pinned: Boolean = false,
        parent: String? = null,
        name: String? = null,
    ) = SampleData
        .terminal(
            address = address,
            name = name,
            argv = listOf("/bin/claude"),
            status = AgentStatus.Reported(state, state.name.lowercase()),
        ).copy(
            turn = turn,
            statusChangedAtMs = since,
            parent = parent,
            labels =
                listOfNotNull(
                    seen?.let { "herdr.seen" to it.toString() },
                    if (pinned) "herdr.pinned" to "1" else null,
                ).toMap(),
        )

    private fun overviewOf(vararg terminals: TerminalRecord) =
        HostOverview(
            SampleData.studio,
            SampleData.overview
                .first()
                .session
                .copy(terminals = terminals.toList(), machines = emptyList()),
        )

    private fun priority(
        vararg terminals: TerminalRecord,
        held: OpenTerminal? = null,
    ) = prioritySections(buildUiState(listOf(overviewOf(*terminals)), ListControls(), false, held = held).groups)
        .associate { it.bucket to it.rows.map { r -> r.row.item.address } }

    @Test
    fun `sections come from the shown status, a finished turn is Done until seen then Idle`() {
        val sections =
            priority(
                agent("a", AgentState.Idle, turn = 3, seen = 2),
                agent("b", AgentState.Idle, turn = 3, seen = 3),
                agent("c", AgentState.Working),
                agent("d", AgentState.Error),
                SampleData.terminal(address = "sh", name = null, argv = listOf("/bin/zsh")),
            )
        assertEquals(listOf("d"), sections[PriorityBucket.NeedsYou])
        assertEquals(listOf("a"), sections[PriorityBucket.Done])
        assertEquals(listOf("c"), sections[PriorityBucket.Working])
        assertEquals(listOf("b"), sections[PriorityBucket.Idle])
        assertEquals(listOf("sh"), sections[PriorityBucket.NoStatus])
    }

    @Test
    fun `a missing seen label falls back to the raw word`() {
        val sections = priority(agent("a", AgentState.Done), agent("b", AgentState.Idle, turn = 5))
        assertEquals(listOf("a"), sections[PriorityBucket.Done])
        assertEquals(listOf("b"), sections[PriorityBucket.Idle])
    }

    @Test
    fun `rows in a section are ordered by since, newest first, then by original order`() {
        val sections =
            priority(
                agent("a", AgentState.Working, since = 100),
                agent("b", AgentState.Working, since = 300),
                agent("c", AgentState.Working, since = 300),
                agent("d", AgentState.Working, since = null),
            )
        assertEquals(listOf("b", "c", "a", "d"), sections[PriorityBucket.Working])
    }

    @Test
    fun `pinned rows form the first section in the same order and are not repeated`() {
        val sections =
            priority(
                agent("a", AgentState.Working, pinned = true, since = 1),
                agent("b", AgentState.Error, pinned = true, since = 1),
                agent("c", AgentState.Error),
            )
        assertEquals(listOf(PriorityBucket.Pinned, PriorityBucket.NeedsYou), sections.keys.toList())
        assertEquals(listOf("b", "a"), sections[PriorityBucket.Pinned])
        assertEquals(listOf("c"), sections[PriorityBucket.NeedsYou])
    }

    @Test
    fun `a sub-agent nests under its listed parent and lifts the parent's urgency`() {
        val state =
            buildUiState(
                listOf(
                    overviewOf(
                        agent("p", AgentState.Idle, turn = 1, seen = 1),
                        agent("k1", AgentState.Error, parent = "p", name = "reviewer"),
                        agent("k2", AgentState.Done, parent = "p"),
                        agent("orphan", AgentState.Idle, parent = "gone"),
                    ),
                ),
                ListControls(),
                false,
            )
        val rows = prioritySections(state.groups).flatMap { s -> s.rows.map { s.bucket to it.row.item } }
        val parent = rows.first { it.second.address == "p" }
        // parent glyph stays Idle, but it sits in Needs you because of the child
        assertEquals(PriorityBucket.NeedsYou, parent.first)
        assertEquals(ShownStatus.Idle, parent.second.shown)
        assertEquals(
            listOf("reviewer", "claude"),
            parent.second.children
                .map { it.title },
        )
        // a sub-agent is never Done
        assertEquals(
            listOf(
                ShownStatus.NeedsYou,
                ShownStatus.Idle,
            ),
            parent.second.children
                .map { it.shown },
        )
        // children have no row of their own; an orphan (parent absent) is a normal row
        assertEquals(listOf("p", "orphan"), rows.map { it.second.address })
    }

    @Test
    fun `parent cycles leave ordinary rows`() {
        val rows =
            priority(agent("x", AgentState.Working, parent = "y"), agent("y", AgentState.Working, parent = "x"))
        assertEquals(listOf("x", "y"), rows[PriorityBucket.Working])
    }

    @Test
    fun `grouped mode keeps sub-agents out of the rows and keeps the layout order`() {
        val ws =
            Workspace(
                listOf(
                    space("desktop:a", "A", null, null, tab("1", "", "w")),
                    space("desktop:b", "B", null, null, tab("1", "", "p", "k")),
                ),
            )
        val terminals =
            arrayOf(
                agent("w", AgentState.Working),
                agent("p", AgentState.Idle, turn = 1, seen = 1),
                agent("k", AgentState.Error, parent = "p"),
            )
        val local = buildUiState(listOf(overviewOf(*terminals)), grouped, false, workspaceOf = { ws }).local()
        assertEquals(listOf("A", "B"), local.spaces.map { it.name })
        assertEquals(listOf("p"), local.spaces[1].items.map { it.address })
        assertEquals(
            ShownStatus.NeedsYou.rank,
            local.spaces[1]
                .items
                .single()
                .urgency,
        )
    }

    @Test
    fun `hold rule keeps a seen Done agent at Done rank only while it is open`() {
        val rule = HoldRule()
        val open = OpenTerminal(SampleData.studio.id, "a")
        assertNull(rule.update(null, null))
        assertNull(rule.update(open, ShownStatus.Done))
        assertEquals(open, rule.update(open, ShownStatus.Idle)) // looked at: Done -> Idle, held
        assertEquals(open, rule.update(open, ShownStatus.Idle))
        assertNull(rule.update(open, ShownStatus.Working)) // any other change moves at once
        assertNull(rule.update(open, ShownStatus.Idle)) // Working -> Idle is not held
        assertNull(rule.update(open, ShownStatus.Done))
        assertEquals(open, rule.update(open, ShownStatus.Idle))
        assertNull(rule.update(null, null)) // left it
        assertNull(rule.update(open, ShownStatus.Idle)) // arriving at an Idle one
    }

    @Test
    fun `a held row keeps Done rank but shows Idle`() {
        val a = agent("a", AgentState.Idle, turn = 2, seen = 2)
        val sections = priority(a, held = OpenTerminal(SampleData.studio.id, "a"))
        assertEquals(listOf("a"), sections[PriorityBucket.Done])
        val item = a.toItem(writable = true, held = true)
        assertEquals(ShownStatus.Idle, item.shown)
    }

    @Test
    fun `mark unread is offered for agents past turn 0 that are not Done`() {
        assertTrue(agent("a", AgentState.Idle, turn = 2, seen = 2).toItem(true).canMarkUnread)
        assertFalse(agent("a", AgentState.Idle, turn = 0, seen = 0).toItem(true).canMarkUnread)
        assertFalse(agent("a", AgentState.Idle, turn = 2, seen = 1).toItem(true).canMarkUnread)
        assertFalse(
            SampleData.terminal(address = "s", name = null, argv = listOf("/bin/zsh")).toItem(true).canMarkUnread,
        )
    }

    private fun nestedFor(
        layout: com.mtajchert.flocket.core.model.DesktopLayout?,
        vararg terminals: TerminalRecord,
    ): List<TerminalItem> {
        val session =
            SampleData.overview
                .first()
                .session
                .copy(terminals = terminals.toList(), machines = emptyList(), desktopLayout = layout)
        val ov = HostOverview(SampleData.studio, session)
        return buildUiState(listOf(ov), ListControls(), false)
            .groups
            .flatMap { it.sections }
            .flatMap { it.items }
    }

    @Test
    fun `a child with its own desktop pane or herdr space label stays a top-level row`() {
        val layout =
            com.mtajchert.flocket.core.model.DesktopLayout(
                listOf(
                    com.mtajchert.flocket.core.model.DesktopSpace(
                        "s",
                        "S",
                        null,
                        listOf(
                            com.mtajchert.flocket.core.model.DesktopTab(
                                "t",
                                "",
                                listOf(TerminalAddress("p"), TerminalAddress("paned")),
                                null,
                            ),
                        ),
                    ),
                ),
            )
        val labelled =
            agent("labelled", AgentState.Working, parent = "p").let {
                it.copy(
                    labels =
                        it.labels + ("herdr.space" to "x"),
                )
            }
        val items =
            nestedFor(
                layout,
                agent("p", AgentState.Idle),
                agent("paned", AgentState.Working, parent = "p"),
                labelled,
                agent("headless", AgentState.Working, parent = "p"),
            )
        assertEquals(
            listOf("p", "paned", "labelled"),
            items
                .map {
                    it.address
                }.sortedBy { listOf("p", "paned", "labelled").indexOf(it) },
        )
        assertEquals(setOf("p", "paned", "labelled"), items.map { it.address }.toSet())
        assertEquals(listOf("headless"), items.first { it.address == "p" }.children.map { it.address })
    }

    @Test
    fun `sub-agents nest depth first and the root takes the whole subtree's urgency`() {
        val items =
            nestedFor(
                null,
                agent("p", AgentState.Idle, turn = 1, seen = 1),
                agent("k1", AgentState.Idle, parent = "p"),
                agent("g1", AgentState.Error, parent = "k1"),
                agent("k2", AgentState.Working, parent = "p"),
            )
        assertEquals(listOf("p"), items.map { it.address })
        val p = items.single()
        assertEquals(listOf("k1" to 1, "g1" to 2, "k2" to 1), p.children.map { it.address to it.depth })
        assertEquals(ShownStatus.NeedsYou.rank, p.urgency)
    }

    @Test
    fun `grouped mode lists pinned rows first and removes them from their space`() {
        val ws =
            Workspace(
                listOf(
                    space("desktop:a", "A", null, null, tab("1", "", "x", "y")),
                    space("desktop:b", "B", null, null, tab("1", "", "z")),
                ),
            )
        val terms =
            arrayOf(
                agent("x", AgentState.Idle, pinned = true),
                agent("y", AgentState.Idle),
                agent("z", AgentState.Idle, pinned = true),
            )
        val state = buildUiState(listOf(overviewOf(*terms)), grouped, false, workspaceOf = { ws })
        assertEquals(listOf("x", "z"), pinnedGroupedRows(state.groups).map { it.item.address })
        val spaces = state.local().spaces.mapNotNull { it.withoutPinned() }
        assertEquals(listOf("A"), spaces.map { it.name })
        assertEquals(listOf("y"), spaces.single().items.map { it.address })
    }

    @Test
    fun `a remote child never falls back to a local parent of the same relative name`() {
        // rohan/t7 is finished and so not listed: rohan/t8 must not nest under the local t7.
        val live = listOf(agent("t7", AgentState.Working), agent("rohan/t8", AgentState.Working, parent = "t7"))
        val items = itemsWithChildren(live, true, true, null)
        assertEquals(listOf("t7", "rohan/t8"), items.map { it.address })
        assertTrue(items.all { it.children.isEmpty() })
        // with rohan/t7 listed the child nests under it, not under the local t7
        val nested = itemsWithChildren(live + agent("rohan/t7", AgentState.Idle), true, true, null)
        assertEquals(listOf("rohan/t8"), nested.first { it.address == "rohan/t7" }.children.map { it.address })
        assertTrue(nested.first { it.address == "t7" }.children.isEmpty())
    }

    @Test
    fun `a terminal leading into a parent cycle nests and only the cycle members are roots`() {
        val items =
            itemsWithChildren(
                listOf(
                    agent("t", AgentState.Working, parent = "a"),
                    agent("a", AgentState.Idle, parent = "b"),
                    agent("b", AgentState.Idle, parent = "a"),
                ),
                true,
                true,
                null,
            )
        assertEquals(listOf("a", "b"), items.map { it.address })
        assertEquals(listOf("t"), items.first { it.address == "a" }.children.map { it.address })
    }

    @Test
    fun `pin and mark unread are not offered for a federated terminal whose machine is not connected`() {
        val terminals =
            listOf(
                agent("rohan/t7", AgentState.Idle, turn = 2, seen = 2),
                agent("t1", AgentState.Idle, turn = 2, seen = 2),
            )
        val items =
            itemsWithChildren(
                terminals,
                true,
                true,
                null,
                machineConnected = { it == null },
            ).associateBy { it.address }
        assertFalse(items.getValue("rohan/t7").supportsLabels)
        assertTrue(items.getValue("t1").supportsLabels)
        val up = itemsWithChildren(terminals, true, true, null, machineConnected = { true })
        assertTrue(up.all { it.supportsLabels })
    }
}
