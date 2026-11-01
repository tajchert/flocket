package com.mtajchert.flocket.core.testing

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.DeviceKeyRepository
import com.mtajchert.flocket.core.data.HostDraft
import com.mtajchert.flocket.core.data.HostRepository
import com.mtajchert.flocket.core.data.HostTrustRepository
import com.mtajchert.flocket.core.data.PreferencesRepository
import com.mtajchert.flocket.core.data.UserPreferences
import com.mtajchert.flocket.core.model.DeviceKey
import com.mtajchert.flocket.core.model.Host
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.PinnedHostKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
