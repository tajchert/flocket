package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.CoreEndpoint
import com.mtajchert.flocket.core.model.CoreInfo
import com.mtajchert.flocket.core.model.DesktopLayout
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
 * terminal streams with `resumed:true` after a reconnect ([TerminalStream] handles are durable).
 *
 * Mutations are at most once: nothing here retries a write. If the connection breaks after a
 * mutating call was dispatched the call returns `AppError.OutcomeUnknown` and state is re-read.
 *
 * Methods never throw (except [kotlin.coroutines.cancellation.CancellationException]).
 */
interface CoreSession {
    val endpoint: CoreEndpoint

    val state: StateFlow<SessionState>

    /**
     * Start connecting and keep the connection up (idempotent; safe to race with [stop]: a new
     * connection starts only after the previous one is fully closed). Parked streams re-attach.
     */
    fun start()

    /**
     * Close the connection and go [ConnectionState.Idle]. Open [TerminalStream]s are **parked**,
     * not ended: their channels are closed (CLOSE, so the core re-applies size ownership), they
     * report `connected = false`, and the next [start] re-attaches them with `resumed:true` and a
     * fresh first paint. Terminals keep running on the host. Call [TerminalStream.detach] to drop
     * a handle for good.
     */
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

    /**
     * The absolute `$HOME` of the core's host, or of [machine] when given (`machine.exec`), cached
     * per core and machine. [run] uses it to expand a leading `~` in the cwd, which herdr-core does
     * not do itself (protocol/SPEC.md §12). `Unsupported` when the core lacks `machine.exec`.
     */
    suspend fun homeDirectory(machine: String? = null): AppResult<String>

    /** Plain-text screen/history (`terminal.read`), for previews — not for rendering. */
    suspend fun readText(
        address: TerminalAddress,
        historyLines: Int = 0,
    ): AppResult<String>

    /**
     * Open a durable stream on a terminal (requires Online). See [AttachRequest.mode] for size
     * ownership. `Rpc(unavailable)` = kept terminal waiting for its new shell: wait for the
     * record to become Running (`restarted`) and attach again.
     */
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
    /** The desktop app's layout (`herdr-app` document, local machine), kept while disconnected; null = none. */
    val desktopLayout: DesktopLayout? = null,
    /**
     * Federated machines' desktop layouts (each machine's own `herdr-app` document, read with `on`), keyed by
     * machine name. Pane addresses inside are that machine's local ones (`t7`, not `rohan/t7`). Kept while
     * disconnected; a machine with no document has no entry.
     */
    val machineLayouts: Map<String, DesktopLayout> = emptyMap(),
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
 * A durable viewer handle on one terminal. Single consumer.
 *
 * **Durable across reconnects:** when the connection drops the session keeps this handle,
 * reconnects and re-attaches with `resumed:true` (so it never steals size ownership), and
 * [output] continues with a fresh first paint (engines must accept a full repaint at any time).
 * [connected] is false while no attach channel is live. [end] is set only when the stream is
 * really over (detached, program ended/lost, or the terminal is gone) — never for a dropped link.
 *
 * Flow control is automatic: credit for a chunk of [output] is returned once the collector has
 * finished processing it, so a slow renderer applies backpressure to this terminal only — never
 * to control calls or other terminals.
 *
 * Size ownership (SPEC §7.5): an [AttachMode.Passive] stream (and every stream after an automatic
 * re-attach) watches at the core's size and sends no RESIZE/FOCUS/FACTS until the first [write],
 * [resize] or `focus(true)`; then it becomes active and the latest requested size/display/focus is
 * sent. [AttachMode.Observe] never sends input or controls.
 */
interface TerminalStream {
    val address: TerminalAddress

    /**
     * Terminal size as currently set by the core: from the attach reply, then from `size` events
     * (and any fresh record) for [address]. Render at this size.
     */
    val size: StateFlow<TerminalSize>

    /** True while an attach channel is live; false while the session is reconnecting/re-attaching. */
    val connected: StateFlow<Boolean>

    /**
     * Raw output bytes: first paint, then live output; a full repaint may arrive at any time
     * (always after a re-attach). Completes after [end] was set and remaining bytes were delivered.
     */
    val output: Flow<ByteArray>

    /** Null while the handle is usable (even if temporarily disconnected); set once when it ends. */
    val end: StateFlow<StreamEnd?>

    /**
     * Send keystrokes/paste bytes. Never queued across a reconnect and never retried. Fails fast
     * with `ConnectionLost` while [connected] is false or after [end], with `InvalidInput` when the
     * bounded outgoing queue is full or the stream is [AttachMode.Observe].
     */
    suspend fun write(bytes: ByteArray): AppResult<Unit>

    /**
     * Request a size; makes this viewer active, i.e. the size owner (ignored for
     * [AttachMode.Observe]). Call it on user intent (first interaction, explicit "fit"), not on
     * every layout pass of a passive viewer. The latest value is also used for re-attaches.
     */
    fun resize(
        cols: Int,
        rows: Int,
    )

    /** `focus(true)` makes this viewer active; `focus(false)` is sent only by an active viewer. */
    fun focus(focused: Boolean)

    /** Theme/font changed. Sent as FACTS only while active (0.1.15 FACTS would steal ownership). */
    fun updateDisplay(facts: DisplayFacts)

    /** Detach (CLOSE; the program keeps running). Sets [end] to [StreamEnd.Detached]. Idempotent. */
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

    /**
     * A kept terminal lost its program (stream closed with the loss reason, or `unavailable` on
     * re-attach while the record is waiting for a new shell; other `unavailable`s, e.g. a remote
     * machine's line being down, keep the handle parked and retry). The record stays live; offer a new [CoreSession.attach] after `restarted`
     * (it is then a fresh shell, not the old task).
     */
    data class ProgramLost(
        val reason: String?,
    ) : StreamEnd

    /**
     * The core closed the stream without a reason (e.g. remote machine forgotten), the terminal
     * was removed, or it was `not_found` when re-attaching after a reconnect.
     */
    data object Unknown : StreamEnd
}
