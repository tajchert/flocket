package com.mtajchert.flocket.core.protocol

import com.mtajchert.flocket.core.protocol.dto.DisplayDto
import com.mtajchert.flocket.core.protocol.dto.TerminalDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Why a stream channel (attach or events) ended. Exposed as a `StateFlow` that is set once. */
sealed interface ChannelEnd {
    /** We sent CLOSE (detach/unsubscribe). */
    data object LocalDetach : ChannelEnd

    /** The core closed with an empty payload: remote machine forgotten, reason unknown. Refresh and offer reattach. */
    data object ClosedByCore : ChannelEnd

    /** The program ended (`finished` data; SPEC §7.7). */
    data class ProgramFinished(
        val reason: String?,
        val exitCode: Int?,
        val signal: Int?,
        val finishedMs: Long?,
    ) : ChannelEnd

    /** A kept terminal lost its program (`lost` data, `finished_ms` null); reattach after `restarted`. */
    data class ProgramLost(
        val reason: String?,
        val exitCode: Int?,
        val signal: Int?,
    ) : ChannelEnd

    /** The connection ended; every stream on it is gone. [cause] is null for a local close. */
    data class ConnectionLost(
        val cause: Throwable?,
    ) : ChannelEnd

    /** The core sent more than the granted credit; the channel was closed defensively. */
    data object Overflow : ChannelEnd
}

/** Parameters of `terminal.attach` (SPEC §4.5). [cols]/[rows] 0 means "don't change the size". */
data class AttachParams(
    val terminal: String,
    val cols: Int,
    val rows: Int,
    val observe: Boolean = false,
    val resumed: Boolean = false,
    val history: Int = 0,
    val display: DisplayDto? = null,
    val timeout: Duration = 15.seconds,
)

/**
 * An attached terminal stream (SPEC §7).
 *
 * [output] has a single collector. Credit for a chunk is returned only after the collector has
 * finished handling it (credit on consumption), so a slow renderer stalls this terminal only.
 * The flow completes when the channel ends; read [closeReason] for why.
 */
interface AttachChannel {
    val channel: Int

    /** The terminal record from the attach reply (already reflects our size if we became size owner). */
    val terminal: TerminalDto

    /** First paint, then live output; a full repaint can arrive at any time. */
    val output: Flow<ByteArray>

    /** Null while open. */
    val closeReason: StateFlow<ChannelEnd?>

    /**
     * Send keystrokes. Split into DATA frames of at most 64 KiB, in order. Throws
     * [InputQueueFullException] (nothing sent) when too much output is already queued,
     * [ChannelClosedException] when the stream ended and [ConnectionClosedException] on a dead link.
     */
    suspend fun send(bytes: ByteArray)

    /** RESIZE; ignored when [cols] or [rows] is not positive (0 would only steal ownership). */
    suspend fun resize(
        cols: Int,
        rows: Int,
    )

    suspend fun focus(focused: Boolean)

    /** FACTS: send the full display object (SPEC §13: 0.1.15 replaces, 0.1.16 merges). */
    suspend fun facts(display: DisplayDto)

    /** Detach: send CLOSE and stop delivering data. The program keeps running. Idempotent. */
    suspend fun close()
}

/** Result of `events.subscribe {}` plus the notification stream (SPEC §8). */
class EventSubscription(
    val seq: Long,
    /** Snapshot as of [seq]; local terminals first. */
    val terminals: List<TerminalDto>,
    val channel: Int,
    /** Single collector. CREDIT is returned after each frame's lines were emitted. Completes when the channel ends. */
    val notifications: Flow<CoreNotification>,
    val end: StateFlow<ChannelEnd?>,
    private val closer: suspend () -> Unit,
) {
    /** Unsubscribe (CLOSE). */
    suspend fun close() = closer()
}

/** A line from the event channel (SPEC §8.2). */
sealed interface CoreNotification {
    /**
     * [data] is the event's `data` object; JSON `null` data (e.g. `foreground` cleared) and absent
     * data are both an empty object. [terminal] is `null` for `doc` events; [machine] only for remote docs.
     */
    data class Event(
        val seq: Long,
        val type: String,
        val terminal: String?,
        val machine: String?,
        val data: JsonObject,
    ) : CoreNotification

    data class Heartbeat(
        val seq: Long,
    ) : CoreNotification

    /** Fresh snapshot after falling behind: replace state, refetch documents, drop events with `seq <= seq`. */
    data class Missed(
        val seq: Long,
        val terminals: List<TerminalDto>,
    ) : CoreNotification
}
