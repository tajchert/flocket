package com.mtajchert.flocket.core.model

import com.mtajchert.flocket.core.common.error.AppError

/**
 * Lifecycle of the connection to one endpoint. Exactly one of these per host at a time; hosts
 * are independent (host A reconnecting never blocks host B).
 */
sealed interface ConnectionState {
    /** Not connected and not trying (never connected, user detached, or app in background). */
    data object Idle : ConnectionState

    /** SSH + `hc connect` + hello + subscribe in progress. */
    data class Connecting(
        val attempt: Int,
    ) : ConnectionState

    /** Live: events are flowing; data is authoritative. */
    data class Online(
        val core: CoreInfo,
    ) : ConnectionState

    /**
     * Lost the connection and will retry at [nextAttemptAtMs]. Cached data stays visible but is
     * stale, and writes are disabled.
     */
    data class Reconnecting(
        val attempt: Int,
        val lastError: AppError,
        val nextAttemptAtMs: Long,
    ) : ConnectionState

    /**
     * Stopped trying: needs the user (trust a host key, fix credentials, install/upgrade hc, …).
     * Cached data stays visible as stale.
     */
    data class NeedsAttention(
        val error: AppError,
    ) : ConnectionState
}

val ConnectionState.isOnline: Boolean get() = this is ConnectionState.Online
