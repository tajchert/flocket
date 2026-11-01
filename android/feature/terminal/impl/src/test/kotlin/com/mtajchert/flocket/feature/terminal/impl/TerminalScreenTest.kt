package com.mtajchert.flocket.feature.terminal.impl

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.testing.SampleData
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
    fun `stream end offers reattach and waiting shows progress text`() {
        show(live.copy(phase = TerminalPhase.StreamEnded))
        compose.onNodeWithText("Stream ended").assertExists()
        compose.onNodeWithText("Reattach").performClick()
        assertEquals(listOf("reattach"), clicks)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescriptionCount(
        description: String,
        count: Int,
    ) = onAllNodes(
        androidx.compose.ui.test
            .hasContentDescription(description),
    ).assertCountEquals(count)
}
