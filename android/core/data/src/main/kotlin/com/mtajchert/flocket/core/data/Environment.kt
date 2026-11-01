package com.mtajchert.flocket.core.data

import com.mtajchert.flocket.core.model.CoreEndpoint
import com.mtajchert.flocket.core.protocol.transport.ByteTransport
import com.mtajchert.flocket.core.session.CoreSession
import com.mtajchert.flocket.core.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Creates the [CoreSession] for one endpoint. Implemented by the session layer; `:app` binds it (core:data
 * deliberately provides no binding). [transportFactory] opens a fresh transport (SSH exec of `hc connect`) for
 * every (re)connect attempt; failures are [AppResult.Failure] with the cause as `AppError`.
 */
fun interface CoreSessionFactory {
    fun create(
        endpoint: CoreEndpoint,
        transportFactory: suspend () -> AppResult<ByteTransport>,
    ): CoreSession
}

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
