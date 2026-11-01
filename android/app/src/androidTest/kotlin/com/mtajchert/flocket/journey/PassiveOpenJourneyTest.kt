package com.mtajchert.flocket.journey

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E_SMOKE step 5, size ownership on open. Since ADR-0007 every open is the size owner: a headless terminal
 * (200x120, no viewer) opened from the list is fitted to the phone at once. The phone is passive only after
 * another viewer takes the size (Herdr desktop, here [MacViewer]): it then leaves that size alone until the
 * first key typed on the phone takes it back.
 */
@Smoke
@RunWith(AndroidJUnit4::class)
class PassiveOpenJourneyTest {
    @get:Rule
    val journey = JourneyRule(host)

    @Test
    fun openFitsTheHeadlessTerminalToThePhone() {
        val address = journey.host.runShell(SHELL_NAME)
        assertEquals(HEADLESS, journey.host.size(address))

        journey.openAsOwner(SHELL_NAME, address)
    }

    @Test
    fun anotherViewerKeepsTheSizeUntilTheFirstKey() {
        val address = journey.host.runShell(SHELL_NAME)
        val phone = journey.openAsOwner(SHELL_NAME, address)

        val desktop = MacViewer.attach(journey.host, address)
        journey.host.assertSizeStays(address, desktop.size, why = "the passive phone must not take the size back")

        journey.typeAndExpect(address, "first-key")
        journey.host.awaitSize(address, "the first key to fit it to the phone again") { it == phone }
    }

    companion object {
        @JvmField
        @ClassRule
        val host = HostRule()

        private const val SHELL_NAME = "macsh"
    }
}
