package com.mtajchert.flocket.journey

import com.mtajchert.flocket.core.data.ConnectionManager
import com.mtajchert.flocket.core.data.DeviceKeyRepository
import com.mtajchert.flocket.core.data.HostRepository
import com.mtajchert.flocket.core.data.HostTrustRepository
import com.mtajchert.flocket.core.data.PreferencesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The app's own singletons, for the device journeys in `src/androidTest` (`JourneyRule` seeds a host through
 * them instead of the onboarding UI). It lives in the debug source set so the app's Hilt aggregation sees it
 * without Hilt processing in the test APK; release builds don't contain it.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface JourneyEntryPoint {
    fun deviceKeys(): DeviceKeyRepository

    fun hostTrust(): HostTrustRepository

    fun hosts(): HostRepository

    fun preferences(): PreferencesRepository

    fun connections(): ConnectionManager
}
