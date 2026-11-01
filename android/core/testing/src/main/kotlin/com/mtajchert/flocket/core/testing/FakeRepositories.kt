package com.mtajchert.flocket.core.testing

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.AutoConnectSettings
import com.mtajchert.flocket.core.data.DeviceKeyRepository
import com.mtajchert.flocket.core.data.HostDraft
import com.mtajchert.flocket.core.data.HostRepository
import com.mtajchert.flocket.core.data.HostSettingsRepository
import com.mtajchert.flocket.core.data.HostTrustRepository
import com.mtajchert.flocket.core.data.PreferencesRepository
import com.mtajchert.flocket.core.data.UserPreferences
import com.mtajchert.flocket.core.model.DeviceKey
import com.mtajchert.flocket.core.model.EffectiveHostSettings
import com.mtajchert.flocket.core.model.Host
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.HostSettingsOverrides
import com.mtajchert.flocket.core.model.PinnedHostKey
import com.mtajchert.flocket.core.model.resolve
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory [HostRepository]. Set [nextError] to make the next write fail. */
class FakeHostRepository(
    initial: List<Host> = emptyList(),
) : HostRepository {
    private val state = MutableStateFlow(initial)
    private var counter = 0
    var nextError: AppError? = null
    val saved = mutableListOf<HostDraft>()
    override val hosts: StateFlow<List<Host>> = state

    override suspend fun host(id: HostId): Host? = state.value.firstOrNull { it.id == id }

    override suspend fun save(draft: HostDraft): AppResult<Host> {
        nextError?.let {
            nextError = null
            return AppResult.Failure(it)
        }
        saved += draft
        val host =
            Host(
                id = draft.id ?: HostId("host-${++counter}"),
                label = draft.label,
                hostname = draft.hostname,
                port = draft.port,
                username = draft.username,
                auth = draft.auth,
                hcPath = draft.hcPath,
                session = draft.session,
            )
        state.update { list ->
            if (list.any { it.id == host.id }) {
                list.map {
                    if (it.id ==
                        host.id
                    ) {
                        host
                    } else {
                        it
                    }
                }
            } else {
                list + host
            }
        }
        return AppResult.Success(host)
    }

    override suspend fun delete(id: HostId): AppResult<Unit> {
        state.update { list -> list.filterNot { it.id == id } }
        return AppResult.Success(Unit)
    }
}

class FakeHostTrustRepository(
    initial: List<PinnedHostKey> = emptyList(),
) : HostTrustRepository {
    private val keys = MutableStateFlow(initial.associateBy { it.hostname to it.port })
    val pinnedKeys: Map<Pair<String, Int>, PinnedHostKey> get() = keys.value

    override suspend fun pinned(
        hostname: String,
        port: Int,
    ): PinnedHostKey? = keys.value[hostname to port]

    override suspend fun pin(key: PinnedHostKey): AppResult<Unit> {
        keys.update { it + ((key.hostname to key.port) to key) }
        return AppResult.Success(Unit)
    }

    override suspend fun forget(
        hostname: String,
        port: Int,
    ): AppResult<Unit> {
        keys.update { it - (hostname to port) }
        return AppResult.Success(Unit)
    }
}

/** Generates deterministic fake ed25519 keys. Set [nextError] to make the next call fail. */
class FakeDeviceKeyRepository(
    initial: List<DeviceKey> = emptyList(),
) : DeviceKeyRepository {
    private val state = MutableStateFlow(initial)
    private var counter = 0
    var nextError: AppError? = null
    override val keys: Flow<List<DeviceKey>> = state

    override suspend fun generate(label: String): AppResult<DeviceKey> = create(label)

    override suspend fun import(
        label: String,
        privateKeyPem: CharArray,
        passphrase: CharArray?,
    ): AppResult<DeviceKey> = create(label)

    override suspend fun delete(alias: String): AppResult<Unit> {
        state.update { list -> list.filterNot { it.alias == alias } }
        return AppResult.Success(Unit)
    }

    private fun create(label: String): AppResult<DeviceKey> {
        nextError?.let {
            nextError = null
            return AppResult.Failure(it)
        }
        val n = ++counter
        val key =
            SampleData.deviceKey.copy(
                alias = "key-$n",
                publicKeyOpenSsh =
                    SampleData.deviceKey.publicKeyOpenSsh.substringBeforeLast(' ') + " $label",
            )
        state.update { it + key }
        return AppResult.Success(key)
    }
}

class FakePreferencesRepository(
    initial: UserPreferences = UserPreferences(),
) : PreferencesRepository {
    private val state = MutableStateFlow(initial)
    override val preferences: StateFlow<UserPreferences> = state

    override suspend fun update(transform: (UserPreferences) -> UserPreferences): AppResult<Unit> {
        state.update(transform)
        return AppResult.Success(Unit)
    }
}

/** In-memory [HostSettingsRepository] over [prefs]; [overridesNow] exposes the current rows to assertions. */
class FakeHostSettingsRepository(
    private val prefs: PreferencesRepository = FakePreferencesRepository(),
    initial: Map<HostId, HostSettingsOverrides> = emptyMap(),
) : HostSettingsRepository {
    private val rows = MutableStateFlow(initial)
    val overridesNow: Map<HostId, HostSettingsOverrides> get() = rows.value

    override fun overrides(hostId: HostId): Flow<HostSettingsOverrides> =
        rows.map { it[hostId] ?: HostSettingsOverrides() }

    override fun effective(hostId: HostId): Flow<EffectiveHostSettings> =
        combine(prefs.preferences, overrides(hostId)) { p, o -> resolve(p.terminal, o) }

    override val customizedHosts: Flow<Set<HostId>> =
        rows.map { r -> r.filterValues { !it.isEmpty }.keys }

    override val autoConnect: Flow<AutoConnectSettings> =
        combine(prefs.preferences, rows) { p, r ->
            AutoConnectSettings(
                p.terminal.autoConnect,
                r
                    .mapNotNull { (k, v) ->
                        v.autoConnect?.let { k to it }
                    }.toMap(),
            )
        }

    override suspend fun update(
        hostId: HostId,
        transform: (HostSettingsOverrides) -> HostSettingsOverrides,
    ): AppResult<Unit> {
        rows.update { r ->
            val next = transform(r[hostId] ?: HostSettingsOverrides())
            if (next.isEmpty) r - hostId else r + (hostId to next)
        }
        return AppResult.Success(Unit)
    }

    override suspend fun resetAll(hostId: HostId): AppResult<Unit> {
        rows.update { it - hostId }
        return AppResult.Success(Unit)
    }

    override suspend fun setFontSize(
        hostId: HostId,
        sizeSp: Float,
    ): AppResult<Unit> {
        if (rows.value[hostId]?.fontSizeSp != null) {
            update(hostId) { it.copy(fontSizeSp = sizeSp) }
        } else {
            prefs.update { it.copy(terminal = it.terminal.copy(fontSizeSp = sizeSp)) }
        }
        return AppResult.Success(Unit)
    }
}
