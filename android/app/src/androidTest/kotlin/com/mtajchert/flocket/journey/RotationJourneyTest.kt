package com.mtajchert.flocket.journey

import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Ignore
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
     * Expected (E2E_SMOKE 6a, and ADR-0007 lists only key, tap, chip and return as reclaiming the size): a
     * passive phone keeps the other viewer's size across a rotation. Observed (hc 0.1.16, API 36, 2026-11-02):
     * the Mac viewer holds t18 at 100x40, the phone is passive; after `setOrientationLeft` `hc get` says 79x11
     * (the phone's landscape grid) within 1.5 s, with no reconnect (one `CoreSession: online` in logcat, no
     * new `Accepted publickey`). Cause: `TerminalSurface` calls `TerminalController.setVisible(false/true)` on
     * the activity's stop/start, which a configuration change also goes through, and `setVisible` treats the
     * second call as "came back" and reclaims the size (`reclaimIfTaken`). Either rotation should not count as
     * coming back, or ADR-0007 should say it does (then flip this assertion).
     */
    @Ignore("bug: rotation reclaims the size from another viewer (setVisible treats a config change as a return)")
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
