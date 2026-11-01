@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.terminals.impl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.SpaceSource
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalSort
import com.mtajchert.flocket.core.model.Workspace
import com.mtajchert.flocket.core.model.WorkspaceSpace
import com.mtajchert.flocket.core.model.WorkspaceTab
import com.mtajchert.flocket.core.testing.SampleData
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
    ) = rule.setContent {
        FlocketTheme(dynamicColor = false) {
            Box(Modifier.requiredSize(600.dp, 4000.dp)) { TerminalsScreen(state, actions, nowMs = SampleData.NOW_MS) }
        }
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
        rule.onAllNodesWithText("Needs permission")[0].assertExists()
        rule.onNodeWithText("Review host key").assertExists()
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
        rule.onNodeWithText("Flocket · 2").assertExists()
        rule.onNodeWithText("Flocket · agents").assertExists()
        rule.onAllNodesWithText("Flocket · Tab 2").assertCountEquals(0)
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
}
