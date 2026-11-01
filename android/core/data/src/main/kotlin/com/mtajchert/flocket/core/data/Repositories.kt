package com.mtajchert.flocket.core.data

import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.DeviceKey
import com.mtajchert.flocket.core.model.Host
import com.mtajchert.flocket.core.model.HostAuth
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.PinnedHostKey
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
    /** Terminal font size in sp. */
    val terminalFontSize: Float = 13f,
    val keepScreenOnInTerminal: Boolean = true,
    /** Show the extra-keys row (Esc, Tab, Ctrl, arrows…) above the soft keyboard. */
    val showExtraKeys: Boolean = true,
)

enum class ThemePreference { System, Light, Dark }
