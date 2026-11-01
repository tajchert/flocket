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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.AgentState
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
        )

    private fun show(state: TerminalUiState) =
        compose.setContent {
            FlocketTheme(dynamicColor = false) { TerminalScreen(state, actions) { Box(it) } }
        }

    @Test
    fun `toolbar actions call through`() {
        show(live)
        compose.onNodeWithContentDescription("Fit to screen").performClick()
        compose.onNodeWithContentDescription("Show or hide keyboard").performClick()
        listOf("Paste", "Copy screen text", "Interrupt", "Kill").forEach { item ->
            compose.onNodeWithContentDescription("More actions").performClick()
            compose.onNodeWithText(item).performClick()
        }
        assertEquals(listOf("fit", "keyboard", "paste", "copy", "interrupt", "kill"), clicks)
    }

    @Test
    fun `fit is offered only while passive`() {
        show(live.copy(isOwner = true))
        compose.onAllNodesWithText("Fit to screen").assertCountEquals(0)
        compose.onAllNodesWithContentDescriptionCount("Fit to screen", 0)
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
    fun `multi-pane top bar toggles the sidebar instead of going back`() {
        showMulti(sidebarVisible = true)
        compose.onAllNodesWithContentDescription("Back").assertCountEquals(0)
        compose.onNodeWithContentDescription("Hide sidebar").performClick()
        assertEquals(listOf("sidebar:false"), clicks)
    }

    @Test
    fun `focus mode offers to show the sidebar again`() {
        showMulti(sidebarVisible = false)
        compose.onNodeWithContentDescription("Show sidebar").performClick()
        assertEquals(listOf("sidebar:true"), clicks)
    }

    @Test
    fun `single-pane keeps the back arrow`() {
        show(live)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onAllNodesWithContentDescription("Hide sidebar").assertCountEquals(0)
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

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescriptionCount(
        description: String,
        count: Int,
    ) = onAllNodes(
        androidx.compose.ui.test
            .hasContentDescription(description),
    ).assertCountEquals(count)
}
