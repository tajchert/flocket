package com.mtajchert.flocket.core.data

import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.TerminalRef

/**
 * Writes the desktop's agent labels `herdr.seen` and `herdr.pinned` (protocol/SPEC.md §6.4) through
 * `terminal.label`. Writes are idempotent, so a failure needs no unknown-outcome dialog: ignore it or
 * re-read the state. Nothing is written (and no call is made) unless the host is Online, the core
 * supports `terminal.label`, and the terminal is known. Failures: `ConnectionLost` (not Online),
 * `Unsupported("terminal.label")`, `InvalidInput("terminal")` (unknown terminal), or the core's error.
 */
interface AgentMarks {
    /** The host's core has `terminal.label` (from the last hello; false when never connected). */
    fun supportsLabels(hostId: HostId): Boolean

    /**
     * A person is looking at this agent: writes `herdr.seen=<turn>` only when the label is missing or lower.
     * At most one write per terminal and turn (remembered in memory; a later mark-unread by another client
     * is not overwritten for the same turn). No-op success for non-agents.
     */
    suspend fun markSeen(ref: TerminalRef): AppResult<Unit>

    /**
     * Writes `herdr.seen=<turn-1>` so the agent shows as done again. No-op at turn 0 and for non-agents.
     * After a successful write [markSeen] may write the current turn again.
     */
    suspend fun markUnread(ref: TerminalRef): AppResult<Unit>

    /** `herdr.pinned=1`, or `""` (removes the label) when unpinning. */
    suspend fun setPinned(
        ref: TerminalRef,
        pinned: Boolean,
    ): AppResult<Unit>
}
