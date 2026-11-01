package com.mtajchert.flocket.journey

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke step 6e: the device loses its network with an owner terminal open. The app shows its reconnecting chip,
 * drops keys typed while offline (product rule 3), reconnects by itself once the network is back, and typing
 * reaches the core again.
 *
 * Wi-Fi and mobile data off is what cuts the emulator's link: Android removes the routes (`connect: Network is
 * unreachable`) and destroys the sockets, so the control connection drops as well. The network is therefore
 * toggled on the device ([networkOff], [networkOn]) and the Mac is checked through a fresh [HostControl] once
 * it is back. The app's own logins are counted by its device key fingerprint, so the fresh control login does
 * not count as a reconnect.
 */
@RunWith(AndroidJUnit4::class)
class NetworkJourneyTest {
    @get:Rule
    val journey = JourneyRule(host)

    @Test
    fun networkLossReconnectsWithoutReplayingKeys() {
        val address = journey.openOwnerTerminal()
        val key = journey.deviceKeyFingerprint()
        val loginsBefore = journey.host.acceptedLogins(key)
        val tag = System.nanoTime().toString(36)
        val offline = "offline-$tag"
        val online = "online-$tag"

        try {
            networkOff()
            journey.compose.waitFor(journey.reconnectingChip(), 10_000)
            typeKeys("echo $offline\n")
        } finally {
            networkOn()
        }

        journey.awaitOnline(15_000)
        journey.compose.waitForGone(journey.reconnectingChip(), 10_000)
        HostControl.connect(journey.host.args).use { mac ->
            try {
                poll(5_000, "a new login with the app's key") {
                    (mac.acceptedLogins(key) - loginsBefore).takeIf { it >= 1 }
                }
                mac.assertNeverReaches(address, offline, watchMs = 2_000)
                typeKeys("echo $online\n")
                poll(10_000, "$online in hc read") { mac.read(address).takeIf { "$online\n" in it } }
                // Anything queued from before would have arrived ahead of the later keys.
                mac.assertNeverReaches(address, offline, watchMs = 0)
            } finally {
                // The rule's own cleanup uses the dropped control connection.
                runCatching { mac.killAll() }
            }
        }
    }

    companion object {
        @JvmField
        @ClassRule
        val host = HostRule()
    }
}
