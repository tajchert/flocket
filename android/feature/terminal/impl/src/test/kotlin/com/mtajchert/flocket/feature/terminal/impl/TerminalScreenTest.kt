package com.mtajchert.flocket.feature.terminal.impl

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.LayoutRef
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.core.ui.nav.LocalPaneLayout
import com.mtajchert.flocket.core.ui.nav.LocalSidebarToggle
import com.mtajchert.flocket.core.ui.nav.PaneLayout
import com.mtajchert.flocket.core.ui.nav.SidebarToggle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalScreenTest {
    @get:Rule val compose = createComposeRule()

    private val live =
        TerminalUiState(
            address = "t1",
            hostLabel = "Studio Mac",
            agentLabel = "claude",
            title = "refactor-auth",
            connection = ConnectionState.Online(SampleData.coreInfo),
            phase = TerminalPhase.Live,
            streamConnected = true,
        )

    private val clicks = mutableListOf<String>()
    private val actions =
        TerminalActions(
            onOpenTerminal = { clicks += "open:$it" },
            onBack = { clicks += "close" },
            onFit = { clicks += "fit" },
            onPaste = { clicks += "paste" },
            onCopy = { clicks += "copy" },
            onInterrupt = { clicks += "interrupt" },
            onKill = { clicks += "kill" },
            onConfirmKill = { clicks += "confirmKill" },
            onDismissKill = { clicks += "dismissKill" },
            onToggleKeyboard = { clicks += "keyboard" },
            onClose = { clicks += "close" },
            onRemove = { clicks += "remove" },
            onReattach = { clicks += "reattach" },
            onClipboardAnswer = { clicks += "clipboard:$it" },
            onRenameTab = { ref, current, placeholder -> clicks += "rename:${ref.tabId}:$current:$placeholder" },
            onRenameChange = { clicks += "text:$it" },
            onRenameSave = { clicks += "save" },
            onRenameDismiss = { clicks += "cancelRename" },
            onReply = { clicks += "reply:${it.badge}" },
            onTopBarHidden = { clicks += "topBarHidden:$it" },
        )

    private fun show(state: TerminalUiState) =
        compose.setContent {
            FlocketTheme(dynamicColor = false) { TerminalScreen(state, actions) { Box(it) } }
        }

    @Test
    fun `toolbar actions call through`() {
        // With the extra keys off the keyboard toggle lives in the top bar.
        show(live.copy(isOwner = true, showExtraKeys = false))
        compose.onNodeWithContentDescription("Show or hide keyboard").performClick()
        listOf("Paste", "Copy screen text", "Interrupt", "Kill").forEach { item ->
            compose.onNodeWithContentDescription("More actions").performClick()
            compose.onNodeWithText(item).performClick()
        }
        assertEquals(listOf("keyboard", "paste", "copy", "interrupt", "kill"), clicks)
    }

    @Test
    fun `another screen taking the size shows a chip that fits on tap`() {
        show(live.copy(isOwner = false))
        compose.onNodeWithText("Sized for another screen").assertExists()
        compose.onNodeWithTag("terminal_size_taken").performClick()
        assertEquals(listOf("fit"), clicks)
    }

    @Test
    fun `fit is offered only while another screen has the size`() {
        show(live.copy(isOwner = true))
        compose.onAllNodesWithText("Fit to screen").assertCountEquals(0)
        compose.onAllNodesWithText("Sized for another screen").assertCountEquals(0)
        compose.onAllNodesWithContentDescriptionCount("Fit to screen", 0)
    }

    @Test
    fun `the size chip is not shown while reconnecting`() {
        show(live.copy(isOwner = false, streamConnected = false))
        compose.onAllNodesWithText("Sized for another screen").assertCountEquals(0)
    }

    @Test
    fun `input actions are disabled while reconnecting`() {
        show(live.copy(streamConnected = false))
        compose.onNodeWithText("Reconnecting…").assertExists()
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Paste").assertIsNotEnabled()
        compose.onNodeWithText("Interrupt").assertIsNotEnabled()
    }

    @Test
    fun `kill asks for confirmation`() {
        show(live.copy(confirmKill = true))
        compose.onNodeWithText("Kill this terminal?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Kill").performClick()
        assertEquals(listOf("dismissKill", "confirmKill"), clicks)
    }

    @Test
    fun `clipboard consent dialog shows the text and answers`() {
        show(live.copy(clipboardRequest = "ssh-ed25519 AAAA"))
        compose.onNodeWithText("Allow clipboard access?").assertExists()
        compose.onNodeWithText("ssh-ed25519 AAAA").assertExists()
        compose.onNodeWithText("Don't copy").performClick()
        compose.onNodeWithText("Copy").performClick()
        assertEquals(listOf("clipboard:false", "clipboard:true"), clicks)
    }

    @Test
    fun `exit banner offers remove and close`() {
        show(live.copy(phase = TerminalPhase.Exited(2, null)))
        compose.onNodeWithText("Exited (code 2)").assertExists()
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Close").performClick()
        assertEquals(listOf("remove", "close"), clicks)
    }

    @Test
    fun `a killed terminal says killed`() {
        show(live.copy(phase = TerminalPhase.Exited(1, null, "killed")))
        compose.onNodeWithText("Killed").assertExists()
    }

    @Test
    fun `a signal end names the signal`() {
        show(live.copy(phase = TerminalPhase.Exited(null, 9, "signal")))
        compose.onNodeWithText("Ended by signal 9").assertExists()
    }

    @Test
    fun `stream end offers reattach and waiting shows progress text`() {
        show(live.copy(phase = TerminalPhase.StreamEnded))
        compose.onNodeWithText("Stream ended").assertExists()
        compose.onNodeWithText("Reattach").performClick()
        assertEquals(listOf("reattach"), clicks)
    }

    private val strip =
        TabStripUi(
            tabs =
                listOf(
                    StripTab("a", "server", null, selected = true, target = "t1"),
                    StripTab("b", "tests", AgentState.Working, selected = false, target = "t2"),
                ),
            panes = emptyList(),
        )

    private fun showMulti(
        sidebarVisible: Boolean,
        tabStrip: TabStripUi? = null,
    ) = compose.setContent {
        CompositionLocalProvider(
            LocalPaneLayout provides PaneLayout(multiPane = true, sidebarVisible = sidebarVisible),
            LocalSidebarToggle provides SidebarToggle { clicks += "sidebar:$it" },
        ) {
            FlocketTheme(dynamicColor = false) { TerminalScreen(live, actions, tabStrip = tabStrip) { Box(it) } }
        }
    }

    @Test
    fun `strip tab long-press opens rename, and only for tabs that have a layout ref`() {
        val renamable =
            TabStripUi(
                tabs =
                    listOf(
                        StripTab(
                            "a",
                            "server",
                            null,
                            selected = true,
                            target = "t1",
                            layoutRef = LayoutRef(null, "s", "ta"),
                        ),
                        StripTab("b", "", AgentState.Working, selected = false, target = "t2", number = 2),
                    ),
                panes = emptyList(),
            )
        showMulti(sidebarVisible = true, tabStrip = renamable)
        compose.onAllNodes(hasTestTag("terminal_tab"))[1].performTouchInput { longClick() }
        assertEquals(listOf("open:t2"), clicks) // no layout ref: long-press is just a tap
        clicks.clear()
        compose.onAllNodes(hasTestTag("terminal_tab"))[0].performTouchInput { longClick() }
        assertEquals(listOf("rename:ta:server:1"), clicks)
    }

    @Test
    fun `rename dialog edits, saves with the keyboard action and cancels`() {
        show(live.copy(rename = RenameUi(LayoutRef(null, "s", "ta"), "1", "old")))
        compose.onNodeWithText("Rename tab").assertExists()
        compose.onNodeWithText("Leave empty to use “1”").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("save", "cancelRename"), clicks)
    }

    @Test
    fun `beside the list the top bar has neither back nor a sidebar button`() {
        // The list hides itself from its own header; the terminal only brings it back.
        showMulti(sidebarVisible = true)
        compose.onAllNodesWithContentDescription("Back").assertCountEquals(0)
        compose.onAllNodes(hasTestTag("terminal_sidebar_toggle")).assertCountEquals(0)
    }

    @Test
    fun `focus mode offers to show the list again`() {
        showMulti(sidebarVisible = false)
        compose.onNodeWithContentDescription("Show terminal list").performClick()
        assertEquals(listOf("sidebar:true"), clicks)
    }

    @Test
    fun `single-pane keeps the back arrow`() {
        show(live)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onAllNodesWithContentDescription("Show terminal list").assertCountEquals(0)
        assertEquals(listOf("close"), clicks)
    }

    @Test
    fun `tab strip opens the tapped tab and is absent without data`() {
        showMulti(sidebarVisible = true, tabStrip = strip)
        compose.onNodeWithText("server").assertExists()
        compose.onNodeWithText("tests").performClick()
        assertEquals(listOf("open:t2"), clicks)
    }

    @Test
    fun `no strip when there is nothing to switch between`() {
        showMulti(sidebarVisible = true, tabStrip = null)
        compose.onAllNodes(hasTestTag("terminal_tab_strip")).assertCountEquals(0)
    }

    @Test
    fun `a blocked agent gets number replies`() {
        show(live.copy(status = AgentStatus.Reported(AgentState.Blocked, "blocked")))
        compose.onAllNodes(hasTestTag("terminal_reply")).assertCountEquals(3)
        compose.onNodeWithContentDescription("Send 2").performClick()
        assertEquals(listOf("reply:2"), clicks)
    }

    @Test
    fun `a working agent gets Esc and its progress line`() {
        show(live.copy(status = AgentStatus.Reported(AgentState.Working, "working", progress = 40)))
        compose.onNodeWithContentDescription("Send Esc to interrupt").performClick()
        compose.onNodeWithTag("terminal_progress").assertExists()
        assertEquals(listOf("reply:Esc"), clicks)
    }

    @Test
    fun `no replies while reconnecting`() {
        show(live.copy(status = AgentStatus.Reported(AgentState.Blocked, "blocked"), streamConnected = false))
        compose.onAllNodes(hasTestTag("terminal_reply")).assertCountEquals(0)
    }

    @Test
    fun `no replies with the extra keys off`() {
        show(live.copy(status = AgentStatus.Reported(AgentState.Blocked, "blocked"), showExtraKeys = false))
        compose.onAllNodes(hasTestTag("terminal_reply")).assertCountEquals(0)
    }

    @Test
    fun `the next needs you pill opens the next blocked terminal`() {
        compose.setContent {
            FlocketTheme(dynamicColor = false) {
                TerminalScreen(live, actions, header = TerminalHeaderUi("api", 1, 2, "t4")) { Box(it) }
            }
        }
        compose.onNodeWithText("api · Studio Mac").assertExists()
        compose.onNodeWithTag("terminal_next_needs_you").performClick()
        assertEquals(listOf("open:t4"), clicks)
    }

    @Test
    fun `the overflow menu hides the top bar`() {
        show(live)
        compose.onAllNodes(hasTestTag("terminal_show_top_bar")).assertCountEquals(0)
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Hide top bar").performClick()
        assertEquals(listOf("topBarHidden:true"), clicks)
    }

    @Test
    fun `full screen drops the bar and strip, keeps the corner, and brings the bar back`() {
        compose.setContent {
            FlocketTheme(dynamicColor = false) {
                TerminalScreen(
                    live.copy(topBarHidden = true),
                    actions,
                    tabStrip = strip,
                    header = TerminalHeaderUi("api", 1, 2, "t4"),
                ) { Box(it) }
            }
        }
        compose.onAllNodesWithText("refactor-auth").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("More actions").assertCountEquals(0)
        compose.onAllNodes(hasTestTag("terminal_tab")).assertCountEquals(0)
        // Other terminals that need the user are still one tap away.
        compose.onNodeWithTag("terminal_next_needs_you").performClick()
        compose.onNodeWithContentDescription("Show top bar").performClick()
        assertEquals(listOf("open:t4", "topBarHidden:false"), clicks)
    }

    @Test
    fun `full screen still labels a host that is not online`() {
        show(live.copy(topBarHidden = true, connection = ConnectionState.Connecting(1), streamConnected = false))
        compose.onNodeWithTag("terminal_show_top_bar").assertExists()
        compose.onNodeWithText("Connecting…").assertExists() // the host's badge, as in the title
        compose.onNodeWithText("Reconnecting…").assertExists()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescriptionCount(
        description: String,
        count: Int,
    ) = onAllNodes(
        androidx.compose.ui.test
            .hasContentDescription(description),
    ).assertCountEquals(count)
}
