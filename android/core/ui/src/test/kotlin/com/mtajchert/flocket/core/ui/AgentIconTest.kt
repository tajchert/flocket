package com.mtajchert.flocket.core.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.AgentCatalog
import com.mtajchert.flocket.core.ui.component.AgentIcon
import com.mtajchert.flocket.core.ui.component.agentIconRes
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentIconTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `AgentIcon describes the agent or Terminal`() {
        rule.setContent {
            FlocketTheme {
                AgentIcon("claude")
                AgentIcon("command-code")
                AgentIcon(null)
                AgentIcon("no-such-agent")
            }
        }
        rule.onNodeWithContentDescription("Claude Code").assertIsDisplayed()
        rule.onNodeWithContentDescription("Command Code").assertIsDisplayed()
        // The null id and the unknown id both read "Terminal".
        rule.onAllNodesWithContentDescription("Terminal").assertCountEquals(2)
    }

    @Test
    fun `AgentIcon with a null description is silent for TalkBack`() {
        rule.setContent {
            FlocketTheme {
                AgentIcon("claude", contentDescription = null)
                AgentIcon(null, contentDescription = null)
            }
        }
        rule.onAllNodesWithContentDescription("Claude Code").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Terminal").assertCountEquals(0)
    }

    @Test
    fun `every catalog agent either has an icon or falls back to the terminal tile`() {
        val missing = AgentCatalog.agents.filter { agentIconRes(it.id) == null }.map { it.id }
        // These have no LobeHub icon; they use the generic tile.
        org.junit.Assert.assertEquals(listOf("mastracode", "crush", "prime-agent"), missing)
        assertNull(agentIconRes(null))
        assertNotNull(agentIconRes("claude"))
    }
}
