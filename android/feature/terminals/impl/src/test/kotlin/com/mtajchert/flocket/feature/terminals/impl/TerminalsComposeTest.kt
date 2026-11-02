@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.terminals.impl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.LayoutRef
import com.mtajchert.flocket.core.model.SpaceSource
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalSort
import com.mtajchert.flocket.core.model.Workspace
import com.mtajchert.flocket.core.model.WorkspaceSpace
import com.mtajchert.flocket.core.model.WorkspaceTab
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.core.ui.nav.LocalPaneLayout
import com.mtajchert.flocket.core.ui.nav.PaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TerminalsComposeTest {
    @get:Rule val rule = createComposeRule()

    private val state = buildUiState(SampleData.overview, ListControls(), refreshing = false)

    private fun show(
        state: TerminalsUiState = this.state,
        actions: TerminalsActions = TerminalsActions(),
        layout: PaneLayout = PaneLayout(),
    ) = rule.setContent {
        FlocketTheme(dynamicColor = false) {
            CompositionLocalProvider(LocalPaneLayout provides layout) {
                Box(
                    Modifier.requiredSize(600.dp, 4000.dp),
                ) { TerminalsScreen(state, actions, nowMs = SampleData.NOW_MS) }
            }
        }
    }

    @Test
    fun `without the navigation rail the list shows its New terminal button`() {
        show()
        rule.onAllNodesWithContentDescription("New terminal").assertCountEquals(1)
    }

    @Test
    fun `beside the navigation rail the list drops its own New terminal buttons`() {
        show(layout = PaneLayout(navigationRail = true))
        rule.onAllNodesWithContentDescription("New terminal").assertCountEquals(0)
    }

    @Test
    fun `a sidebar without the rail keeps the top bar New terminal button`() {
        show(layout = PaneLayout(multiPane = true, compactHeight = true))
        rule.onAllNodesWithContentDescription("New terminal").assertCountEquals(1)
    }

    @Test
    fun `filter chips report the chosen filter`() {
        var chosen: TerminalFilter? = null
        show(actions = TerminalsActions(onFilter = { chosen = it }))
        // The priority section "Working" shares the chip label; the chip comes first in the tree.
        rule.onAllNodesWithText("Working")[0].performClick()
        assertEquals(TerminalFilter.Working, chosen)
        rule.onNodeWithText("Finished").performClick()
        assertEquals(TerminalFilter.Finished, chosen)
    }

    @Test
    fun `list shows host headers, the federated machine and the needs attention action`() {
        show()
        rule.onNodeWithText("Studio Mac").assertExists()
        rule.onNodeWithText("Machine rohan").assertExists()
        rule.onAllNodesWithText("Allow running `npm test`?")[0].assertExists()
        rule.onNodeWithText("Review host key").assertExists()
    }

    private val inboxState =
        buildUiState(SampleData.overview, ListControls(sort = TerminalSort.Inbox), refreshing = false)

    @Test
    fun `inbox groups rows by status across hosts with Idle collapsed`() {
        show(inboxState)
        rule.onNodeWithText("Needs you").assertExists()
        val rows = hasContentDescription("Actions for", substring = true)
        val before = rule.onAllNodes(rows).fetchSemanticsNodes().size
        val idle =
            inboxState.groups.flatMap { g -> g.sections.flatMap { it.priority } }.filter {
                it.bucket ==
                    PriorityBucket.Idle
            }
        assertTrue(idle.isNotEmpty())
        // Idle starts collapsed; one tap on its header shows its rows.
        rule.onAllNodesWithText("Idle")[0].performClick()
        assertEquals(before + idle.sumOf { it.rows.size }, rule.onAllNodes(rows).fetchSemanticsNodes().size)
    }

    @Test
    fun `inbox host chips filter and status filter stays in the options menu`() {
        var chosen: TerminalFilter? = null
        show(inboxState, TerminalsActions(onFilter = { chosen = it }))
        rule.onNodeWithText("Studio Mac").performClick()
        rule.onNodeWithContentDescription("List options").performClick()
        rule.onNodeWithText("Needs attention").performClick()
        assertEquals(TerminalFilter.NeedsAttention, chosen)
    }

    @Test
    fun `sort menu switches between Priority and Grouped by space`() {
        var chosen: TerminalSort? = null
        show(actions = TerminalsActions(onSort = { chosen = it }))
        rule.onNodeWithContentDescription("List options").performClick()
        rule.onNodeWithText("Priority").assertExists()
        rule.onNodeWithText("Grouped by space").performClick()
        assertEquals(TerminalSort.Grouped, chosen)
        rule.onNodeWithContentDescription("List options").performClick()
        rule.onNodeWithText("Priority").performClick()
        assertEquals(TerminalSort.Priority, chosen)
    }

    @Test
    fun `unnamed tab shows its number`() {
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
                            WorkspaceTab("1", "agents", listOf(TerminalAddress("t1")), null, null, 1),
                            WorkspaceTab("2", "", listOf(TerminalAddress("t2")), null, null, 2),
                        ),
                        null,
                        1,
                    ),
                ),
            )
        show(buildUiState(SampleData.overview.take(1), ListControls(), refreshing = false) { ws })
        // The place shares the row's subtitle with the agent, so it is matched as a part of it.
        rule.onAllNodesWithText("Flocket · 2", substring = true)[0].assertExists()
        rule.onAllNodesWithText("Flocket · agents", substring = true)[0].assertExists()
        rule.onAllNodesWithText("Flocket · Tab 2", substring = true).assertCountEquals(0)
    }

    @Test
    fun `row menu kill goes through the callback with the right terminal`() {
        var killed: TerminalItem? = null
        show(actions = TerminalsActions(onKill = { killed = it }))
        rule.onAllNodesWithContentDescription("Actions for codex")[0].performClick()
        rule.onNodeWithText("Kill…").performClick()
        assertEquals("t2", killed?.address)
    }

    @Test
    fun `stale rows disable write actions and explain why`() {
        val stale = state.copy(groups = state.groups.filter { it.hostId == HostId("hetzner") })
        var killed = false
        show(stale, TerminalsActions(onKill = { killed = true }))
        rule.onAllNodesWithContentDescription("Actions for claude")[0].performClick()
        rule.onNodeWithText("Read-only until the host reconnects").assertIsDisplayed()
        rule.onNodeWithText("Kill…").assertIsNotEnabled()
        rule.onNodeWithText("Send text…").assertIsNotEnabled()
        assertFalse(killed)
    }

    @Test
    fun `kill dialog needs explicit confirmation`() {
        val item =
            state.groups
                .first()
                .sections
                .first()
                .items
                .first()
        var confirmed = false
        var dismissed = false
        rule.setContent {
            FlocketTheme { KillConfirmDialog(item, onConfirm = { confirmed = true }, onDismiss = { dismissed = true }) }
        }
        rule.onNodeWithText("Kill this terminal?").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
        assertFalse(confirmed)
        rule.onNodeWithText("Kill").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun `new terminal form shows the missing folder error and submits`() {
        var submitted = 0
        val form =
            NewTerminalForm(
                hosts = listOf(HostChoice(HostId("studio"), "Studio Mac", listOf("rohan"), supportsTemplates = false)),
                hostId = HostId("studio"),
                cwd = "",
                showErrors = true,
            )
        rule.setContent { FlocketTheme { NewTerminalContent(form, NewTerminalCallbacks(onSubmit = { submitted++ })) } }
        rule.onNodeWithText("Enter a working directory").assertExists()
        rule.onNodeWithText("rohan").assertExists()
        rule.onNodeWithText("Start").performScrollTo().performClick()
        assertEquals(1, submitted)
    }

    @Test
    fun `empty state offers to start a terminal`() {
        var started = false
        show(
            buildUiState(
                listOf(
                    SampleData.overview.first().copy(session = SampleData.studioOnline.copy(terminals = emptyList())),
                ),
                ListControls(),
                false,
            ),
            TerminalsActions(onNewTerminal = {
                started =
                    true
            }),
        )
        rule.onNodeWithText("No terminals yet").assertIsDisplayed()
        rule.onNodeWithText("Start a terminal").performClick()
        assertTrue(started)
    }

    // ---- rename -------------------------------------------------------------------------------

    private val studio = HostId("studio")
    private val refA = LayoutRef(null, "a")

    /** A desktop space with two document tabs (one named) and an implicit one, and a folder space whose tabs have no layout. */
    private val renameWorkspace =
        Workspace(
            listOf(
                WorkspaceSpace(
                    "desktop:a",
                    "Flocket",
                    null,
                    SpaceSource.Desktop,
                    null,
                    listOf(
                        WorkspaceTab(
                            "1",
                            "agents",
                            listOf(TerminalAddress("t1")),
                            null,
                            null,
                            1,
                            LayoutRef(null, "a", "ta1"),
                        ),
                        WorkspaceTab(
                            "2",
                            "",
                            listOf(TerminalAddress("t2")),
                            null,
                            null,
                            2,
                            LayoutRef(null, "a", "ta2"),
                        ),
                        // The implicit tab of folder-joined terminals: not in the document, no ref.
                        WorkspaceTab("all", "", listOf(TerminalAddress("t5")), null, null, 3),
                    ),
                    null,
                    1,
                    refA,
                    "Flocket",
                ),
                WorkspaceSpace(
                    "folder::/x",
                    "x",
                    "/x",
                    SpaceSource.Folder,
                    null,
                    listOf(
                        WorkspaceTab("b1", "first", listOf(TerminalAddress("t3")), null, null, 1),
                        WorkspaceTab("b2", "second", listOf(TerminalAddress("t4")), null, null, 2),
                    ),
                    null,
                    2,
                ),
            ),
        )

    private fun renameState(sort: TerminalSort) =
        buildUiState(SampleData.overview.take(1), ListControls(sort = sort), refreshing = false) { renameWorkspace }

    @Test
    fun `long-press on a desktop space header opens rename prefilled with its given name`() {
        var asked: List<Any?>? = null
        show(
            renameState(TerminalSort.Grouped),
            TerminalsActions(onRename = { host, ref, current, placeholder ->
                asked =
                    listOf(host, ref, current, placeholder)
            }),
        )
        rule.onNodeWithText("Flocket").performTouchInput { longClick() }
        assertEquals(listOf<Any?>(studio, refA, "Flocket", "Space 1"), asked)
        asked = null
        rule.onNodeWithText("agents").performTouchInput { longClick() }
        assertEquals(listOf<Any?>(studio, LayoutRef(null, "a", "ta1"), "agents", "1"), asked)
    }

    @Test
    fun `no rename on a folder space or the implicit tab`() {
        var asked = 0
        show(renameState(TerminalSort.Grouped), TerminalsActions(onRename = { _, _, _, _ -> asked++ }))
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("first"))
        rule.onNodeWithText("first").performTouchInput { longClick() }
        rule.onNodeWithText("second").performTouchInput { longClick() }
        // The implicit tab of a desktop space shows its number and has no rename.
        // The first "3" is the space's terminal count; the second is the tab label.
        rule.onAllNodesWithText("3", useUnmergedTree = true)[1].performTouchInput { longClick() }
        // Last: without a long-press action the header just toggles collapse on release.
        rule.onNodeWithText("x").performTouchInput { longClick() }
        assertEquals(0, asked)
    }

    @Test
    fun `priority row overflow has Rename tab and Rename space only when refs exist`() {
        val state = renameState(TerminalSort.Priority)
        val addresses =
            state.groups
                .first()
                .sections
                .first()
                .items
                .map { it.address }
        val actionButtons = hasContentDescription("Actions for", substring = true)
        var asked = mutableListOf<LayoutRef>()
        show(state, TerminalsActions(onRename = { _, ref, _, _ -> asked += ref }))

        rule.onAllNodes(actionButtons)[addresses.indexOf("t1")].performClick()
        rule.onNodeWithText("Rename tab…").performClick()
        rule.onAllNodes(actionButtons)[addresses.indexOf("t1")].performClick()
        rule.onNodeWithText("Rename space…").performClick()
        assertEquals(listOf(LayoutRef(null, "a", "ta1"), refA), asked)

        rule.onAllNodes(actionButtons)[addresses.indexOf("t3")].performClick()
        rule.onNodeWithText("Kill…").assertIsDisplayed()
        rule.onAllNodesWithText("Rename tab…").assertCountEquals(0)
        rule.onAllNodesWithText("Rename space…").assertCountEquals(0)
    }

    @Test
    fun `rename entries are disabled while the host is stale`() {
        val offline =
            SampleData.overview.first().let {
                it.copy(
                    session = it.session.copy(connection = ConnectionState.Idle),
                )
            }
        val stale = buildUiState(listOf(offline), ListControls(), false) { renameWorkspace }
        val addresses =
            stale.groups
                .first()
                .sections
                .first()
                .items
                .map { it.address }
        var asked = 0
        show(stale, TerminalsActions(onRename = { _, _, _, _ -> asked++ }))
        rule.onAllNodes(hasContentDescription("Actions for", substring = true))[addresses.indexOf("t1")].performClick()
        rule.onNodeWithText("Rename tab…").assertIsNotEnabled()
        rule.onNodeWithText("Rename space…").assertIsNotEnabled()
        assertEquals(0, asked)
    }
}
