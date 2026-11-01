package com.mtajchert.flocket.demo

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.data.ConnectionManager
import com.mtajchert.flocket.core.data.DeviceKeyRepository
import com.mtajchert.flocket.core.data.HostRepository
import com.mtajchert.flocket.core.data.HostTrustRepository
import com.mtajchert.flocket.core.data.PreferencesRepository
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.session.SessionState
import com.mtajchert.flocket.core.testing.FakeConnectionManager
import com.mtajchert.flocket.core.testing.FakeDeviceKeyRepository
import com.mtajchert.flocket.core.testing.FakeHostRepository
import com.mtajchert.flocket.core.testing.FakeHostTrustRepository
import com.mtajchert.flocket.core.testing.FakePreferencesRepository
import com.mtajchert.flocket.core.testing.SampleData
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * TODO(core:data): TEMPORARY, debug builds only. Binds in-memory fakes from :core:testing so the UI can be run
 * before the real :core:data implementations exist. Delete this file (and the debugImplementation of
 * :core:testing in app/build.gradle.kts) when :core:data provides the bindings.
 *
 * Behaviour: a new host first asks to verify its host key; after it is pinned the session goes online with
 * sample terminals.
 */
@Module
@InstallIn(SingletonComponent::class)
object DemoDataModule {
    private val hostRepository = FakeHostRepository()
    private val trustRepository = FakeHostTrustRepository()
    private val connectionManager =
        FakeConnectionManager(hostRepository) { session ->
            fun evaluate() {
                val host = hostRepository.hosts.value.firstOrNull { it.id == session.endpoint.hostId } ?: return
                val pinned = trustRepository.pinnedKeys[host.hostname to host.port]
                session.update {
                    if (pinned == null) {
                        it.copy(
                            connection =
                                ConnectionState.NeedsAttention(
                                    SampleData.hostKeyUnknown.copy(host = host.hostname),
                                ),
                        )
                    } else {
                        SessionState(
                            endpoint = it.endpoint,
                            connection = ConnectionState.Online(SampleData.coreInfo),
                            core = SampleData.coreInfo,
                            terminals =
                                SampleData.terminals.map { t ->
                                    t.copy(ref = t.ref.copy(endpoint = it.endpoint))
                                },
                            machines = SampleData.machines,
                            confirmedAtMs = System.currentTimeMillis(),
                        )
                    }
                }
            }
            session.onStart = { evaluate() }
            session.onRetry = { evaluate() }
            session.start()
        }
    private val deviceKeys = FakeDeviceKeyRepository()
    private val preferences = FakePreferencesRepository()

    @Provides @Singleton
    fun hostRepository(): HostRepository = hostRepository

    @Provides @Singleton
    fun hostTrustRepository(): HostTrustRepository = trustRepository

    @Provides @Singleton
    fun deviceKeyRepository(): DeviceKeyRepository = deviceKeys

    @Provides @Singleton
    fun preferencesRepository(): PreferencesRepository = preferences

    @Provides @Singleton
    fun connectionManager(): ConnectionManager = connectionManager
}
