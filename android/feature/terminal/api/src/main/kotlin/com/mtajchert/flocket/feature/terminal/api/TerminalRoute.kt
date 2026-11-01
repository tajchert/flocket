package com.mtajchert.flocket.feature.terminal.api

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The interactive terminal screen (detail pane on wide windows). [hostId] is `HostId.value`;
 * [address] is the core's opaque terminal address, e.g. `t7` or `rohan/t7`.
 * Opening it always fits the terminal to this screen (size owner, protocol/SPEC.md §7.5).
 */
@Serializable
data class TerminalRoute(
    val hostId: String,
    val address: String,
) : NavKey {
    /** Same terminal, regardless of how it was opened. */
    fun isSameTerminal(other: TerminalRoute?): Boolean =
        other != null && other.hostId == hostId && other.address == address
}
