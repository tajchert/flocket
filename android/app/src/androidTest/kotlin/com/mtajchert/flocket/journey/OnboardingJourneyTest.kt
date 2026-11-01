package com.mtajchert.flocket.journey

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.mtajchert.flocket.core.ui.R as CoreUiR
import com.mtajchert.flocket.feature.onboarding.impl.R as OnboardingR
import com.mtajchert.flocket.feature.terminals.impl.R as TerminalsR

/**
 * The only journey that goes through the onboarding UI: from a cleared app to the Terminals list with a
 * generated device key, the harness host added, and its host key verified and trusted.
 */
@Smoke
@RunWith(AndroidJUnit4::class)
class OnboardingJourneyTest {
    @get:Rule
    val journey = JourneyRule(host, JourneyRule.Start.Cleared)

    @Test
    fun onboardsAddsTheMachineAndTrustsItsHostKey() {
        val ui = journey.compose
        val args = journey.host.args
        ui.clickText(journey.string(OnboardingR.string.welcome_get_started))
        ui.clickText(journey.string(OnboardingR.string.prereq_continue))
        ui.clickText(journey.string(OnboardingR.string.key_generate))
        ui.waitFor(hasText(journey.string(OnboardingR.string.key_ready)), 30_000)

        val keyLine = ui.textOf(hasText("ssh-ed25519 AAAA", substring = true))
        journey.host.authorize(checkNotNull(PUBLIC_KEY.find(keyLine)) { "no public key in \"$keyLine\"" }.value)
        ui.clickText(journey.string(OnboardingR.string.key_continue))

        ui.waitFor(hasText(journey.string(OnboardingR.string.host_title)))
        ui.fill(journey.string(CoreUiR.string.form_label), JourneyRule.HOST_LABEL)
        ui.fill(journey.string(CoreUiR.string.form_hostname), args.host)
        ui.fill(journey.string(CoreUiR.string.form_username), args.user)
        ui.click(hasText(journey.string(CoreUiR.string.form_advanced)) and hasClickAction())
        ui.fill(journey.string(CoreUiR.string.form_port), args.port.toString())
        ui.fill(journey.string(CoreUiR.string.form_session), args.hcSession)
        ui.clickText(journey.string(OnboardingR.string.host_save))

        ui.waitFor(hasText(journey.string(CoreUiR.string.trust_title)), 30_000)
        // The dialog groups the fingerprint in blocks of four; compare without whitespace.
        val shown = ui.textOf(hasText("SHA256:", substring = true)).filterNot(Char::isWhitespace)
        assertEquals(args.hostFingerprint, shown)
        ui.clickText(journey.string(CoreUiR.string.trust_confirm))

        ui.waitFor(hasContentDescription(journey.string(TerminalsR.string.new_terminal)), 30_000)
        journey.awaitOnline()
        val pinned = runBlocking { journey.app.hostTrust().pinned(args.host, args.port) }
        assertEquals(args.hostFingerprint, pinned?.fingerprint)
    }

    companion object {
        @JvmField
        @ClassRule
        val host = HostRule()

        private val PUBLIC_KEY = Regex("ssh-ed25519 [A-Za-z0-9+/=]{20,}( [A-Za-z0-9._@-]+)?")
    }
}
