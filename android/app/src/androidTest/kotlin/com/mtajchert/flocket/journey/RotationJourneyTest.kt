package com.mtajchert.flocket.journey

import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E_SMOKE step 6a: rotation is a configuration change, not a reconnect. The owner's size follows the new grid,
 * a passive phone leaves another viewer's size alone, and the list keeps its rows. Every test ends in natural
 * orientation with rotation frozen (the state the runner resets to, DECISIONS.md rule 11).
 */
@RunWith(AndroidJUnit4::class)
class RotationJourneyTest {
    @get:Rule
    val journey = JourneyRule(host)

    @After
    fun restoreOrientation() {
        uiDevice.setOrientationNatural()
    }

    @Test
    fun ownerTerminalFollowsTheRotatedGrid() {
        val address = journey.host.runShell(SHELL_NAME)
        val portrait = journey.openAsOwner(SHELL_NAME, address)

        val reconnects =
            journey.reconnectsDuring {
                uiDevice.setOrientationLeft()
                val landscape =
                    journey.host.awaitStableSize(address, "the size to follow the landscape grid") { it != portrait }
                assertTrue("landscape $landscape is wider than portrait $portrait", landscape.cols > portrait.cols)
                journey.awaitTerminalScreen()
                journey.typeAndExpect(address, "rotated-typing")
            }

        assertEquals("reconnects during rotation", 0, reconnects)
    }

    /**
     * A passive phone keeps the other viewer's size across a rotation: a configuration change is not coming back
     * to the screen (ADR-0007, amended 2026-11-02). Regression: `TerminalSurface` reported the activity's
     * stop/start on rotation as a return and `TerminalController.setVisible` reclaimed the size (the Mac viewer's
     * 100x40 became the phone's 79x11 landscape grid); the surface now passes `isChangingConfigurations`.
     */
    @Test
    fun passiveTerminalKeepsTheOtherViewersSize() {
        val address = journey.host.runShell(SHELL_NAME)
        journey.openAsOwner(SHELL_NAME, address)
        val desktop = MacViewer.attach(journey.host, address)

        val reconnects =
            journey.reconnectsDuring {
                uiDevice.setOrientationLeft()
                journey.awaitTerminalScreen()
                journey.host.assertSizeStays(address, desktop.size, why = "a passive phone must not resize on rotation")
            }

        assertEquals("reconnects during rotation", 0, reconnects)
    }

    @Test
    fun listSurvivesRotation() {
        journey.host.runShell(SHELL_NAME)
        journey.compose.waitFor(hasText(SHELL_NAME, substring = true), 15_000)

        val reconnects =
            journey.reconnectsDuring {
                uiDevice.setOrientationLeft()
                uiDevice.waitForIdle()
                journey.compose.waitFor(hasText(SHELL_NAME, substring = true))
                journey.assertStaysOnline(1_000, "the host must stay online across rotation")
            }

        assertEquals("reconnects during rotation", 0, reconnects)
    }

    companion object {
        @JvmField
        @ClassRule
        val host = HostRule()

        private const val SHELL_NAME = "macsh"
    }
}
