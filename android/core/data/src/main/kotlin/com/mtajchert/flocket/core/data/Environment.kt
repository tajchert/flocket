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

/**
 * Port for the short foreground service that keeps open terminals connected for a few minutes while the app
 * is in the background (ADR-0006). `:app` implements it with an Android `shortService`.
 */
interface KeepAliveService {
    /**
     * Starts (or refreshes) the service for [terminals] attached terminal screens. Returns false when the
     * platform refuses the start (e.g. `ForegroundServiceStartNotAllowedException`); never throws.
     */
    fun start(terminals: Int): Boolean

    /** Stops the service if it runs. Safe to call at any time, also before the service started. */
    fun stop()
}
