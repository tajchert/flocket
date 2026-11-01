package com.mtajchert.flocket.journey

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke step 6g: the app's SSH link dies on the Mac side (its `sshd-session` is killed) with an owner terminal
 * open. The app shows its reconnecting chip, drops keys typed while the link is down (product rule 3),
 * reconnects by itself and typing reaches the core again.
 *
 * The harness listener is stopped before the kill and started again after the keys were typed, so the link
 * stays down long enough to type into it; the control connection is a separate `sshd-session` and survives.
 */
@RunWith(AndroidJUnit4::class)
class SshLinkJourneyTest {
    @get:Rule
    val journey = JourneyRule(host)

    @Test
    fun killedLinkReconnectsWithoutReplayingKeys() {
        val mac = journey.host
        val address = journey.openOwnerTerminal()
        val key = journey.deviceKeyFingerprint()
        val loginsBefore = mac.acceptedLogins()
        val keyLoginsBefore = mac.acceptedLogins(key)
        val tag = System.nanoTime().toString(36)
        val down = "down-$tag"
        val up = "up-$tag"

        try {
            mac.killAppLink(key, holdDown = true)
            journey.compose.waitFor(journey.reconnectingChip(), 10_000)
            typeKeys("echo $down\n")
        } finally {
            mac.startListener()
        }

        journey.awaitOnline(15_000)
        journey.compose.waitForGone(journey.reconnectingChip(), 10_000)
        // Rule 12: the control connection never logs in again, so every new login is the app's.
        poll(5_000, "a new login") { (mac.acceptedLogins() - loginsBefore).takeIf { it >= 1 } }
        check(mac.acceptedLogins(key) > keyLoginsBefore) { "the new logins did not use the app's key" }
        mac.assertNeverReaches(address, down, watchMs = 2_000)
        typeKeys("echo $up\n")
        poll(10_000, "$up in hc read") { mac.read(address).takeIf { "$up\n" in it } }
        mac.assertNeverReaches(address, down, watchMs = 0)
    }

    companion object {
        @JvmField
        @ClassRule
        val host = HostRule()
    }
}
