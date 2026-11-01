package com.mtajchert.flocket.feature.terminal.api

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The interactive terminal screen (detail pane on wide windows). [hostId] is `HostId.value`;
 * [address] is the core's opaque terminal address, e.g. `t7` or `rohan/t7`.
 */
@Serializable
data class TerminalRoute(
    val hostId: String,
    val address: String,
) : NavKey
