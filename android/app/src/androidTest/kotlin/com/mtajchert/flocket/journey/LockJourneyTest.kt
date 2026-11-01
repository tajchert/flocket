package com.mtajchert.flocket.journey

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E_SMOKE 6c' (ADR-0006): with a terminal open, locking the screen keeps the link through the keep-alive
 * service, so unlocking needs no reconnect, the phone stays the size owner and typing works. The test ends with
 * the screen awake and unlocked (DECISIONS.md rule 11).
 */
@RunWith(AndroidJUnit4::class)
class LockJourneyTest {
    @get:Rule
    val journey = JourneyRule(host)

    @After
    fun wake() {
        unlockScreen()
    }

    @Test
    fun lockAndUnlockKeepsTheTerminalConnected() {
        val address = journey.host.runShell(SHELL_NAME)
        val owner = journey.openAsOwner(SHELL_NAME, address)

        val reconnects =
            journey.reconnectsDuring {
                uiDevice.sleep()
                journey.assertStaysOnline(LOCKED_MS, "the keep-alive must hold the link while locked")
                unlockScreen()
                journey.awaitTerminalScreen()
                journey.typeAndExpect(address, "unlocked-typing")
            }

        assertEquals("reconnects across lock", 0, reconnects)
        assertEquals("owner size after unlock", owner, journey.host.size(address))
    }

    companion object {
        @JvmField
        @ClassRule
        val host = HostRule()

        private const val SHELL_NAME = "macsh"
        private const val LOCKED_MS = 8_000L
    }
}
