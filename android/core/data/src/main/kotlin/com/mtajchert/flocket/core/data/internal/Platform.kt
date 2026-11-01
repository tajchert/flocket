package com.mtajchert.flocket.core.data.internal

import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.mtajchert.flocket.core.common.logging.Logger
import com.mtajchert.flocket.core.data.AppForeground
import com.mtajchert.flocket.core.data.NetworkMonitor
import com.mtajchert.flocket.core.data.NetworkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/** Foreground = process lifecycle at least STARTED (screen off or all activities stopped = background). */
internal class ProcessLifecycleForeground : AppForeground {
    override val isForeground: Flow<Boolean> =
        callbackFlow {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            val observer =
                LifecycleEventObserver { _, _ -> trySend(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
            // The observer replays the current state on registration. Must be added on the main thread.
            lifecycle.addObserver(observer)
            awaitClose { lifecycle.removeObserver(observer) }
        }.flowOn(Dispatchers.Main.immediate)
}

internal class ConnectivityNetworkMonitor(
    private val connectivity: ConnectivityManager,
) : NetworkMonitor {
    override val state: Flow<NetworkState> =
        callbackFlow {
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        trySend(NetworkState(true, network.networkHandle))
                    }

                    override fun onLost(network: Network) {
                        trySend(NetworkState(false, null))
                    }
                }
            connectivity.registerDefaultNetworkCallback(callback)
            awaitClose { connectivity.unregisterNetworkCallback(callback) }
        }
}

internal object AndroidLogger : Logger {
    override fun debug(
        tag: String,
        message: () -> String,
    ) {
        Log.d(tag, message())
    }

    override fun info(
        tag: String,
        message: () -> String,
    ) {
        Log.i(tag, message())
    }

    override fun warn(
        tag: String,
        message: () -> String,
        error: Throwable?,
    ) {
        Log.w(tag, message(), error)
    }

    override fun error(
        tag: String,
        message: () -> String,
        error: Throwable?,
    ) {
        Log.e(tag, message(), error)
    }
}
