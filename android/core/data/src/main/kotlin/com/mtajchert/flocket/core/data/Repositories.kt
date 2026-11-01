package com.mtajchert.flocket.core.data

import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.DeviceKey
import com.mtajchert.flocket.core.model.EffectiveHostSettings
import com.mtajchert.flocket.core.model.Host
import com.mtajchert.flocket.core.model.HostAuth
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.HostSetting
import com.mtajchert.flocket.core.model.HostSettingsOverrides
import com.mtajchert.flocket.core.model.PinnedHostKey
import com.mtajchert.flocket.core.model.TerminalPreferences
import com.mtajchert.flocket.core.session.CoreSession
import com.mtajchert.flocket.core.session.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * Public data-layer contracts used by features. Features depend on these interfaces only (never
 * on implementations, databases or SSH). Fakes for tests and previews live in :core:testing.
 * All suspend functions return AppResult and never throw (except cancellation).
 */

/** Configured hosts (source of truth: the local database). */
interface HostRepository {
    val hosts: Flow<List<Host>>

    suspend fun host(id: HostId): Host?

    /** Create (when [HostDraft.id] is null) or update a host. Validates input. */
    suspend fun save(draft: HostDraft): AppResult<Host>

    /** Delete a host, its pinned host keys' association and its session. Keys stay in the vault. */
    suspend fun delete(id: HostId): AppResult<Unit>
}

data class HostDraft(
    val id: HostId? = null,
    val label: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val auth: HostAuth,
    val hcPath: String? = null,
    val session: String? = null,
)

/** SSH host keys the user has explicitly trusted (TOFU with confirmation). */
interface HostTrustRepository {
    suspend fun pinned(
        hostname: String,
        port: Int,
    ): PinnedHostKey?

    /** Called only after the user confirmed the fingerprint shown by `AppError.HostKeyUnknown`. */
    suspend fun pin(key: PinnedHostKey): AppResult<Unit>

    suspend fun forget(
        hostname: String,
        port: Int,
    ): AppResult<Unit>
}

/** Device SSH keys (private halves stay encrypted in :core:security). */
interface DeviceKeyRepository {
    val keys: Flow<List<DeviceKey>>

    suspend fun generate(label: String): AppResult<DeviceKey>

    suspend fun import(
        label: String,
        privateKeyPem: CharArray,
        passphrase: CharArray?,
    ): AppResult<DeviceKey>

    suspend fun delete(alias: String): AppResult<Unit>
}

/**
 * Owns one [CoreSession] per host and their lifecycle: connects hosts while the app is in the
 * foreground, detaches after a background grace period, reconnects on network changes. Hosts are
 * independent: one host failing never affects another.
 */
interface ConnectionManager {
    /** Every configured host with its live/stale session state, in the user's host order. */
    val overview: StateFlow<List<HostOverview>>

    /** The session for [hostId], created on demand. Null if the host doesn't exist. */
    fun session(hostId: HostId): CoreSession?

    /**
     * Supplies a password for a host using [HostAuth.Password] and retries. The password is kept in
     * memory for this process only.
     */
    fun providePassword(
        hostId: HostId,
        password: CharArray,
    )

    /**
     * A terminal screen is attached under [token] (ADR-0006): while at least one is held, leaving the app
     * keeps the sessions connected through a short foreground service instead of detaching at once.
     * Idempotent per token; pair with [releaseTerminal].
     */
    fun holdTerminal(token: Any) = Unit

    /** The screen behind [token] detached or closed. */
    fun releaseTerminal(token: Any) = Unit

    /**
     * The user asked for this host to be connected (opened it, pulled to refresh, opened one of its terminals,
     * tapped Connect). Needed for hosts whose `autoConnect` is off: they stay Idle until then. Idempotent;
     * once asked, the host reconnects whenever the app returns to the foreground during this process.
     */
    fun connect(hostId: HostId) = Unit

    /** The keep-alive service ended on its own (timeout, or it could not go foreground): detach now. */
    fun keepAliveEnded() = Unit
}

data class HostOverview(
    val host: Host,
    val session: SessionState,
)

/** User preferences (DataStore). */
interface PreferencesRepository {
    val preferences: Flow<UserPreferences>

    suspend fun update(transform: (UserPreferences) -> UserPreferences): AppResult<Unit>
}

data class UserPreferences(
    val onboardingCompleted: Boolean = false,
    val theme: ThemePreference = ThemePreference.System,
    val dynamicColor: Boolean = true,
    /** App-level terminal defaults; hosts may override them (see [HostSettingsRepository]). */
    val terminal: TerminalPreferences = TerminalPreferences(),
    /** The terminal screen already asked for POST_NOTIFICATIONS once (Android 13+); never nag again. */
    val notificationPermissionAsked: Boolean = false,
    /**
     * App-level opt-out of the background keep-alive (ADR-0006). Off: leaving the app detaches at once,
     * with no foreground service and no notification.
     */
    val keepConnectedInBackground: Boolean = true,
)

/**
 * Per-host overrides of the app's terminal defaults (Room `host_settings`) and the resulting effective
 * settings. A host without a row simply uses the app defaults.
 */
interface HostSettingsRepository {
    /** The host's raw overrides (empty when it has none). */
    fun overrides(hostId: HostId): Flow<HostSettingsOverrides>

    /** App defaults with the host's overrides applied. Emits again when either side changes. */
    fun effective(hostId: HostId): Flow<EffectiveHostSettings>

    /** Hosts that have at least one override (for the "Custom" badge). */
    val customizedHosts: Flow<Set<HostId>>

    /** Effective `autoConnect` for every host (ConnectionManager). */
    val autoConnect: Flow<AutoConnectSettings>

    suspend fun update(
        hostId: HostId,
        transform: (HostSettingsOverrides) -> HostSettingsOverrides,
    ): AppResult<Unit>

    /** Back to the app default for one setting. */
    suspend fun reset(
        hostId: HostId,
        setting: HostSetting,
    ): AppResult<Unit> = update(hostId) { it.cleared(setting) }

    /** Drop every override of the host. */
    suspend fun resetAll(hostId: HostId): AppResult<Unit>

    /**
     * Pinch-zoom result: writes the host's font override when it has one, else the app default
     * (so a pinch on a host that follows the app changes it everywhere).
     */
    suspend fun setFontSize(
        hostId: HostId,
        sizeSp: Float,
    ): AppResult<Unit>
}

/** [default] is the app-level `autoConnect`; [overrides] only holds hosts that set their own. */
data class AutoConnectSettings(
    val default: Boolean = true,
    val overrides: Map<HostId, Boolean> = emptyMap(),
) {
    fun isEnabled(hostId: HostId): Boolean = overrides[hostId] ?: default
}

enum class ThemePreference { System, Light, Dark }
