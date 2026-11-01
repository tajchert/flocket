package com.mtajchert.flocket.core.data

import android.content.Context
import android.net.ConnectivityManager
import com.mtajchert.flocket.core.common.logging.Logger
import com.mtajchert.flocket.core.common.time.Clock
import com.mtajchert.flocket.core.data.internal.AndroidLogger
import com.mtajchert.flocket.core.data.internal.ConnectionManagerImpl
import com.mtajchert.flocket.core.data.internal.ConnectivityNetworkMonitor
import com.mtajchert.flocket.core.data.internal.ConnectorSshOpener
import com.mtajchert.flocket.core.data.internal.DeviceKeyRepositoryImpl
import com.mtajchert.flocket.core.data.internal.HcPathCache
import com.mtajchert.flocket.core.data.internal.HostConnector
import com.mtajchert.flocket.core.data.internal.HostRepositoryImpl
import com.mtajchert.flocket.core.data.internal.HostSettingsRepositoryImpl
import com.mtajchert.flocket.core.data.internal.HostTrustRepositoryImpl
import com.mtajchert.flocket.core.data.internal.PreferencesRepositoryImpl
import com.mtajchert.flocket.core.data.internal.ProcessLifecycleForeground
import com.mtajchert.flocket.core.data.internal.SshHostConnector
import com.mtajchert.flocket.core.database.HostDao
import com.mtajchert.flocket.core.database.HostSettingsDao
import com.mtajchert.flocket.core.database.PinnedHostKeyDao
import com.mtajchert.flocket.core.datastore.PreferencesDataSource
import com.mtajchert.flocket.core.security.KeyVault
import com.mtajchert.flocket.core.session.CoreSessionFactory
import com.mtajchert.flocket.core.session.DefaultCoreSessionFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

/**
 * Binds the data-layer interfaces. Binds the session layer's DefaultCoreSessionFactory.
 * `ConnectionManager` only starts working once something injects it, so `:app` should inject it at startup.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object DataModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    internal fun provideHostRepositoryImpl(
        hosts: HostDao,
        pins: PinnedHostKeyDao,
    ): HostRepositoryImpl = HostRepositoryImpl(hosts, pins)

    @Provides
    fun provideHostRepository(impl: HostRepositoryImpl): HostRepository = impl

    @Provides
    @Singleton
    fun provideHostTrustRepository(dao: PinnedHostKeyDao): HostTrustRepository = HostTrustRepositoryImpl(dao)

    @Provides
    @Singleton
    fun provideDeviceKeyRepository(vault: KeyVault): DeviceKeyRepository = DeviceKeyRepositoryImpl(vault)

    @Provides
    @Singleton
    fun providePreferencesRepository(source: PreferencesDataSource): PreferencesRepository =
        PreferencesRepositoryImpl(source)

    @Provides
    @Singleton
    fun provideHostSettingsRepository(
        dao: HostSettingsDao,
        preferences: PreferencesRepository,
    ): HostSettingsRepository = HostSettingsRepositoryImpl(dao, preferences)

    @Provides
    @Singleton
    internal fun provideHostConnector(
        hosts: HostRepositoryImpl,
        trust: HostTrustRepository,
        vault: KeyVault,
    ): HostConnector = SshHostConnector(hosts, hosts as HcPathCache, trust, vault, ConnectorSshOpener(), AndroidLogger)

    @Provides
    @Singleton
    fun provideAppForeground(): AppForeground = ProcessLifecycleForeground()

    @Provides
    @Singleton
    fun provideNetworkMonitor(
        @ApplicationContext context: Context,
    ): NetworkMonitor = ConnectivityNetworkMonitor(context.getSystemService(ConnectivityManager::class.java))

    @Provides
    @Singleton
    internal fun provideConnectionManager(
        hosts: HostRepository,
        factory: CoreSessionFactory,
        connector: HostConnector,
        foreground: AppForeground,
        network: NetworkMonitor,
        @ApplicationScope scope: CoroutineScope,
        // Bound by :app (the Android service lives there, next to the Activity it returns to).
        keepAlive: KeepAliveService,
        preferences: PreferencesRepository,
        hostSettings: HostSettingsRepository,
    ): ConnectionManager =
        ConnectionManagerImpl(
            hosts,
            factory,
            connector,
            foreground,
            network,
            scope,
            keepAlive,
            keepAliveEnabled = preferences.preferences.map { it.keepConnectedInBackground },
            autoConnect = hostSettings.autoConnect,
            logger = AndroidLogger,
        )

    @Provides
    @Singleton
    fun provideCoreSessionFactory(
        @ApplicationScope scope: CoroutineScope,
    ): CoreSessionFactory = DefaultCoreSessionFactory(scope, Clock.System, AndroidLogger)

    @Provides
    @Singleton
    fun provideLogger(): Logger = AndroidLogger
}
