package com.mtajchert.flocket.feature.hosts.api

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Top-level hosts list (list pane on wide windows). */
@Serializable
data object HostsRoute : NavKey

/** One host's detail (detail pane on wide windows). [hostId] is `HostId.value`. */
@Serializable
data class HostDetailRoute(
    val hostId: String,
) : NavKey

/** Add ([hostId] null) or edit a host. Full-screen. */
@Serializable
data class HostEditRoute(
    val hostId: String? = null,
) : NavKey
