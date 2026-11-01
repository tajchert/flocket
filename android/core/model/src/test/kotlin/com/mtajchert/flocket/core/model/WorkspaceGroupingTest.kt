package com.mtajchert.flocket.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGroupingTest {
    private val endpoint = CoreEndpoint(HostId("h1"))

    private fun term(
        address: String,
        cwd: String = "/tmp",
        space: String? = null,
        tab: String? = null,
        state: AgentState? = null,
        finished: Boolean = false,
    ) = TerminalRecord(
        ref = TerminalRef(endpoint, TerminalAddress(address)),
        labels =
            buildMap {
                space?.let { put(HerdrLabels.SPACE, it) }
                tab?.let { put(HerdrLabels.TAB, it) }
            },
        argv = listOf("sh"),
        cwd = cwd,
        liveness = if (finished) Liveness.Finished("exited", 0, null, 1L) else Liveness.Running,
        status = state?.let { AgentStatus.Reported(it, it.name.lowercase()) } ?: AgentStatus.Unknown,
        cols = 80,
        rows = 24,
        createdAtMs = 0,
    )

    private fun a(vararg v: String) = v.map { TerminalAddress(it) }

    private fun layout(vararg spaces: DesktopSpace) = DesktopLayout(spaces.toList())

    private fun space(
        id: String,
        vararg tabs: DesktopTab,
        name: String = "",
        folder: String? = null,
    ) = DesktopSpace(id, name, folder, tabs.toList())

    private fun tab(
        id: String,
        vararg panes: String,
        name: String = "",
        active: String? = null,
    ) = DesktopTab(id, name, a(*panes), active?.let { TerminalAddress(it) })

    /** Every terminal appears in exactly one tab. */
    private fun assertPartition(
        terminals: List<TerminalRecord>,
        w: Workspace,
    ) {
        val all = w.spaces.flatMap { s -> s.tabs.flatMap { it.panes } }
        assertEquals(terminals.map { it.address }.toSet(), all.toSet())
        assertEquals(all.size, all.toSet().size)
    }

    private fun Workspace.shape() = spaces.map { s -> s.key to s.tabs.map { t -> t.key to t.panes.map { it.value } } }

    @Test
    fun `desktop layout orders spaces, tabs and panes like the document`() {
        val terms = listOf(term("t1"), term("t2"), term("t3", cwd = "/src/web"), term("t4"))
        val l =
            layout(
                space("sp2", tab("tbA", "t3"), name = "web", folder = "/src/web"),
                space("sp1", tab("tbB", "t4", "t1", name = "fix login", active = "t1"), tab("tbC", "t2")),
            )
        val w = buildWorkspace(terms, l)
        assertEquals(
            listOf(
                "desktop:sp2" to listOf("tbA" to listOf("t3")),
                "desktop:sp1" to listOf("tbB" to listOf("t4", "t1"), "tbC" to listOf("t2")),
            ),
            w.shape(),
        )
        val sp1 = w.spaces[1]
        assertEquals(SpaceSource.Desktop, sp1.source)
        assertEquals("web", w.spaces[0].name)
        assertEquals("/src/web", w.spaces[0].folder)
        assertEquals("fix login", sp1.tabs[0].name)
        assertEquals(TerminalAddress("t1"), sp1.tabs[0].activePane)
        assertEquals("", sp1.tabs[1].name)
        assertEquals(2, sp1.tabs[1].number)
        assertNull(sp1.machine)
        assertPartition(terms, w)
    }

    @Test
    fun `unnamed desktop spaces without a live folder fall back to the document folder, then Space N`() {
        val terms = listOf(term("t1", cwd = ""), term("t2", cwd = ""), term("t3", cwd = ""))
        val l =
            layout(
                space("a", tab("x", "t1"), folder = "/Users/me/code/flocket/"),
                space("b", tab("x", "t2"), name = "  "),
                space("c", tab("x", "t3"), folder = "~"),
            )
        val built = buildWorkspace(terms, l).spaces
        assertEquals(listOf("flocket", "", "~"), built.map { it.name })
        assertEquals(listOf(1, 2, 3), built.map { it.number })
    }

    @Test
    fun `layout panes for terminals that no longer exist are dropped with empty tabs and spaces`() {
        val terms = listOf(term("t1"), term("t2"))
        val l =
            layout(
                space("gone", tab("x", "t9")),
                space("sp", tab("empty"), tab("dead", "t8"), tab("live", "t8", "t2", "t1", active = "t8")),
            )
        val w = buildWorkspace(terms, l)
        assertEquals(listOf("desktop:sp" to listOf("live" to listOf("t2", "t1"))), w.shape())
        // Numbers follow the desktop: the place in the document, hidden tabs included.
        assertEquals("", w.spaces[0].tabs[0].name)
        assertEquals(3, w.spaces[0].tabs[0].number)
        assertEquals(1, w.spaces[0].number)
        // The active pane pointed at a vanished terminal: unknown.
        assertNull(w.spaces[0].tabs[0].activePane)
    }

    @Test
    fun `a terminal listed twice in the layout stays in its first pane`() {
        val terms = listOf(term("t1"), term("t2"))
        val l = layout(space("a", tab("x", "t1"), tab("y", "t1", "t2")))
        assertEquals(
            listOf("desktop:a" to listOf("x" to listOf("t1"), "y" to listOf("t2"))),
            buildWorkspace(terms, l).shape(),
        )
    }

    @Test
    fun `terminals the layout does not place fall back to labels, then folders`() {
        val terms =
            listOf(
                term("t1"),
                term("t2", cwd = "/src/api"),
                term("t3", space = "sp9", tab = "tbZ", cwd = "/src/other"),
                term("t4", cwd = "/src/api"),
            )
        val w = buildWorkspace(terms, layout(space("sp1", tab("tb", "t1"))))
        assertEquals(
            listOf(
                "desktop:sp1" to listOf("tb" to listOf("t1")),
                "label:sp9" to listOf("tbZ" to listOf("t3")),
                "folder::/src/api" to listOf("all" to listOf("t2", "t4")),
            ),
            w.shape(),
        )
        assertEquals(listOf(SpaceSource.Desktop, SpaceSource.Labels, SpaceSource.Folder), w.spaces.map { it.source })
        assertEquals("api", w.spaces[2].name)
        assertPartition(terms, w)
    }

    @Test
    fun `a labelled terminal missing from the layout joins its desktop space`() {
        val terms =
            listOf(
                term("t1"),
                term("t2", space = "sp1", tab = "tb"),
                term("t3", space = "sp1", tab = "new"),
            )
        val w = buildWorkspace(terms, layout(space("sp1", tab("tb", "t1"), name = "api")))
        assertEquals(
            listOf("desktop:sp1" to listOf("tb" to listOf("t1", "t2"), "new" to listOf("t3"))),
            w.shape(),
        )
        assertEquals(2, w.spaces[0].tabs[1].number)
    }

    @Test
    fun `labels only group by first appearance in core order`() {
        val terms =
            listOf(
                term("t1", space = "B", tab = "b1", cwd = "/src/beta"),
                term("t2", space = "A", tab = "a1", cwd = "/src/alpha"),
                term("t3", space = "B", tab = "b2"),
                term("t4", space = "B", tab = "b1"),
                term("t5", space = "A"),
                term("t6", space = "C", cwd = ""),
            )
        val w = buildWorkspace(terms, null)
        assertEquals(
            listOf(
                "label:B" to listOf("b1" to listOf("t1", "t4"), "b2" to listOf("t3")),
                "label:A" to listOf("a1" to listOf("t2"), "-" to listOf("t5")),
                "label:C" to listOf("-" to listOf("t6")),
            ),
            w.shape(),
        )
        // Label spaces have no name: the first terminal's start folder, else Space N.
        assertEquals(listOf("beta", "alpha", ""), w.spaces.map { it.name })
        assertEquals(listOf(1, 2, 3), w.spaces.map { it.number })
        assertEquals("/src/beta", w.spaces[0].folder)
        assertEquals(listOf(1, 2), w.spaces[0].tabs.map { it.number })
        assertTrue(w.spaces.all { it.source == SpaceSource.Labels })
        assertPartition(terms, w)
    }

    @Test
    fun `desktop spaces are named after their first terminal's live folder, not the stale document folder`() {
        // Real data: the desktop space was created in kanarek-android, but its terminals all run in flocket.
        fun live(
            address: String,
            start: String,
            now: String?,
        ) = term(
            address,
            cwd = start,
        ).copy(foreground = now?.let { ForegroundProcess(1, "claude", listOf("claude"), it) })
        val terms =
            listOf(
                live("t1g", "/src/flocket", "/src/flocket"),
                live("t1j", "/src/flocket", "/src/flocket"),
                live("t14", "/src/flocket", "/src/carom"),
                live("t19", "/src/kanarek-android", null),
            )
        val l =
            layout(
                space("sp1", tab("a", "t1g"), tab("b", "t1j"), folder = "/src/kanarek-android"),
                space("sp2", tab("a", "t14"), folder = "/src/flocket"),
                space("sp3", tab("a", "t19"), folder = "/src/elsewhere"),
                space("sp4", tab("a", "gone"), folder = "/src/empty"),
            )
        val w = buildWorkspace(terms, l)
        // Foreground folder first, else the start folder (like the desktop's `where` label).
        assertEquals(listOf("flocket", "carom", "kanarek-android"), w.spaces.map { it.name })
        assertEquals(listOf("/src/flocket", "/src/carom", "/src/kanarek-android"), w.spaces.map { it.folder })
    }

    @Test
    fun `folder terminals join a space by its live folder, not the stale document folder`() {
        val terms =
            listOf(
                term("t1", cwd = "/src/flocket"),
                term("t2", cwd = "/src/kanarek-android"),
                term("t3", cwd = "/src/flocket/"),
            )
        val l = layout(space("sp1", tab("a", "t1"), folder = "/src/kanarek-android"))
        val w = buildWorkspace(terms, l)
        assertEquals(
            listOf(
                "desktop:sp1" to listOf("a" to listOf("t1"), "all" to listOf("t3")),
                "folder::/src/kanarek-android" to listOf("all" to listOf("t2")),
            ),
            w.shape(),
        )
    }

    @Test
    fun `folder only groups by start folder, not the foreground folder`() {
        val moved =
            term(
                "t2",
                cwd = "/src/app",
            ).copy(foreground = ForegroundProcess(1, "vim", listOf("vim"), "/elsewhere"))
        val terms =
            listOf(
                term("t1", cwd = "~"),
                moved,
                term("t3", cwd = "~/"),
                term("t4", cwd = "/src/app/"),
                term("t5", cwd = "/"),
            )
        val w = buildWorkspace(terms, null)
        assertEquals(
            listOf(
                "folder::~" to listOf("all" to listOf("t1")),
                "folder::/src/app" to listOf("all" to listOf("t2")),
                "folder::~/" to listOf("all" to listOf("t3")),
                "folder::/src/app/" to listOf("all" to listOf("t4")),
                "folder::/" to listOf("all" to listOf("t5")),
            ),
            w.shape(),
        )
        assertEquals(listOf("~", "app", "~", "app", "/"), w.spaces.map { it.name })
        assertTrue(w.spaces.all { it.source == SpaceSource.Folder && it.tabs.single().number == 1 })
    }

    @Test
    fun `an empty start folder still gets a name`() {
        val w = buildWorkspace(listOf(term("t1", cwd = "")), null)
        assertEquals("", w.spaces.single().name)
        assertEquals(1, w.spaces.single().number)
        assertNull(w.spaces.single().folder)
    }

    @Test
    fun `federated terminals without a machine layout group by labels then folder, after local spaces`() {
        val terms =
            listOf(
                term("t1", cwd = "/a"),
                term("t2", space = "sp1"),
                term("rohan/t7", cwd = "/a", space = "sp1", tab = "tb"),
                term("mordor/t1", cwd = "/a"),
                term("rohan/t8", cwd = "/a"),
            )
        // A (malformed) local layout that names a remote terminal must not claim it.
        val w = buildWorkspace(terms, layout(space("sp1", tab("tb", "rohan/t7"))))
        assertEquals(
            listOf(
                "desktop:sp1" to listOf("-" to listOf("t2")),
                "folder::/a" to listOf("all" to listOf("t1")),
                "label:rohan:sp1" to listOf("tb" to listOf("rohan/t7"), "all" to listOf("rohan/t8")),
                "folder:mordor:/a" to listOf("all" to listOf("mordor/t1")),
            ),
            w.shape(),
        )
        assertEquals(listOf(null, null, "rohan", "mordor"), w.spaces.map { it.machine })
        // `t1` and `mordor/t1` stay distinct.
        assertEquals("mordor", w.locate(TerminalAddress("mordor/t1"))!!.first.machine)
        assertNull(w.locate(TerminalAddress("t1"))!!.first.machine)
        assertPartition(terms, w)
    }

    @Test
    fun `an unplaced terminal whose folder equals a layout space folder joins the first such space`() {
        val terms =
            listOf(
                term("t1", cwd = "/src/api"),
                term("t2", cwd = "/src/api"),
                term("t3", cwd = "/src/web/"),
                term("t4", cwd = "/src/other"),
                term("t5", cwd = "/src/api/"),
            )
        val l =
            layout(
                space("a", tab("x", "t1"), folder = "/src/api/"),
                space("b", tab("x", "t1"), folder = "/src/web"),
                space("c", tab("x", "t1"), folder = "/src/api"),
            )
        val w = buildWorkspace(terms, l)
        assertEquals(
            listOf(
                "desktop:a" to listOf("x" to listOf("t1"), "all" to listOf("t2", "t5")),
                "desktop:b" to listOf("all" to listOf("t3")),
                "folder::/src/other" to listOf("all" to listOf("t4")),
            ),
            w.shape(),
        )
        assertEquals(listOf(1, 2), w.spaces[0].tabs.map { it.number })
        assertPartition(terms, w)
    }

    @Test
    fun `folder-join also targets label spaces`() {
        val terms = listOf(term("t1", space = "L", cwd = "/src/api"), term("t2", cwd = "/src/api"))
        val w = buildWorkspace(terms, null)
        assertEquals(listOf("label:L" to listOf("-" to listOf("t1"), "all" to listOf("t2"))), w.shape())
    }

    @Test
    fun `a machine layout places that machine's terminals, mapping t7 to machine slash t7`() {
        val terms =
            listOf(
                term("t7", cwd = "/l"),
                term("rohan/t7", cwd = "/r/app"),
                term("rohan/t8", space = "ls", tab = "lt", cwd = "/r"),
                term("rohan/t9", cwd = "/r/app"),
                term("mordor/t7", cwd = "/r"),
            )
        val rohan = layout(space("rs", tab("rt", "t7", "t4", active = "t7"), name = "remote", folder = "/r/app"))
        val ghost = layout(space("g", tab("x", "t7")))
        val w = buildWorkspace(terms, null, mapOf("rohan" to rohan, "ghost" to ghost))
        assertEquals(
            listOf(
                "folder::/l" to listOf("all" to listOf("t7")),
                "desktop:rohan:rs" to listOf("rt" to listOf("rohan/t7"), "all" to listOf("rohan/t9")),
                "label:rohan:ls" to listOf("lt" to listOf("rohan/t8")),
                "folder:mordor:/r" to listOf("all" to listOf("mordor/t7")),
            ),
            w.shape(),
        )
        assertEquals(listOf(null, "rohan", "rohan", "mordor"), w.spaces.map { it.machine })
        assertEquals(TerminalAddress("rohan/t7"), w.spaces[1].tabs[0].activePane)
        assertEquals("remote", w.spaces[1].name)
        assertEquals(listOf(1, 2, 3, 4), w.spaces.map { it.number })
        assertPartition(terms, w)
    }

    @Test
    fun `a local layout never places remote terminals and a machine layout never places local ones`() {
        val terms = listOf(term("t7"), term("rohan/t7"))
        val local = layout(space("l", tab("x", "t7", "rohan/t7")))
        val remote = layout(space("r", tab("y", "t7", "rohan/t7")))
        val w = buildWorkspace(terms, local, mapOf("rohan" to remote))
        assertEquals(
            listOf("desktop:l" to listOf("x" to listOf("t7")), "desktop:rohan:r" to listOf("y" to listOf("rohan/t7"))),
            w.shape(),
        )
    }

    @Test
    fun `remote terminals listed before local ones still come after the local spaces`() {
        val terms = listOf(term("rohan/t1", cwd = "/r"), term("t1", cwd = "/l"))
        assertEquals(listOf(null, "rohan"), buildWorkspace(terms, null).spaces.map { it.machine })
    }

    @Test
    fun `status is the most urgent non-finished terminal, per tab and per space`() {
        val terms =
            listOf(
                term("t1", state = AgentState.Idle),
                term("t2", state = AgentState.Blocked, finished = true),
                term("t3", state = AgentState.Working),
                term("t4"),
                term("t5", state = AgentState.Error),
            )
        val l = layout(space("a", tab("x", "t1", "t2"), tab("y", "t3", "t4")), space("b", tab("z", "t4", "t5")))
        val w = buildWorkspace(terms, l)
        assertEquals(AgentState.Idle, w.spaces[0].tabs[0].topState)
        assertEquals(AgentState.Working, w.spaces[0].tabs[1].topState)
        assertEquals(AgentState.Working, w.spaces[0].topState)
        assertEquals(AgentState.Error, w.spaces[1].topState)
    }

    @Test
    fun `priority rank matches the desktop`() {
        assertEquals(4, AgentState.Blocked.priorityRank)
        assertEquals(4, AgentState.Error.priorityRank)
        assertEquals(3, AgentState.Done.priorityRank)
        assertEquals(2, AgentState.Working.priorityRank)
        assertEquals(1, AgentState.Idle.priorityRank)
        assertEquals(0, AgentState.Other.priorityRank)
    }

    @Test
    fun `space topState prefers done over working`() {
        val terms = listOf(term("t1", state = AgentState.Working), term("t2", state = AgentState.Done))
        val w = buildWorkspace(terms, layout(space("a", tab("x", "t1", "t2"))))
        assertEquals(AgentState.Done, w.spaces.single().topState)
        assertEquals(
            AgentState.Done,
            w.spaces
                .single()
                .tabs
                .single()
                .topState,
        )
    }

    @Test
    fun `no reported status gives a null topState`() {
        val w = buildWorkspace(listOf(term("t1"), term("t2", state = AgentState.Done, finished = true)), null)
        assertNull(w.spaces.single().topState)
        assertNull(
            w.spaces
                .single()
                .tabs
                .single()
                .topState,
        )
    }

    @Test
    fun `keys stay unique when a damaged document repeats or omits ids`() {
        val terms = listOf(term("t1"), term("t2"), term("t3"), term("t4"))
        val l = layout(space("x", tab("", "t1"), tab("", "t2")), space("x", tab("t", "t3")), space("", tab("t", "t4")))
        val w = buildWorkspace(terms, l)
        val keys = w.spaces.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(listOf("desktop:x", "desktop:x~2", "desktop:#2"), keys)
        assertEquals(listOf("#0", "#1"), w.spaces[0].tabs.map { it.key })
        assertPartition(terms, w)
    }

    @Test
    fun `the same input always gives the same workspace`() {
        val terms =
            listOf(
                term("t3", space = "s", tab = "a"),
                term("t1", cwd = "/x"),
                term("rohan/t2", cwd = "/y"),
                term("t2", space = "s", tab = "b"),
            )
        val l = layout(space("d", tab("q", "t1")))
        assertEquals(buildWorkspace(terms, l), buildWorkspace(terms.toList(), l.copy()))
    }

    @Test
    fun `an empty host gives an empty workspace and the layout alone adds nothing`() {
        assertEquals(Workspace.Empty, buildWorkspace(emptyList(), null))
        assertEquals(Workspace.Empty, buildWorkspace(emptyList(), layout(space("a", tab("b", "t1")))))
        assertEquals(Workspace.Empty, buildWorkspace(emptyList(), DesktopLayout(emptyList())))
    }

    @Test
    fun `folder names`() {
        assertEquals("app", folderName("/src/app"))
        assertEquals("app", folderName("/src/app//"))
        assertEquals("~", folderName("~"))
        assertEquals("~", folderName("~/"))
        assertEquals("/", folderName("/"))
        assertEquals("proj", folderName("C:\\work\\proj"))
        assertNull(folderName(""))
        assertNull(folderName(null))
    }

    @Test
    fun `desktop spaces and their document tabs carry layout refs`() {
        val terms = listOf(term("t1"), term("t2"))
        val w = buildWorkspace(terms, layout(space("sp1", tab("tb1", "t1"), tab("tb2", "t2"), name = " web ")))
        val sp = w.spaces[0]
        assertEquals(LayoutRef(null, "sp1"), sp.layoutRef)
        assertEquals("web", sp.givenName)
        assertEquals(listOf(LayoutRef(null, "sp1", "tb1"), LayoutRef(null, "sp1", "tb2")), sp.tabs.map { it.layoutRef })
    }

    @Test
    fun `implicit, label-only and folder tabs have no layout ref`() {
        val terms =
            listOf(
                term("t1", cwd = "/work"),
                term("t2", cwd = "/work"),
                term("t3", space = "sp1", tab = "tbX"),
                term("t4", space = "lab"),
                term("t5", cwd = "/elsewhere"),
            )
        val w = buildWorkspace(terms, layout(space("sp1", tab("tb1", "t1"), folder = "/work")))
        val byKey = w.spaces.associateBy { it.key }
        val sp1 = byKey.getValue("desktop:sp1")
        assertEquals(LayoutRef(null, "sp1"), sp1.layoutRef)
        val refs = sp1.tabs.associate { it.key to it.layoutRef }
        assertEquals(LayoutRef(null, "sp1", "tb1"), refs["tb1"])
        assertNull(refs["tbX"])
        assertNull(refs["all"])
        assertEquals(3, sp1.tabs.size)
        assertNull(byKey.getValue("label:lab").layoutRef)
        assertNull(w.spaces.single { it.source == SpaceSource.Folder }.layoutRef)
    }

    @Test
    fun `a label tab matching a document tab id is that document tab`() {
        val terms = listOf(term("t1"), term("t2", space = "sp1", tab = "tb1"))
        val w = buildWorkspace(terms, layout(space("sp1", tab("tb1", "t1"))))
        val tab =
            w.spaces
                .single()
                .tabs
                .single()
        assertEquals(a("t1", "t2"), tab.panes)
        assertEquals(LayoutRef(null, "sp1", "tb1"), tab.layoutRef)
    }

    @Test
    fun `a document tab with no live terminal keeps the numbers of the tabs after it`() {
        val terms = listOf(term("t1"), term("t2"))
        val w = buildWorkspace(terms, layout(space("sp", tab("a", "t1"), tab("b", "t8"), tab("c", "t2"))))
        assertEquals(
            listOf(1, 3),
            w.spaces
                .single()
                .tabs
                .map { it.number },
        )
        val w2 = buildWorkspace(terms, layout(space("sp", tab("gone", "t8"), tab("b", "t1"), tab("c", "t2"))))
        assertEquals(
            listOf(2, 3),
            w2.spaces
                .single()
                .tabs
                .map { it.number },
        )
    }

    @Test
    fun `label and implicit tabs are numbered after the document tabs`() {
        val terms =
            listOf(
                term("t1", cwd = "/work"),
                term("t2"),
                term("t3", space = "sp", tab = "lbl"),
                term("t4", cwd = "/work"),
            )
        val w = buildWorkspace(terms, layout(space("sp", tab("a", "t1"), tab("b", "t2"), folder = "/work")))
        val tabs = w.spaces.single().tabs
        assertEquals(listOf("a", "b", "lbl", "all"), tabs.map { it.key })
        assertEquals(listOf(1, 2, 3, 4), tabs.map { it.number })
    }

    @Test
    fun `only desktop spaces carry a given name`() {
        val terms = listOf(term("t1"), term("t2", space = "lbl"), term("t3", cwd = "/work/proj"))
        val w = buildWorkspace(terms, layout(space("sp", tab("a", "t1"), name = "api")))
        assertEquals(listOf("api", "", ""), w.spaces.map { it.givenName })
        assertEquals(listOf(SpaceSource.Desktop, SpaceSource.Labels, SpaceSource.Folder), w.spaces.map { it.source })
    }

    @Test
    fun `machine layouts set the machine in layout refs`() {
        val w =
            buildWorkspace(
                listOf(term("rohan/t7")),
                null,
                machineLayouts = mapOf("rohan" to layout(space("rs", tab("rt", "t7")))),
            )
        val sp = w.spaces.single()
        assertEquals(LayoutRef("rohan", "rs"), sp.layoutRef)
        assertEquals(LayoutRef("rohan", "rs", "rt"), sp.tabs.single().layoutRef)
    }

    @Test
    fun `blank or repeated document ids give no layout ref after the first`() {
        val terms = listOf(term("t1"), term("t2"), term("t3"), term("t4"), term("t5"), term("t6"))
        val l =
            layout(
                space("", tab("a", "t1")),
                space("d", tab("", "t2"), tab("x", "t3"), tab("x", "t4")),
                space("d", tab("y", "t5")),
            )
        val w = buildWorkspace(terms, l)
        assertEquals(listOf(null, LayoutRef(null, "d"), null), w.spaces.map { it.layoutRef })
        assertEquals(listOf(null, LayoutRef(null, "d", "x")), w.spaces[1].tabs.map { it.layoutRef })
        assertNull(
            w.spaces[2]
                .tabs
                .single()
                .layoutRef,
        )
    }
}
