package com.mtajchert.flocket.feature.terminal.api

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The interactive terminal screen (detail pane on wide windows). [hostId] is `HostId.value`;
 * [address] is the core's opaque terminal address, e.g. `t7` or `rohan/t7`.
 *
 * [startedHere]: this device just started the terminal (new-terminal flow), so the screen attaches
 * as the size owner. Every other open (and every later re-attach) is passive, so opening a terminal
 * never resizes it for a desktop user who is watching it too (protocol/SPEC.md §7.5).
 */
@Serializable
data class TerminalRoute(
    val hostId: String,
    val address: String,
    val startedHere: Boolean = false,
) : NavKey {
    /** Same terminal, regardless of how it was opened. */
    fun isSameTerminal(other: TerminalRoute?): Boolean =
        other != null && other.hostId == hostId && other.address == address
}
