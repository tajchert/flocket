package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.CoreEndpoint
import com.mtajchert.flocket.core.model.CoreInfo
import com.mtajchert.flocket.core.model.MachineInfo
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalRecord
import com.mtajchert.flocket.core.model.TerminalTemplate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one authoritative model of a connection to one herdr-core endpoint.
 *
 * Responsibilities (protocol/SPEC.md §11): open the transport, negotiate `hello` (generation,
 * features, required methods — never exact versions), subscribe to events unfiltered, reduce
 * events into [state], recover from `missed`, reconnect with jittered backoff using a new
 * connection epoch each time (callbacks from older epochs are dropped), and re-attach open
 * terminal streams passively after a reconnect.
 *
 * Mutations are at most once: nothing here retries a write. If the connection breaks after a
 * mutating call was dispatched the call returns `AppError.OutcomeUnknown` and state is re-read.
 *
 * Methods never throw (except [kotlin.coroutines.cancellation.CancellationException]).
 */
interface CoreSession {
    val endpoint: CoreEndpoint

    val state: StateFlow<SessionState>

    /** Start connecting and keep the connection up (idempotent). */
    fun start()

    /** Detach all streams and close the connection. Terminals keep running on the host. */
    suspend fun stop()

    /** Skip the current backoff delay / retry after [ConnectionState.NeedsAttention] was resolved. */
    fun retryNow()

    suspend fun run(request: RunRequest): AppResult<TerminalRecord>

    suspend fun kill(address: TerminalAddress): AppResult<TerminalRecord>

    /** Remove a finished record. */
    suspend fun remove(address: TerminalAddress): AppResult<Unit>

    /** Paste [text] and submit it (`terminal.send`). May take seconds (the core waits for quiet). */
    suspend fun sendText(
        address: TerminalAddress,
        text: String,
        force: Boolean = false,
    ): AppResult<SendReceipt>

    /** Press the program's stop key (`terminal.interrupt`). */
    suspend fun interrupt(address: TerminalAddress): AppResult<InterruptOutcome>

    suspend fun templates(): AppResult<List<TerminalTemplate>>

    /** Plain-text screen/history (`terminal.read`), for previews — not for rendering. */
    suspend fun readText(
        address: TerminalAddress,
        historyLines: Int = 0,
    ): AppResult<String>

    /** Open an interactive stream on a terminal. See [AttachRequest.mode] for size ownership. */
    suspend fun attach(
        address: TerminalAddress,
        request: AttachRequest,
    ): AppResult<TerminalStream>
}

/** Snapshot of everything the app knows about one endpoint. */
data class SessionState(
    val endpoint: CoreEndpoint,
    val connection: ConnectionState = ConnectionState.Idle,
    /** Last core seen on this endpoint (kept while disconnected, for display). */
    val core: CoreInfo? = null,
    /** In core order: local terminals first, then federated machines' terminals. */
    val terminals: List<TerminalRecord> = emptyList(),
    val machines: List<MachineInfo> = emptyList(),
    /** When [terminals] were last confirmed by a live connection; null = never. */
    val confirmedAtMs: Long? = null,
) {
    /** Data is cached, not live: show it as stale and disable writes. */
    val isStale: Boolean get() = connection !is ConnectionState.Online

    fun terminal(address: TerminalAddress): TerminalRecord? = terminals.firstOrNull { it.address == address }
}

data class RunRequest(
    /** Program and args (no shell). Empty with [line] null = the host's default shell. */
    val argv: List<String> = emptyList(),
    /** Shell line instead of [argv]. */
    val line: String? = null,
    /** Template name to run instead of argv/line (`templates.list`). */
    val template: String? = null,
    /** Always send a folder: without it the core uses its own cwd (SPEC §4.4). */
    val cwd: String,
    val name: String? = null,
    /** Federated machine to run on (`on`), null = the endpoint's own machine. */
    val machine: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val keep: Boolean = false,
)

data class SendReceipt(
    val statusWord: String?,
    val turn: Long,
    val started: Boolean?,
)

enum class InterruptOutcome { Stopped, Exited, StillWorking, Unknown }

data class AttachRequest(
    /** Viewport in cells. The core may keep its own size; render at [TerminalStream.size]. */
    val cols: Int,
    val rows: Int,
    val mode: AttachMode = AttachMode.Passive,
    /** Scrollback lines to include in the first paint. */
    val historyLines: Int = 0,
    val display: DisplayFacts? = null,
)

/**
 * Size ownership policy (SPEC §7.5): the most recently active viewer sets a terminal's size, so
 * attaching from a phone could resize a desktop user's terminal.
 */
enum class AttachMode {
    /** Default. Attach `resumed`: watch at the current size; become size owner on first input/resize/focus. */
    Passive,

    /** Read only: input, resize and focus are ignored by the core. */
    Observe,

    /** Take size ownership immediately (terminals this device just started). */
    Owner,
}

/** Viewer display facts (SPEC §6.6) so the core can answer color/cell-size queries. */
data class DisplayFacts(
    val foreground: String? = null,
    val background: String? = null,
    val cursor: String? = null,
    val cellWidthPx: Int? = null,
    val cellHeightPx: Int? = null,
)

/**
 * A live attach stream. Single consumer. Flow control is automatic: credit for a chunk of
 * [output] is returned once the collector has finished processing it, so a slow renderer applies
 * backpressure to this terminal only — never to control calls or other terminals.
 */
interface TerminalStream {
    val address: TerminalAddress

    /** Terminal size as currently set by the core (updated from `size` events). */
    val size: StateFlow<TerminalSize>

    /** Raw output bytes: first paint, then live output; a full repaint may arrive at any time. */
    val output: Flow<ByteArray>

    /** Null while open; set once when the stream ends. [output] completes at the same time. */
    val end: StateFlow<StreamEnd?>

    /**
     * Send keystrokes/paste bytes. Never retried. Fails with `InvalidInput` when the bounded
     * outgoing queue is full and `ConnectionLost` when the stream is gone.
     */
    suspend fun write(bytes: ByteArray): AppResult<Unit>

    /** Request a size; makes this viewer the size owner (unless [AttachMode.Observe]). */
    fun resize(
        cols: Int,
        rows: Int,
    )

    fun focus(focused: Boolean)

    fun updateDisplay(facts: DisplayFacts)

    /** Detach (the program keeps running). */
    suspend fun detach()
}

data class TerminalSize(
    val cols: Int,
    val rows: Int,
)

sealed interface StreamEnd {
    /** We detached. */
    data object Detached : StreamEnd

    /** The program exited or was killed. */
    data class ProgramEnded(
        val reason: String?,
        val exitCode: Int?,
        val signal: Int?,
    ) : StreamEnd

    /** A kept terminal lost its program; reattach after it restarts with a fresh shell. */
    data class ProgramLost(
        val reason: String?,
    ) : StreamEnd

    /** The connection dropped; the session will reattach a new stream after reconnecting. */
    data object ConnectionLost : StreamEnd

    /** The core closed the stream without a reason (e.g. remote machine forgotten). */
    data object Unknown : StreamEnd
}
