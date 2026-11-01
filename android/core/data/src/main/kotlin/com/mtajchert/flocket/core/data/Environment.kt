package com.mtajchert.flocket.core.data

import kotlinx.coroutines.flow.Flow

/** Whether the app is in the foreground (process lifecycle at least STARTED). Replays the current value. */
interface AppForeground {
    val isForeground: Flow<Boolean>
}

/** Connectivity of the default network. Replays the current value. */
interface NetworkMonitor {
    val state: Flow<NetworkState>
}

/** [networkId] changes when the default network changes (Wi-Fi to cellular); null when offline. */
data class NetworkState(
    val isAvailable: Boolean,
    val networkId: Long? = null,
)
