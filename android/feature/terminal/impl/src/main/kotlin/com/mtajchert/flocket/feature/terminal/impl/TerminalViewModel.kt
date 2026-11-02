package com.mtajchert.flocket.feature.terminal.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.AgentMarks
import com.mtajchert.flocket.core.data.ApplicationScope
import com.mtajchert.flocket.core.data.ConnectionManager
import com.mtajchert.flocket.core.data.HostSettingsRepository
import com.mtajchert.flocket.core.data.PreferencesRepository
import com.mtajchert.flocket.core.model.ClipboardWritePolicy
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.LayoutRef
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.MachineState
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.buildWorkspace
import com.mtajchert.flocket.core.model.isAgent
import com.mtajchert.flocket.core.session.AttachMode
import com.mtajchert.flocket.core.session.AttachRequest
import com.mtajchert.flocket.core.session.CoreSession
import com.mtajchert.flocket.core.session.InterruptOutcome
import com.mtajchert.flocket.core.session.SessionState
import com.mtajchert.flocket.core.session.StreamEnd
import com.mtajchert.flocket.core.session.TerminalStream
import com.mtajchert.flocket.core.terminal.controller.TerminalController
import com.mtajchert.flocket.core.ui.error.UiText
import com.mtajchert.flocket.core.ui.error.toRenameText
import com.mtajchert.flocket.core.ui.error.toUiError
import com.mtajchert.flocket.core.ui.error.unreachableRenameText
import com.mtajchert.flocket.feature.terminal.api.TerminalRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Creates the screen's [TerminalController] (production: Ghostty engine; tests: fake engine + manual clock). */
fun interface TerminalControllerFactory {
    fun create(): TerminalController
}

/**
 * Owns ONE [TerminalController] and the [TerminalStream] of one terminal for as long as the screen is
 * really open. Config changes (rotation, fold, multi-window resize) keep this ViewModel, so nothing is
 * re-attached and the engine's screen is shown instantly. Process death rebuilds it from the saved
 * back stack ([route]) and attaches again; the core's first paint restores the screen.
 *
 * Attach needs an Online session: the screen shows "Connecting" and attaches as soon as the host is
 * Online. Dropped links are handled by the stream itself (durable handle, passive re-attach).
 */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = TerminalViewModel.Factory::class)
class TerminalViewModel
    @AssistedInject
    constructor(
        @Assisted private val route: TerminalRoute,
        private val connections: ConnectionManager,
        private val preferences: PreferencesRepository,
        private val hostSettings: HostSettingsRepository,
        private val agentMarks: AgentMarks,
        controllerFactory: TerminalControllerFactory,
        @ApplicationScope private val appScope: CoroutineScope,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(route: TerminalRoute): TerminalViewModel
        }

        private val hostId = HostId(route.hostId)
        private val address = TerminalAddress(route.address)

        /** The engine + renderer state; survives config changes with this ViewModel. */
        val controller: TerminalController = controllerFactory.create()

        private val session = MutableStateFlow<CoreSession?>(null)
        private val phase = MutableStateFlow<TerminalPhase>(TerminalPhase.Connecting)
        private val local = MutableStateFlow(LocalState())
        private val retries = Channel<Unit>(Channel.CONFLATED)
        private val fontSizes = MutableStateFlow<Float?>(null)
        private val _events = Channel<TerminalScreenEvent>(Channel.BUFFERED)
        val events: Flow<TerminalScreenEvent> = _events.receiveAsFlow()

        /** A person is looking: app in the foreground and this pane drawn (see [setLooking]). */
        private val looking = MutableStateFlow(false)

        @Volatile private var stream: TerminalStream? = null

        @Volatile private var cleared = false

        /** Number of `attach` calls made (diagnostics and tests). */
        @Volatile var attachCount: Int = 0
            private set

        private data class LocalState(
            val freshShell: Boolean = false,
            val clipboardRequest: String? = null,
            val confirmKill: Boolean = false,
            val rename: RenameUi? = null,
            /** Pinch result not yet confirmed by the preferences flow. */
            val fontSizeSp: Float? = null,
            /** Answered in this screen; hides the prompt before the preferences write lands. */
            val notificationAsked: Boolean = false,
        )

        private val sessionState: Flow<SessionState?> = session.flatMapLatest { it?.state ?: flowOf(null) }

        private val controllerState =
            combine(controller.connected, controller.isOwner, controller.title) { c, o, t -> Triple(c, o, t) }

        val uiState: StateFlow<TerminalUiState> =
            combine(
                sessionState,
                connections.overview.map { list -> list.firstOrNull { it.host.id == hostId }?.host?.label },
                combine(phase, local, ::Pair),
                controllerState,
                combine(preferences.preferences, hostSettings.effective(hostId), ::Pair),
            ) { st, hostLabel, (ph, loc), (connected, owner, programTitle), (prefs, effective) ->
                val terminal = effective.terminal
                val record = st?.terminal(address)
                TerminalUiState(
                    address = route.address,
                    hostLabel = hostLabel.orEmpty(),
                    machine = address.machine,
                    agentLabel = record?.agentLabel.orEmpty(),
                    title = programTitle.ifBlank { record?.recordTitle().orEmpty() },
                    status = record?.status ?: com.mtajchert.flocket.core.model.AgentStatus.Unknown,
                    connection = st?.connection ?: ConnectionState.Idle,
                    phase = ph,
                    streamConnected = connected,
                    isOwner = owner,
                    freshShell = loc.freshShell,
                    fontSizeSp = loc.fontSizeSp ?: terminal.fontSizeSp,
                    showExtraKeys = terminal.showExtraKeys,
                    keepScreenOn = terminal.keepScreenOn,
                    colorScheme = terminal.colorScheme,
                    bell = terminal.bell,
                    clipboardRequest = loc.clipboardRequest,
                    confirmKill = loc.confirmKill,
                    rename = loc.rename,
                    offerNotificationPermission =
                        ph == TerminalPhase.Live && prefs.keepConnectedInBackground &&
                            !prefs.notificationPermissionAsked && !loc.notificationAsked,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TerminalUiState(address = route.address))

        /**
         * The space's tabs and this tab's panes (null = nothing to switch between). A separate flow from
         * [uiState], deduplicated on the built value, so status ticks and foreground polls on other
         * terminals never recompose the terminal itself.
         */
        val tabStrip: StateFlow<TabStripUi?>

        /** The top bar's space name and "next needs you" target; deduplicated like [tabStrip]. */
        val header: StateFlow<TerminalHeaderUi>

        init {
            // One workspace build per session update feeds both flows.
            val workspace =
                sessionState
                    .map { st -> st?.let { it to buildWorkspace(it.terminals, it.desktopLayout, it.machineLayouts) } }
                    .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
            tabStrip =
                workspace
                    .map { pair -> pair?.let { (st, ws) -> buildTabStrip(ws, st.terminals, address)?.renameable(st) } }
                    .distinctUntilChanged()
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
            header =
                workspace
                    .map { pair ->
                        pair?.let { (st, ws) -> buildHeader(ws, st.terminals, address) }
                            ?: TerminalHeaderUi()
                    }.distinctUntilChanged()
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TerminalHeaderUi())
        }

        init {
            viewModelScope.launch { run() }
            viewModelScope.launch { markSeenWhileLooking() }
            viewModelScope.launch {
                var last: AppError? = null
                var lastAtMs = 0L
                controller.failures.collect { error ->
                    // Keys typed while the link is down fail fast with ConnectionLost: the reconnecting
                    // chip already says so, and one snackbar per key would bury the screen.
                    if (error is AppError.ConnectionLost) return@collect
                    val now = System.currentTimeMillis()
                    if (error == last && now - lastAtMs < FAILURE_DEDUPE_MS) return@collect
                    last = error
                    lastAtMs = now
                    report(error)
                }
            }
            viewModelScope.launch {
                // Pinch emits continuously; persist once it settles.
                fontSizes.filterNotNull().debounce(FONT_SAVE_DEBOUNCE_MS).collect { size ->
                    // The host's own font override when it has one, else the app default.
                    hostSettings.setFontSize(hostId, size)
                }
            }
        }

        // ------------------------------------------------------------------ attach loop

        private suspend fun run() {
            val s = awaitSession() ?: return
            session.value = s
            while (true) {
                val record = s.state.value.terminal(address)
                val liveness = record?.liveness
                if (liveness is Liveness.Finished && stream == null) {
                    // Nothing to attach to; show how it ended (the screen keeps whatever it has).
                    phase.value = TerminalPhase.Exited(liveness.exitCode, liveness.signal, liveness.reason)
                    return
                }
                if (phase.value != TerminalPhase.WaitingForShell) phase.value = TerminalPhase.Connecting
                s.state.first { it.connection is ConnectionState.Online }
                if (s.state.value
                        .terminal(address)
                        ?.liveness is Liveness.Waiting
                ) {
                    phase.value = TerminalPhase.WaitingForShell
                    awaitRunning(s)
                    continue
                }
                phase.value = TerminalPhase.Attaching
                // Always the size owner: opening a terminal fits it to this screen (ADR-0007); a
                // desktop viewer snaps back when its user types there.
                val mode = AttachMode.Owner
                val size = controller.preferredSize()
                attachCount++
                // Not cancellable: a stream returned after the screen closed must still be detached.
                val result =
                    withContext(NonCancellable) {
                        s.attach(address, AttachRequest(size.cols, size.rows, mode, historyLines = HISTORY_LINES))
                    }
                when (result) {
                    is AppResult.Success -> {
                        val st = result.value
                        if (cleared) {
                            appScope.launch { st.detach() }
                            return
                        }
                        stream = st
                        // While bound, leaving the app keeps the link up for a few minutes (ADR-0006).
                        connections.holdTerminal(this)
                        controller.bind(st, mode)
                        phase.value = TerminalPhase.Live
                        val end = st.end.filterNotNull().first()
                        connections.releaseTerminal(this)
                        when (end) {
                            is StreamEnd.ProgramEnded -> {
                                phase.value = TerminalPhase.Exited(end.exitCode, end.signal, end.reason)
                                return
                            }

                            is StreamEnd.ProgramLost -> {
                                stream = null
                                phase.value = TerminalPhase.WaitingForShell
                                awaitRunning(s)
                            }

                            StreamEnd.Unknown -> {
                                stream = null
                                phase.value = TerminalPhase.StreamEnded
                                retries.receive()
                            }

                            StreamEnd.Detached -> {
                                return
                            }
                        }
                    }

                    is AppResult.Failure -> {
                        val error = result.error
                        when {
                            error is AppError.Rpc && error.kind == AppError.Rpc.UNAVAILABLE -> {
                                phase.value = TerminalPhase.WaitingForShell
                                awaitRunning(s)
                            }

                            error is AppError.Rpc && error.kind == AppError.Rpc.FINISHED -> {
                                val l =
                                    s.state.value
                                        .terminal(address)
                                        ?.liveness as? Liveness.Finished
                                phase.value = TerminalPhase.Exited(l?.exitCode, l?.signal, l?.reason)
                                return
                            }

                            error is AppError.Rpc && error.kind in GONE_KINDS -> {
                                phase.value = TerminalPhase.StreamEnded
                                retries.receive()
                            }

                            // The link dropped under the call: wait for Online again.
                            error is AppError.ConnectionLost || error is AppError.Timeout -> {
                                Unit
                            }

                            else -> {
                                phase.value = TerminalPhase.Failed(error.toUiError())
                                retries.receive()
                            }
                        }
                    }
                }
            }
        }

        private suspend fun awaitSession(): CoreSession? {
            // Opening a terminal is an explicit connect for hosts that don't connect on launch.
            connections.connect(hostId)
            connections.session(hostId)?.let { return it }
            // `session()` is null until the manager saw the hosts (startup, process death).
            val found =
                connections.overview.first { list ->
                    connections.session(hostId) != null || (list.isNotEmpty() && list.none { it.host.id == hostId })
                }
            return connections.session(hostId) ?: run {
                if (found.isNotEmpty()) phase.value = TerminalPhase.HostMissing
                null
            }
        }

        /** A kept terminal waits for its new shell (`restarted` makes the record Running again). */
        private suspend fun awaitRunning(s: CoreSession) {
            s.state.first { st ->
                st.connection is ConnectionState.Online && st.terminal(address)?.liveness == Liveness.Running
            }
            // Only now is it a different program than the one the user saw.
            local.update { it.copy(freshShell = true) }
        }

        // ------------------------------------------------------------------ seen

        /**
         * The screen reports whether the terminal is really on screen (resumed and composed). Not part of
         * the attach path: it only drives the `herdr.seen` write.
         */
        fun setLooking(value: Boolean) {
            looking.value = value
        }

        /**
         * While looking, marks the agent seen once when the visit starts and again for each new turn.
         * The turn handled is remembered per visit, so a mark-unread done elsewhere (two-pane list)
         * is not overwritten; a new visit starts fresh. A transient failure is retried when the state next emits
         * (e.g. after a reconnect); the write is idempotent.
         */
        private suspend fun markSeenWhileLooking() {
            looking.collectLatest { isLooking ->
                if (!isLooking) return@collectLatest
                var handled: Long? = null
                sessionState
                    .map { st ->
                        val record = st?.terminal(address)
                        if (record != null && record.isAgent &&
                            st.connection is ConnectionState.Online
                        ) {
                            record
                        } else {
                            null
                        }
                    }.distinctUntilChanged { a, b -> a?.turn == b?.turn }
                    .collect { record ->
                        if (record == null || record.turn == handled) return@collect
                        // Only a written (or permanently unwritable) turn counts as handled, so a failed
                        // write is tried again after a reconnect.
                        when (val r = agentMarks.markSeen(record.ref)) {
                            is AppResult.Success -> handled = record.turn
                            is AppResult.Failure ->
                                if (r.error is AppError.Unsupported || r.error is AppError.InvalidInput) {
                                    handled = record.turn
                                }
                        }
                    }
            }
        }

        // ------------------------------------------------------------------ actions

        fun retryAttach() {
            retries.trySend(Unit)
        }

        fun fitToScreen() {
            controller.fitToScreen()
            send(TerminalScreenEvent.Message(UiText.Res(R.string.terminal_fit_done)))
        }

        fun paste(text: String) {
            if (text.isEmpty()) return
            controller.paste(text)
        }

        fun copyScreen() {
            viewModelScope.launch {
                val text = controller.screenText(historyLines = 0).trimEnd()
                send(TerminalScreenEvent.CopyToClipboard(text))
                send(TerminalScreenEvent.Message(UiText.Res(R.string.terminal_copied)))
            }
        }

        /**
         * A reply chip: the same path as the extra keys ([TerminalController.sendKey], sticky modifiers
         * apply), so it is written at most once and fails fast while the link is down.
         */
        fun sendReply(reply: ReplyKey) {
            controller.sendKey(reply.keyInput)
        }

        fun interrupt() {
            val s = session.value ?: return
            viewModelScope.launch {
                when (val r = s.interrupt(address)) {
                    is AppResult.Success -> {
                        val msg =
                            when (r.value) {
                                InterruptOutcome.Stopped -> R.string.terminal_interrupt_stopped
                                InterruptOutcome.Exited -> R.string.terminal_interrupt_exited
                                InterruptOutcome.StillWorking -> R.string.terminal_interrupt_still_working
                                InterruptOutcome.Unknown -> R.string.terminal_interrupt_sent
                            }
                        send(TerminalScreenEvent.Message(UiText.Res(msg)))
                    }

                    is AppResult.Failure -> {
                        report(r.error)
                    }
                }
            }
        }

        /** Opens the rename dialog for a tab; refused with a message while the host or machine is not reachable. */
        fun requestRename(
            ref: LayoutRef,
            current: String,
            placeholder: String,
        ) {
            if (!canRename(ref)) {
                send(TerminalScreenEvent.Message(unreachableRenameText(ref, hostLabel())))
                return
            }
            local.update { it.copy(rename = RenameUi(ref, placeholder, current)) }
        }

        fun updateRename(text: String) = local.update { l -> l.copy(rename = l.rename?.copy(text = text)) }

        fun dismissRename() = local.update { it.copy(rename = null) }

        /** Writes the name (empty = automatic name). Never resent when the link drops: the outcome is shown. */
        fun confirmRename() {
            val r = local.value.rename ?: return
            local.update { it.copy(rename = null) }
            val s = session.value
            if (s == null) {
                send(TerminalScreenEvent.Message(unreachableRenameText(r.ref, hostLabel())))
                return
            }
            viewModelScope.launch {
                val failure = (s.renameInLayout(r.ref, r.text.trim()) as? AppResult.Failure)?.error ?: return@launch
                val text = failure.toRenameText(r.ref, hostLabel())
                if (text != null) send(TerminalScreenEvent.Message(text)) else report(failure)
            }
        }

        private fun hostLabel(): String =
            connections.overview.value
                .firstOrNull { it.host.id == hostId }
                ?.host
                ?.label
                .orEmpty()

        private fun canRename(ref: LayoutRef): Boolean {
            val st = session.value?.state?.value ?: return false
            return st.connection is ConnectionState.Online &&
                (
                    ref.machine == null ||
                        st.machines.firstOrNull { it.name == ref.machine }?.state == MachineState.Connected
                )
        }

        fun requestKill() = local.update { it.copy(confirmKill = true) }

        fun dismissKill() = local.update { it.copy(confirmKill = false) }

        fun confirmKill() {
            local.update { it.copy(confirmKill = false) }
            val s = session.value ?: return
            viewModelScope.launch {
                when (val r = s.kill(address)) {
                    is AppResult.Success -> Unit

                    // The stream's end turns into the Exited banner.
                    is AppResult.Failure -> report(r.error)
                }
            }
        }

        /** Remove the finished record and leave the screen. */
        fun remove() {
            val s = session.value ?: return
            viewModelScope.launch {
                when (val r = s.remove(address)) {
                    is AppResult.Success -> send(TerminalScreenEvent.Close)
                    is AppResult.Failure -> report(r.error)
                }
            }
        }

        fun close() = send(TerminalScreenEvent.Close)

        fun dismissFreshShell() = local.update { it.copy(freshShell = false) }

        /** OSC 52: the host's effective policy decides. Allow writes silently (with a note), Deny drops it. */
        fun onClipboardWriteRequest(text: String) {
            viewModelScope.launch {
                when (
                    hostSettings
                        .effective(hostId)
                        .first()
                        .terminal.clipboardWritePolicy
                ) {
                    ClipboardWritePolicy.Allow -> {
                        send(TerminalScreenEvent.CopyToClipboard(text))
                        send(TerminalScreenEvent.Message(UiText.Res(R.string.terminal_clipboard_written)))
                    }

                    ClipboardWritePolicy.Deny -> {
                        Unit
                    }

                    ClipboardWritePolicy.Ask -> {
                        local.update { it.copy(clipboardRequest = text) }
                    }
                }
            }
        }

        /** "Always allow for this host": copies now and makes Allow this host's own policy. */
        fun alwaysAllowClipboard() {
            val text = local.value.clipboardRequest ?: return
            local.update { it.copy(clipboardRequest = null) }
            send(TerminalScreenEvent.CopyToClipboard(text))
            viewModelScope.launch {
                hostSettings.update(hostId) { it.copy(clipboardWritePolicy = ClipboardWritePolicy.Allow) }
            }
        }

        fun answerClipboardRequest(allow: Boolean) {
            val text = local.value.clipboardRequest ?: return
            local.update { it.copy(clipboardRequest = null) }
            if (allow) send(TerminalScreenEvent.CopyToClipboard(text))
        }

        /** The notification permission prompt was shown (any answer): never offer it again. */
        fun onNotificationPermissionAsked() {
            local.update { it.copy(notificationAsked = true) }
            viewModelScope.launch { preferences.update { it.copy(notificationPermissionAsked = true) } }
        }

        fun onFontSizeChange(sizeSp: Float) {
            local.update { it.copy(fontSizeSp = sizeSp) }
            fontSizes.value = sizeSp
        }

        private fun report(error: AppError) {
            send(
                if (error is AppError.OutcomeUnknown) {
                    TerminalScreenEvent.OutcomeUnknown
                } else {
                    TerminalScreenEvent.Failure(error.toUiError())
                },
            )
        }

        private fun send(event: TerminalScreenEvent) {
            _events.trySend(event)
        }

        /** Only a real close lands here (never rotation): detach for good, then release the engine. */
        override fun onCleared() {
            cleared = true
            connections.releaseTerminal(this)
            val s = stream
            stream = null
            appScope.launch {
                s?.detach()
                controller.close()
            }
        }

        companion object {
            const val HISTORY_LINES = 1_000
            private const val FONT_SAVE_DEBOUNCE_MS = 400L
            private const val FAILURE_DEDUPE_MS = 3_000L
            private val GONE_KINDS = setOf(AppError.Rpc.NOT_FOUND, AppError.Rpc.MACHINE_UNKNOWN)
        }
    }

private fun com.mtajchert.flocket.core.model.TerminalRecord.recordTitle(): String =
    title.ifBlank {
        where
            ?: name.orEmpty()
    }

/** The strip with the rename targets removed while writes are impossible (stale host, unreachable machine). */
private fun TabStripUi.renameable(state: SessionState): TabStripUi {
    val online = state.connection is ConnectionState.Online
    return copy(
        tabs =
            tabs.map { tab ->
                val ref = tab.layoutRef
                val reachable =
                    online &&
                        (
                            ref?.machine == null ||
                                state.machines.firstOrNull { it.name == ref.machine }?.state == MachineState.Connected
                        )
                if (reachable) tab else tab.copy(layoutRef = null)
            },
    )
}
