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
 * then on the viewport's cols x rows are sent (debounced while the window animates).
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
    private var lastRequested: TerminalSize? = null
    private var theme = theme

    @Volatile private var visible = true

    @Volatile private var focused = false

    @Volatile private var viewport: TerminalViewport? = null

    // ---- shared with the UI thread (guarded by [lock]) ----
    private val lock = Any()
    private val shared = ScreenSnapshot(initialCols, initialRows)

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
        }
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
        if (work.viewportOffset != 0) {
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
        scope.launch {
            if (!owner) {
                becomeOwner()
            } else {
                viewport?.grid()?.let { resizeTo(it, force = true) }
            }
        }
    }

    /** Lifecycle: app in foreground (STARTED) and screen on. Frames are not published while invisible. */
    fun setVisible(visible: Boolean) {
        this.visible = visible
        visibleFlow.value = visible
        scope.launch { updateFocus() }
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
            requestFrame()
            if (owner) stream?.updateDisplay(displayFacts())
        }
    }

    /** Scroll into scrollback: negative = older. */
    fun scrollBy(rows: Int) {
        if (rows == 0) return
        scope.launch {
            engine?.scrollViewport(rows)
            requestFrame()
        }
    }

    fun scrollToBottom() {
        scope.launch {
            engine?.scrollToBottom()
            requestFrame()
        }
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
        synchronized(lock) { copyRows(work, work.dirtyRows, shared, force = false) }
        publishedFrames++
        _frameVersion.value = (_frameVersion.value + 1) and 0x7F
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
