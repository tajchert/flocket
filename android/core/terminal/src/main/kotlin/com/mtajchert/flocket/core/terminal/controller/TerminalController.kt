package com.mtajchert.flocket.core.terminal.controller

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.session.AttachMode
import com.mtajchert.flocket.core.session.DisplayFacts
import com.mtajchert.flocket.core.session.TerminalSize
import com.mtajchert.flocket.core.session.TerminalStream
import com.mtajchert.flocket.core.terminal.engine.Key
import com.mtajchert.flocket.core.terminal.engine.KeyInput
import com.mtajchert.flocket.core.terminal.engine.ScreenSnapshot
import com.mtajchert.flocket.core.terminal.engine.TerminalEngine
import com.mtajchert.flocket.core.terminal.engine.TerminalEngineFactory
import com.mtajchert.flocket.core.terminal.engine.TerminalEvent
import com.mtajchert.flocket.core.terminal.engine.TerminalTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.Executors

/** The drawable area in pixels and the font's cell size; the controller derives cols x rows from it. */
data class TerminalViewport(
    val widthPx: Int,
    val heightPx: Int,
    val cellWidthPx: Float,
    val cellHeightPx: Float,
) {
    fun grid(): TerminalSize =
        TerminalSize(
            cols = (widthPx / cellWidthPx).toInt().coerceAtLeast(MIN_COLS),
            rows = (heightPx / cellHeightPx).toInt().coerceAtLeast(MIN_ROWS),
        )

    private companion object {
        const val MIN_COLS = 10
        const val MIN_ROWS = 3
    }
}

/**
 * Owns one terminal's [TerminalEngine] and connects it to a [TerminalStream]. Lives OUTSIDE
 * composition (a ViewModel or an app-scoped holder) so rotation, fold/unfold and other config
 * changes never recreate the engine or reattach. See docs/TERMINAL_RENDERING.md.
 *
 * Threading: every engine call runs on one dedicated thread ([engineDispatcher], by default a
 * private daemon thread). Output chunks are fed as they arrive, so the stream's credit flows back
 * per chunk and nothing is buffered here. Frames are coalesced: after feeding, at most one
 * snapshot per [FrameClock] tick is copied into a lock-guarded shared buffer, and [frameVersion]
 * changes to tell the UI to pull it with [acquireFrame].
 *
 * Size ownership (protocol/SPEC.md §7.5): in [AttachMode.Passive] the engine mirrors the core's
 * size ([TerminalStream.size]) and nothing that would make this viewer the leader (RESIZE,
 * FOCUS 1, FACTS) is sent. The first keystroke/paste or [fitToScreen] makes it the owner: from
 * then on the viewport's cols x rows are sent (debounced while the window animates). A stream
 * bound in [AttachMode.Owner] takes the size again after a dropped link, once the link is back
 * and the terminal is visible (the session re-attaches with `resumed:true`, which is passive).
 */
@OptIn(FlowPreview::class)
class TerminalController(
    private val engineFactory: TerminalEngineFactory,
    theme: TerminalTheme,
    private val frameClock: FrameClock = ChoreographerFrameClock(),
    engineDispatcher: CoroutineDispatcher? = null,
    private val resizeDebounceMs: Long = DEFAULT_RESIZE_DEBOUNCE_MS,
    inputQueueCapacity: Int = DEFAULT_INPUT_QUEUE,
    initialCols: Int = 80,
    initialRows: Int = 24,
) : TerminalFrameSource,
    AutoCloseable {
    private val ownedExecutor: ExecutorCoroutineDispatcher? =
        if (engineDispatcher == null) {
            Executors
                .newSingleThreadExecutor { r ->
                    Thread(r, "flocket-terminal").apply {
                        isDaemon = true
                        priority = Thread.NORM_PRIORITY + 1
                    }
                }.asCoroutineDispatcher()
        } else {
            null
        }
    private val dispatcher: CoroutineDispatcher = engineDispatcher ?: ownedExecutor!!
    private val scope =
        CoroutineScope(
            SupervisorJob() + dispatcher +
                CoroutineExceptionHandler { _, e -> _failures.tryEmit(AppError.Unexpected(e)) },
        )

    // ---- engine-thread state (only touched on [dispatcher]) ----
    private var engine: TerminalEngine? = null
    private val work = ScreenSnapshot(initialCols, initialRows)
    private var stream: TerminalStream? = null
    private var streamJob: Job? = null
    private var mode = AttachMode.Passive
    private var owner = false
    private var focusSent = false

    // Bound as Owner and the link dropped: take the size again when connected and visible.
    private var refitPending = false
    private var lastRequested: TerminalSize? = null
    private var theme = theme

    @Volatile private var visible = true

    @Volatile private var focused = false

    @Volatile private var viewport: TerminalViewport? = null

    // Row above the viewport for smooth scrolling (engine side). [aboveStale]: theme or size changed.
    @Volatile private var rowAboveWanted = false
    private val aboveWork = RowSnapshot()
    private var aboveStale = false

    // The viewport moved since `work` was taken (input must still snap it to the bottom).
    private var scrolledSinceSnapshot = false

    // ---- shared with the UI thread (guarded by [lock]) ----
    private val lock = Any()
    private val shared = ScreenSnapshot(initialCols, initialRows)
    private val aboveShared = RowSnapshot()

    private val frameRequests = Channel<Unit>(Channel.CONFLATED)
    private val visibleFlow = MutableStateFlow(true)
    private val viewportFlow = MutableStateFlow<TerminalViewport?>(null)
    private val input = Channel<Input>(inputQueueCapacity)

    private val _frameVersion = MutableStateFlow(0L)
    private val _gridSize = MutableStateFlow(TerminalSize(initialCols, initialRows))
    private val _isOwner = MutableStateFlow(false)
    private val _isReadOnly = MutableStateFlow(false)
    private val _connected = MutableStateFlow(false)
    private val _title = MutableStateFlow("")
    private val _workingDirectory = MutableStateFlow<String?>(null)
    private val _events =
        MutableSharedFlow<TerminalEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _failures =
        MutableSharedFlow<AppError>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _stickyCtrl = MutableStateFlow(false)
    private val _stickyAlt = MutableStateFlow(false)

    /**
     * Changes whenever a new frame was published; the UI invalidates and calls [acquireFrame].
     * Values cycle through 0..127 so the boxed Long comes from the JVM cache (no per-frame allocation).
     */
    val frameVersion: StateFlow<Long> = _frameVersion.asStateFlow()

    /** The engine's cols x rows (what is being rendered). */
    val gridSize: StateFlow<TerminalSize> = _gridSize.asStateFlow()

    /** The size to attach with: the viewport's cols x rows once laid out, else the engine's. */
    fun preferredSize(): TerminalSize = viewport?.grid() ?: _gridSize.value

    /** True once this viewer is the size owner (Owner mode, first input, or [fitToScreen]). */
    val isOwner: StateFlow<Boolean> = _isOwner.asStateFlow()

    /** True when bound in [AttachMode.Observe]: input is refused. */
    val isReadOnly: StateFlow<Boolean> = _isReadOnly.asStateFlow()

    /**
     * The bound stream's link is live. While false the retained screen keeps rendering (show a
     * "reconnecting" overlay); the session re-attaches passively and sends a fresh first paint.
     */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    val title: StateFlow<String> = _title.asStateFlow()

    /** OSC 7 working directory URI, if the program reported one. */
    val workingDirectory: StateFlow<String?> = _workingDirectory.asStateFlow()

    /** Bell, OSC 52 [TerminalEvent.ClipboardWrite] (UI must ask the user), notifications. */
    val events: Flow<TerminalEvent> = _events.asSharedFlow()

    /** Input that could not be delivered (never retried) and unexpected engine errors. */
    val failures: Flow<AppError> = _failures.asSharedFlow()

    /** Sticky modifiers from the extra-keys row; consumed by the next key. */
    val stickyCtrl: StateFlow<Boolean> = _stickyCtrl.asStateFlow()
    val stickyAlt: StateFlow<Boolean> = _stickyAlt.asStateFlow()

    /** Frames published so far (tests and diagnostics). */
    @Volatile var publishedFrames: Long = 0
        private set

    /** Output bytes fed to the engine so far. */
    @Volatile var bytesFed: Long = 0
        private set

    @Volatile private var closed = false

    init {
        scope.launch {
            engine = engineFactory.create(initialCols, initialRows).also { it.setTheme(theme) }
            requestFrame()
        }
        scope.launch {
            for (unused in frameRequests) {
                visibleFlow.first { it }
                frameClock.awaitFrame()
                publish()
            }
        }
        scope.launch { viewportFlow.filterNotNull().debounce(resizeDebounceMs).collect { onViewportSettled() } }
        scope.launch { for (cmd in input) deliver(cmd) }
    }

    /**
     * Attach a stream (or replace the previous one after a reattach / process death). The
     * engine's retained screen keeps showing until the core's first paint arrives.
     */
    fun bind(
        stream: TerminalStream,
        mode: AttachMode = AttachMode.Passive,
    ) {
        scope.launch {
            streamJob?.cancel()
            this@TerminalController.stream = stream
            this@TerminalController.mode = mode
            owner = false
            focusSent = false
            refitPending = false
            lastRequested = null
            _isOwner.value = false
            _isReadOnly.value = mode == AttachMode.Observe
            applyRemoteSize(stream.size.value)
            if (mode == AttachMode.Owner) becomeOwner()
            streamJob =
                launch {
                    launch { stream.size.collect { applyRemoteSize(it) } }
                    launch { stream.connected.collect { onConnectedChanged(it) } }
                    stream.output.collect { chunk ->
                        engine?.feed(chunk)
                        bytesFed += chunk.size
                        requestFrame()
                        // Let the frame loop and input run between chunks even when the stream has
                        // a backlog; returning from here returns the chunk's credit.
                        yield()
                    }
                }
        }
    }

    /** Stop following the current stream (the owner detaches it). The screen stays. */
    fun unbind() {
        scope.launch {
            streamJob?.cancel()
            streamJob = null
            stream = null
            owner = false
            refitPending = false
            _isOwner.value = false
            _connected.value = false
        }
    }

    private fun onConnectedChanged(connected: Boolean) {
        _connected.value = connected
        if (!connected) {
            // The session re-attaches passively (`resumed:true`) and is inactive until the next
            // write/resize/focus(true): mirror that so a layout pass never steals the size.
            owner = false
            _isOwner.value = false
            focusSent = false
            lastRequested = null
            if (mode == AttachMode.Owner) refitPending = true
        } else {
            refitIfPending()
        }
    }

    private fun refitIfPending() {
        if (!refitPending || !visible || !_connected.value) return
        refitPending = false
        fit()
    }

    // ------------------------------------------------------------------ input

    /** A key from the hardware keyboard, IME key events or the extra-keys row. Applies sticky modifiers. */
    fun sendKey(key: KeyInput): Boolean {
        val ctrl = _stickyCtrl.value
        val alt = _stickyAlt.value
        if (ctrl) _stickyCtrl.value = false
        if (alt) _stickyAlt.value = false
        return offer(Input.KeyPress(key.copy(ctrl = key.ctrl || ctrl, alt = key.alt || alt)))
    }

    /** Committed IME text. A single character honors sticky modifiers; newlines become CR. */
    fun sendText(text: String): Boolean {
        if (text.isEmpty()) return true
        if (text == "\n") return sendKey(KeyInput(Key.Enter))
        if (text.codePointCount(0, text.length) == 1 && (_stickyCtrl.value || _stickyAlt.value)) {
            return sendKey(KeyInput(Key.Character, text = text))
        }
        return offer(Input.Raw(text.replace("\r\n", "\r").replace('\n', '\r').encodeToByteArray()))
    }

    fun paste(text: String): Boolean = offer(Input.Paste(text))

    fun toggleStickyCtrl() {
        _stickyCtrl.value = !_stickyCtrl.value
    }

    fun toggleStickyAlt() {
        _stickyAlt.value = !_stickyAlt.value
    }

    private fun offer(cmd: Input): Boolean {
        if (closed) return false
        val ok = input.trySend(cmd).isSuccess
        if (!ok) _failures.tryEmit(AppError.InvalidInput("input", "input queue full; keystroke dropped"))
        return ok
    }

    private suspend fun deliver(cmd: Input) {
        val e = engine ?: return
        val s = stream
        if (s == null || mode == AttachMode.Observe) {
            _failures.tryEmit(if (s == null) AppError.ConnectionLost else AppError.InvalidInput("input", "read-only"))
            return
        }
        becomeOwner()
        if (work.viewportOffset != 0 || scrolledSinceSnapshot) {
            e.scrollToBottom()
            requestFrame()
        }
        val bytes =
            when (cmd) {
                is Input.KeyPress -> e.encodeKey(cmd.key)
                is Input.Paste -> e.encodePaste(cmd.text)
                is Input.Raw -> cmd.bytes
            }
        if (bytes.isEmpty()) return
        val result = s.write(bytes)
        if (result is AppResult.Failure) _failures.tryEmit(result.error)
    }

    // ------------------------------------------------------- size and focus

    /** Report the drawable area (on every layout change; debounced internally). */
    fun onViewportChanged(viewport: TerminalViewport) {
        this.viewport = viewport
        viewportFlow.value = viewport
    }

    /** Take size ownership now and fit the terminal to this screen. */
    fun fitToScreen() {
        scope.launch { fit() }
    }

    private fun fit() {
        if (!owner) {
            becomeOwner()
        } else {
            viewport?.grid()?.let { resizeTo(it, force = true) }
        }
    }

    /** Lifecycle: app in foreground (STARTED) and screen on. Frames are not published while invisible. */
    fun setVisible(visible: Boolean) {
        this.visible = visible
        visibleFlow.value = visible
        scope.launch {
            refitIfPending()
            updateFocus()
        }
    }

    /** The terminal view has input focus (this pane is the active one). */
    fun setFocused(focused: Boolean) {
        this.focused = focused
        scope.launch { updateFocus() }
    }

    fun setTheme(theme: TerminalTheme) {
        scope.launch {
            this@TerminalController.theme = theme
            engine?.setTheme(theme)
            aboveStale = true
            requestFrame()
            if (owner) stream?.updateDisplay(displayFacts())
        }
    }

    /** Scroll into scrollback: negative = older. */
    fun scrollBy(rows: Int) {
        if (rows == 0) return
        scope.launch {
            engine?.scrollViewport(rows)
            scrolledSinceSnapshot = true
            requestFrame()
        }
    }

    fun scrollToBottom() {
        scope.launch {
            engine?.scrollToBottom()
            requestFrame()
        }
    }

    /**
     * Scroll so the viewport's top row is the row with id [rowId] (`historyBase + screen row`, see
     * [ScreenSnapshot.historyBase]), clamped to the scrollback. Absolute and resolved against the
     * engine's current history base, so neither moves in flight nor output fed since the last frame
     * shift the result. To follow the bottom use [scrollToBottom].
     */
    fun scrollToRow(rowId: Long) {
        scope.launch {
            val e = engine ?: return@launch
            val row = (rowId - e.historyBase()).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            e.scrollViewportTo(row)
            scrolledSinceSnapshot = true
            requestFrame()
        }
    }

    override fun setRowAboveWanted(wanted: Boolean) {
        if (rowAboveWanted == wanted) return
        rowAboveWanted = wanted
        if (wanted) scope.launch { requestFrame() }
    }

    override fun acquireRowAbove(target: RowSnapshot): Boolean =
        synchronized(lock) {
            if (target.version == aboveShared.version) return false
            copyRows(aboveShared.cells, aboveShared.cells.dirtyRows, target.cells, force = true)
            target.id = aboveShared.id
            target.version = aboveShared.version
            true
        }

    /**
     * Plain text of the cells from ([startRow], [startCol]) to ([endRow], [endCol]) inclusive, rows as
     * ids (see [scrollToRow]) on the screen given by [altScreen]; null when the start row has left the
     * scrollback, the active screen changed, or there is no text.
     */
    suspend fun selectionText(
        startRow: Long,
        startCol: Int,
        endRow: Long,
        endCol: Int,
        altScreen: Boolean,
    ): String? =
        kotlinx.coroutines.withContext(dispatcher) {
            val e = engine ?: return@withContext null
            publish() // current historyBase
            val base = work.historyBase
            if (startRow < base || work.altScreen != altScreen) return@withContext null
            e.textRange((startRow - base).toInt(), startCol, (endRow - base).toInt(), endCol).ifEmpty { null }
        }

    /** Plain text of the screen plus [historyLines] of scrollback. */
    suspend fun screenText(historyLines: Int = 0): String =
        kotlinx.coroutines.withContext(dispatcher) { engine?.text(historyLines).orEmpty() }

    private fun becomeOwner() {
        val s = stream ?: return
        if (owner || mode == AttachMode.Observe) return
        val e = engine ?: return
        owner = true
        _isOwner.value = true
        resizeTo(viewport?.grid() ?: TerminalSize(e.cols, e.rows), force = true)
        if (visible && focused) {
            s.focus(true)
            focusSent = true
        }
        s.updateDisplay(displayFacts())
    }

    private fun onViewportSettled() {
        val vp = viewport ?: return
        if (owner) resizeTo(vp.grid(), force = false)
    }

    private fun resizeTo(
        size: TerminalSize,
        force: Boolean,
    ) {
        val e = engine ?: return
        if (!force && size.cols == e.cols && size.rows == e.rows) return
        if (size.cols != e.cols || size.rows != e.rows) {
            e.resize(size.cols, size.rows)
            _gridSize.value = size
            requestFrame()
        }
        lastRequested = size
        stream?.resize(size.cols, size.rows)
    }

    private fun applyRemoteSize(size: TerminalSize) {
        val e = engine ?: return
        if (size.cols <= 0 || size.rows <= 0) return
        if (owner && lastRequested != null && size != lastRequested) {
            // Another viewer became the leader (SPEC §7.5); stay passive until the user interacts.
            owner = false
            _isOwner.value = false
            focusSent = false
        }
        if (size.cols != e.cols || size.rows != e.rows) {
            e.resize(size.cols, size.rows)
            _gridSize.value = size
            requestFrame()
        }
    }

    private fun updateFocus() {
        val s = stream ?: return
        val want = visible && focused
        if (owner) {
            if (want != focusSent) {
                s.focus(want)
                focusSent = want
            }
        } else if (!want && focusSent) {
            // Never send FOCUS 1 while passive (it takes ownership); FOCUS 0 only if we sent 1.
            s.focus(false)
            focusSent = false
        }
    }

    private fun displayFacts(): DisplayFacts {
        val vp = viewport
        return DisplayFacts(
            foreground = hex(theme.foreground),
            background = hex(theme.background),
            cursor = hex(theme.cursor),
            cellWidthPx = vp?.cellWidthPx?.toInt(),
            cellHeightPx = vp?.cellHeightPx?.toInt(),
        )
    }

    // ------------------------------------------------------------- frames

    private fun requestFrame() {
        frameRequests.trySend(Unit)
    }

    private fun publish() {
        val e = engine ?: return
        val drained = e.drainEvents()
        if (drained.isNotEmpty()) {
            for (ev in drained) {
                when (ev) {
                    is TerminalEvent.TitleChanged -> _title.value = ev.title
                    is TerminalEvent.WorkingDirectoryChanged -> _workingDirectory.value = ev.uri
                    else -> _events.tryEmit(ev)
                }
            }
        }
        e.snapshot(work)
        scrolledSinceSnapshot = false
        // Read the row above outside the lock, then publish it with the frame it belongs to.
        val aboveChanged = rowAboveWanted && readRowAbove(e)
        synchronized(lock) {
            copyRows(work, work.dirtyRows, shared, force = false)
            if (aboveChanged) {
                copyRows(aboveWork.cells, aboveWork.cells.dirtyRows, aboveShared.cells, force = true)
                aboveShared.id = aboveWork.id
                aboveShared.version = (aboveShared.version + 1) and 0x3FFFFFFF
            }
        }
        publishedFrames++
        _frameVersion.value = (_frameVersion.value + 1) and 0x7F
    }

    /** Engine thread: read the row above the viewport if it is a different row than last time. */
    private fun readRowAbove(e: TerminalEngine): Boolean {
        val screenRow = work.scrollbackRows - work.viewportOffset - 1
        val id = if (screenRow >= 0) work.historyBase + screenRow else RowSnapshot.NONE
        if (id == aboveWork.id && aboveWork.cells.cols == work.cols && !aboveStale) return false
        aboveStale = false
        aboveWork.id = if (id != RowSnapshot.NONE && e.readRow(screenRow, aboveWork.cells)) id else RowSnapshot.NONE
        return true
    }

    override fun acquire(
        target: ScreenSnapshot,
        rowAbove: RowSnapshot,
    ): Int =
        synchronized(lock) {
            (if (acquireFrame(target)) TerminalFrameSource.FRAME_CHANGED else 0) or
                (if (acquireRowAbove(rowAbove)) TerminalFrameSource.ROW_ABOVE_CHANGED else 0)
        }

    override fun acquireFrame(target: ScreenSnapshot): Boolean =
        synchronized(lock) {
            val changed = copyRows(shared, shared.dirtyRows, target, force = false)
            shared.dirtyRows.fill(false)
            changed
        }

    /**
     * Stop collecting, drop pending input and release the engine (on its thread). Does NOT detach
     * the stream: its owner decides (grace period, other panes). Idempotent.
     */
    override fun close() {
        if (closed) return
        closed = true
        input.close()
        frameRequests.close()
        scope.cancel()
        CoroutineScope(dispatcher + NonCancellable).launch {
            engine?.close()
            engine = null
            ownedExecutor?.close()
        }
    }

    private sealed interface Input {
        class KeyPress(
            val key: KeyInput,
        ) : Input

        class Paste(
            val text: String,
        ) : Input

        class Raw(
            val bytes: ByteArray,
        ) : Input
    }

    companion object {
        const val DEFAULT_RESIZE_DEBOUNCE_MS = 120L
        const val DEFAULT_INPUT_QUEUE = 256

        private fun hex(argb: Int) = "#%06x".format(argb and 0xFFFFFF)
    }
}
