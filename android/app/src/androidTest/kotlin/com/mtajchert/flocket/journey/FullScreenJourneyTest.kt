package com.mtajchert.flocket.journey

import androidx.compose.ui.test.hasContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.mtajchert.flocket.feature.terminal.impl.R as TerminalR

/**
 * Full-screen terminal: "Hide top bar" gives the rows of the top bar to the terminal, and the corner button gives
 * them back. Checked on the core: the owner phone's grid grows taller (same width) and returns to the first size.
 */
@RunWith(AndroidJUnit4::class)
class FullScreenJourneyTest {
    @get:Rule
    val journey = JourneyRule(host)

    private val ui get() = journey.compose

    @Test
    fun hidingTheTopBarGivesTheTerminalItsRows() {
        val address = journey.host.runShell(SHELL_NAME)
        val withBar = journey.openAsOwner(SHELL_NAME, address)
        val more = hasContentDescription(journey.string(TerminalR.string.terminal_action_more))
        val showBar = journey.string(TerminalR.string.terminal_action_show_top_bar)

        ui.clickDescription(journey.string(TerminalR.string.terminal_action_more))
        ui.clickText(journey.string(TerminalR.string.terminal_action_hide_top_bar))
        // Wait on the UI before the core: the test clock drives recomposition only while the test syncs with it.
        ui.waitFor(hasContentDescription(showBar))
        ui.waitForGone(more)
        val full = journey.host.awaitStableSize(address, "more rows without the top bar") { it.rows > withBar.rows }
        assertEquals("width without the top bar", withBar.cols, full.cols)
        journey.typeAndExpect(address, "fullscreen-typing")

        ui.clickDescription(showBar)
        ui.waitFor(more)
        journey.host.awaitStableSize(address, "the first grid back with the top bar") { it == withBar }
    }

    companion object {
        @JvmField
        @ClassRule
        val host = HostRule()

        private const val SHELL_NAME = "macsh"
    }
}
