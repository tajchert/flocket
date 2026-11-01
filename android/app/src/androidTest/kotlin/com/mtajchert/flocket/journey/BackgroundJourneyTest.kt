package com.mtajchert.flocket.journey

import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E_SMOKE 6h, 6j, 6k and the return half of 6d (ADR-0006), short versions. With a terminal open, HOME keeps
 * the link through the keep-alive service; from the list, or with "Keep connected in background" off, the app
 * detaches at once and reconnects on return. The keep-alive window's end (6i, ~3 min) is not waited for here.
 * The app is brought back like Recents does ([JourneyRule.returnToApp]), so the same activity and ViewModels return.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundJourneyTest {
    @get:Rule
    val journey = JourneyRule(host)

    @Test
    fun terminalOpenStaysConnectedInBackground() {
        val address = journey.host.runShell(SHELL_NAME)
        val owner = journey.openAsOwner(SHELL_NAME, address)

        val reconnects =
            journey.reconnectsDuring {
                pressHome()
                // Past the ~5 s after which Android 15+ blocks a background app's network without the service.
                poll(3_000, "the keep-alive service in the foreground") { Unit.takeIf { keepAliveForeground() } }
                journey.assertStaysOnline(BACKGROUND_MS, "the keep-alive must hold the link in background")
                journey.returnToApp()
                journey.awaitTerminalScreen()
                journey.typeAndExpect(address, "back-typing")
            }

        assertEquals("reconnects across background", 0, reconnects)
        assertEquals("owner size after return", owner, journey.host.size(address))
    }

    @Test
    fun listDetachesAtOnceAndReconnectsOnReturn() {
        journey.host.runShell(SHELL_NAME)
        journey.compose.waitFor(hasText(SHELL_NAME, substring = true), 15_000)

        val reconnects =
            journey.reconnectsDuring {
                pressHome()
                journey.awaitDetached("no terminal open: HOME must detach at once")
                assertFalse("no keep-alive without a terminal", keepAliveForeground())
                journey.returnToApp()
                journey.awaitOnline()
            }

        assertTrue("one reconnect on return, got $reconnects", reconnects >= 1)
        journey.compose.waitFor(hasText(SHELL_NAME, substring = true))
    }

    @Test
    fun keepAliveOffDetachesAtOnceAndReconnectsOnReturn() {
        runBlocking { journey.app.preferences().update { it.copy(keepConnectedInBackground = false) } }
        val address = journey.host.runShell(SHELL_NAME)
        journey.openAsOwner(SHELL_NAME, address)

        val reconnects =
            journey.reconnectsDuring {
                pressHome()
                journey.awaitDetached("keep-alive off: HOME must detach at once")
                assertFalse("no keep-alive when the setting is off", keepAliveForeground())
                journey.host.awaitSize(address, "the viewer to leave after the detach") { it == HEADLESS }
                journey.returnToApp()
                journey.awaitOnline()
                journey.awaitTerminalScreen()
                // Online is the host link; the terminal re-attaches just after it. Keys sent before that are dropped
                // by design (product rule 3), so wait until the core sees the phone as the owner again.
                journey.host.awaitSize(address, "the phone to re-attach as owner") { it != HEADLESS }
                journey.typeAndExpect(address, "reattached-typing")
            }

        assertTrue("one reconnect on return, got $reconnects", reconnects >= 1)
    }

    companion object {
        @JvmField
        @ClassRule
        val host = HostRule()

        private const val SHELL_NAME = "macsh"
        private const val BACKGROUND_MS = 8_000L
    }
}
