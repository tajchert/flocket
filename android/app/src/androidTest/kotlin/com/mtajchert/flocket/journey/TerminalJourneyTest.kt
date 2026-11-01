package com.mtajchert.flocket.journey

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.mtajchert.flocket.feature.terminal.impl.R as TerminalR
import com.mtajchert.flocket.feature.terminals.impl.R as TerminalsR

/** The terminal basics on a seeded, connected app: the live list, starting a terminal and typing, kill and remove. */
@Smoke
@RunWith(AndroidJUnit4::class)
class TerminalJourneyTest {
    @get:Rule
    val journey = JourneyRule(host)

    private val ui get() = journey.compose

    @Test
    fun liveTerminalAppearsInList() {
        journey.host.runShell(SHELL_NAME)
        ui.waitFor(hasText(SHELL_NAME, substring = true), 15_000)
    }

    @Test
    fun newTerminalTypingReachesTheCore() {
        ui.clickDescription(journey.string(TerminalsR.string.new_terminal))
        ui.clickText(journey.string(TerminalsR.string.new_terminal_start))
        ui.waitFor(hasContentDescription(journey.string(TerminalR.string.terminal_action_more)), 15_000)
        // The session started empty (JourneyRule.killAll), so the app's terminal is the only one.
        val address =
            poll(10_000, "the new terminal in hc ls") {
                journey.host
                    .terminals()
                    .singleOrNull()
                    ?.address
            }

        // Key events into the focused terminal view, the same path as `adb shell input text`.
        InstrumentationRegistry.getInstrumentation().sendStringSync("echo hello-flocket\n")
        poll(10_000, "hello-flocket in hc read") { journey.host.read(address).takeIf { "hello-flocket\n" in it } }
    }

    @Test
    fun killAndRemove() {
        val address = journey.host.runShell(SHELL_NAME)
        ui.click(hasText(SHELL_NAME, substring = true) and hasClickAction(), 15_000)

        ui.clickDescription(journey.string(TerminalR.string.terminal_action_more), 15_000)
        ui.clickText(journey.string(TerminalR.string.terminal_action_kill))
        ui.waitFor(hasText(journey.string(TerminalR.string.terminal_kill_title)))
        ui.clickText(journey.string(TerminalR.string.terminal_kill_confirm))
        ui.clickText(journey.string(TerminalR.string.terminal_remove), 15_000)

        ui.waitFor(hasContentDescription(journey.string(TerminalsR.string.new_terminal)), 15_000)
        // Remove deletes it from the core, finished terminals included.
        poll(10_000, "$address removed from hc ls --all") {
            Unit.takeIf { journey.host.terminals(all = true).none { it.address == address } }
        }
    }

    companion object {
        @JvmField
        @ClassRule
        val host = HostRule()

        private const val SHELL_NAME = "macsh"
    }
}
