package com.mtajchert.flocket.journey

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.mtajchert.flocket.MainActivity
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.HostDraft
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.HostAuth
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.PinnedHostKey
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Per-test setup of a device journey (DECISIONS.md rules 3, 5, 6). Around every test it:
 * 1. kills and removes every terminal in the isolated hc session (`HostControl.killAll`);
 * 2. with [Start.Seeded], seeds an onboarded app with one trusted host through the app's own repositories
 *    (device key generated and authorized on the Mac, host key pinned to `hostFingerprint`, host saved with the
 *    harness port, user and hc session, onboarding marked done);
 * 3. launches [MainActivity] and, when seeded, waits up to [ONLINE_TIMEOUT_MS] for the host to be online;
 * 4. after the test closes the activity and cleans the hc session again.
 *
 * Launch order: seeding must finish before the activity exists, because the activity decides between onboarding
 * and the Terminals list from the first preferences it reads. So the rule uses [createEmptyComposeRule] and
 * launches the activity itself with [ActivityScenario] once seeding is done, instead of an activity rule that
 * would start the activity before any test code runs.
 *
 * The app process is cleared by the runner before every method, so the rule never has to undo seeding.
 *
 * ```
 * companion object { @JvmField @ClassRule val host = HostRule() }
 * @get:Rule val journey = JourneyRule(host)
 * @Test fun x() { journey.compose.clickText(journey.string(R.string.new_terminal_start)) }
 * ```
 */
class JourneyRule(
    private val hostRule: HostRule,
    private val start: Start = Start.Seeded,
) : TestRule {
    enum class Start {
        /** Onboarded, trusted, connected host (the fast path for every journey but onboarding). */
        Seeded,

        /** The app as the runner left it after `pm clear`: onboarding on screen. */
        Cleared,
    }

    /** Drives Compose in the launched activity (see `ComposeJourney.kt` for the helpers). */
    val compose: ComposeTestRule = createEmptyComposeRule()

    /** The Mac side: hc in the isolated session, authorize, adb. */
    val host: HostControl get() = hostRule.control

    /** The app's own singletons (repositories, ConnectionManager). */
    val app: JourneyEntryPoint by lazy {
        EntryPointAccessors.fromApplication(targetContext.applicationContext, JourneyEntryPoint::class.java)
    }

    val targetContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The seeded host's id; null with [Start.Cleared]. */
    var hostId: HostId? = null
        private set

    /** The running activity; recreate or move it through its lifecycle from lifecycle journeys. */
    lateinit var scenario: ActivityScenario<MainActivity>
        private set

    /** A UI label from the app's resources, so copy changes don't break journeys. */
    fun string(
        @StringRes id: Int,
        vararg formatArgs: Any,
    ): String = targetContext.getString(id, *formatArgs)

    /** Waits until the seeded (or the first) host is online; fails after [timeoutMs]. */
    fun awaitOnline(timeoutMs: Long = ONLINE_TIMEOUT_MS) {
        val online =
            runBlocking {
                withTimeoutOrNull(timeoutMs) {
                    app.connections().overview.first { hosts ->
                        hosts.any {
                            (hostId == null || it.host.id == hostId) &&
                                it.session.connection is ConnectionState.Online
                        }
                    }
                }
            }
        checkNotNull(online) {
            "host not online after $timeoutMs ms: ${app.connections().overview.value.map { it.session.connection }}"
        }
    }

    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        compose.apply(
            object : Statement() {
                override fun evaluate() {
                    host.killAll()
                    if (start == Start.Seeded) seed()
                    scenario = ActivityScenario.launch(MainActivity::class.java)
                    try {
                        if (start == Start.Seeded) awaitOnline()
                        base.evaluate()
                    } finally {
                        scenario.close()
                        // Cleanup must not hide the test's own failure; the next test cleans up before it starts.
                        runCatching { host.killAll() }
                    }
                }
            },
            description,
        )

    /** Mirrors what onboarding persists (OnboardingViewModel + pinAndRetry), without the UI. */
    private fun seed() =
        runBlocking {
            val args = host.args
            val key = app.deviceKeys().generate(DEVICE_KEY_LABEL).orFail("generate the device key")
            host.authorize(key.publicKeyOpenSsh)
            app
                .hostTrust()
                .pin(
                    PinnedHostKey(
                        args.host,
                        args.port,
                        host.hostKeyType,
                        args.hostFingerprint,
                        System.currentTimeMillis(),
                    ),
                ).orFail("pin the host key")
            val saved =
                app
                    .hosts()
                    .save(
                        HostDraft(
                            label = HOST_LABEL,
                            hostname = args.host,
                            port = args.port,
                            username = args.user,
                            auth = HostAuth.Key(key.alias),
                            hcPath = null,
                            session = args.hcSession,
                        ),
                    ).orFail("save the host")
            hostId = saved.id
            app
                .preferences()
                .update { it.copy(onboardingCompleted = true, notificationPermissionAsked = true) }
                .orFail("mark onboarding done")
        }

    private fun <T> AppResult<T>.orFail(what: String): T =
        when (this) {
            is AppResult.Success -> value
            is AppResult.Failure -> error("seeding failed to $what: $error")
        }

    companion object {
        const val ONLINE_TIMEOUT_MS = 20_000L

        /** The same label onboarding gives the generated key. */
        const val DEVICE_KEY_LABEL = "flocket-device"
        const val HOST_LABEL = "e2e"
    }
}
