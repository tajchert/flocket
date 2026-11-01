package com.mtajchert.flocket.feature.terminals.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.ConnectionManager
import com.mtajchert.flocket.core.data.HostOverview
import com.mtajchert.flocket.core.data.HostSettingsRepository
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.CoreInfo
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.MachineState
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalPreferences
import com.mtajchert.flocket.core.session.CoreSession
import com.mtajchert.flocket.core.session.InterruptOutcome
import com.mtajchert.flocket.core.session.RunRequest
import com.mtajchert.flocket.core.ui.error.UiError
import com.mtajchert.flocket.core.ui.error.UiErrorAction
import com.mtajchert.flocket.core.ui.error.UiText
import com.mtajchert.flocket.core.ui.error.toUiError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-shot things the screen reacts to. */
sealed interface TerminalsEvent {
    data class Info(
        val text: UiText,
    ) : TerminalsEvent

    data class Failure(
        val error: UiError,
    ) : TerminalsEvent

    /** The mutation may or may not have happened. Never retried automatically. */
    data object OutcomeUnknown : TerminalsEvent

    data class OpenTerminal(
        val hostId: HostId,
        val address: String,
    ) : TerminalsEvent

    /** Host-level actions that live on the host's own screen (trust a key, enter a password, edit). */
    data class OpenHost(
        val hostId: HostId,
        val action: UiErrorAction,
    ) : TerminalsEvent
}

@HiltViewModel
class TerminalsViewModel
    @Inject
    constructor(
        private val connectionManager: ConnectionManager,
        private val lastCwd: LastCwdStore,
        private val hostSettings: HostSettingsRepository,
    ) : ViewModel() {
        private val controls = MutableStateFlow(ListControls())
        private val refreshing = MutableStateFlow(false)
        private val dialogState = MutableStateFlow<TerminalDialog?>(null)
        private val eventChannel = Channel<TerminalsEvent>(Channel.BUFFERED)

        val events: Flow<TerminalsEvent> = eventChannel.receiveAsFlow()
        val dialog: StateFlow<TerminalDialog?> = dialogState

        val uiState: StateFlow<TerminalsUiState> =
            combine(connectionManager.overview, controls, refreshing, ::buildUiState)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TerminalsUiState())

        // ---- list controls ----------------------------------------------------------------------

        fun setFilter(filter: TerminalFilter) = controls.update { it.copy(filter = filter) }

        fun setAttentionFirst(value: Boolean) = controls.update { it.copy(attentionFirst = value) }

        fun setShowFinished(value: Boolean) = controls.update { it.copy(showFinished = value) }

        fun clearFilters() = controls.update { it.copy(filter = TerminalFilter.All, showFinished = true) }

        /** Pull-to-refresh: asks every host that is not online to retry now. Online hosts push their own updates. */
        fun refresh() {
            viewModelScope.launch {
                refreshing.value = true
                connectionManager.overview.value
                    .filter { it.session.connection !is ConnectionState.Online }
                    .forEach {
                        // Also wakes hosts that don't connect on launch.
                        connectionManager.connect(it.host.id)
                        connectionManager.session(it.host.id)?.retryNow()
                    }
                delay(REFRESH_FEEDBACK_MS)
                refreshing.value = false
            }
        }

        fun onHostAction(
            hostId: HostId,
            action: UiErrorAction,
        ) {
            if (action == UiErrorAction.Retry) {
                connectionManager.connect(hostId)
                connectionManager.session(hostId)?.retryNow()
            } else {
                send(TerminalsEvent.OpenHost(hostId, action))
            }
        }

        // ---- row actions ------------------------------------------------------------------------

        fun dismissDialog() {
            dialogState.value = null
        }

        fun requestKill(item: TerminalItem) {
            if (guardWritable(item)) dialogState.value = TerminalDialog.ConfirmKill(item)
        }

        fun confirmKill() {
            val item = (dialogState.value as? TerminalDialog.ConfirmKill)?.item ?: return
            dialogState.value = null
            viewModelScope.launch {
                val session = sessionFor(item) ?: return@launch
                report(
                    session.kill(TerminalAddress(item.address)),
                    UiText.Res(R.string.msg_killed, listOf(item.address)),
                )
            }
        }

        fun remove(item: TerminalItem) {
            if (!guardWritable(item)) return
            viewModelScope.launch {
                val session = sessionFor(item) ?: return@launch
                report(
                    session.remove(TerminalAddress(item.address)),
                    UiText.Res(R.string.msg_removed, listOf(item.address)),
                )
            }
        }

        fun interrupt(item: TerminalItem) {
            if (!guardWritable(item)) return
            viewModelScope.launch {
                val session = sessionFor(item) ?: return@launch
                when (val result = session.interrupt(TerminalAddress(item.address))) {
                    is AppResult.Success -> {
                        val text =
                            when (result.value) {
                                InterruptOutcome.Stopped -> R.string.msg_interrupt_stopped
                                InterruptOutcome.Exited -> R.string.msg_interrupt_exited
                                InterruptOutcome.StillWorking -> R.string.msg_interrupt_still_working
                                InterruptOutcome.Unknown -> R.string.msg_interrupt_sent
                            }
                        send(TerminalsEvent.Info(UiText.Res(text)))
                    }

                    is AppResult.Failure -> {
                        reportError(result.error)
                    }
                }
            }
        }

        fun requestSend(item: TerminalItem) {
            if (guardWritable(item)) dialogState.value = TerminalDialog.SendText(item)
        }

        fun onSendTextChange(text: String) {
            dialogState.update { d ->
                if (d is TerminalDialog.SendText &&
                    d.phase !is SendPhase.Queued
                ) {
                    d.copy(text = text, phase = SendPhase.Editing)
                } else {
                    d
                }
            }
        }

        /** Sends the typed text. [force] is only set after the user saw the "program is blocked" warning. */
        fun sendText(force: Boolean = false) {
            val d = dialogState.value as? TerminalDialog.SendText ?: return
            if (d.text.isBlank() || d.phase is SendPhase.Queued) return
            dialogState.value = d.copy(phase = SendPhase.Queued)
            viewModelScope.launch {
                val session = sessionFor(d.item)
                val phase =
                    if (session == null) {
                        SendPhase.Failed(AppError.ConnectionLost.toUiError())
                    } else {
                        when (val r = session.sendText(TerminalAddress(d.item.address), d.text, force)) {
                            is AppResult.Success -> SendPhase.Sent(r.value.statusWord)
                            is AppResult.Failure -> r.error.toSendPhase()
                        }
                    }
                val showing = (dialogState.value as? TerminalDialog.SendText)?.item?.key == d.item.key
                if (showing) {
                    dialogState.update { current ->
                        (current as? TerminalDialog.SendText)?.copy(phase = phase)
                            ?: current
                    }
                } else {
                    // The dialog was closed while the call was queued: report the result as a message instead.
                    when (phase) {
                        is SendPhase.Sent -> send(TerminalsEvent.Info(UiText.Res(R.string.send_sent)))
                        SendPhase.OutcomeUnknown -> send(TerminalsEvent.OutcomeUnknown)
                        is SendPhase.Failed -> send(TerminalsEvent.Failure(phase.error))
                        is SendPhase.Blocked -> send(TerminalsEvent.Info(UiText.Plain(phase.message)))
                        else -> Unit
                    }
                }
            }
        }

        private fun AppError.toSendPhase(): SendPhase =
            when {
                this is AppError.OutcomeUnknown -> SendPhase.OutcomeUnknown
                this is AppError.Rpc && kind == AppError.Rpc.BLOCKED -> SendPhase.Blocked(message)
                else -> SendPhase.Failed(toUiError())
            }

        // ---- new terminal -----------------------------------------------------------------------

        fun openNewTerminal(preferredHost: HostId? = null) {
            val choices = hostChoices()
            val host = choices.firstOrNull { it.id == preferredHost } ?: choices.firstOrNull()
            val form = NewTerminalForm(hosts = choices)
            dialogState.value = TerminalDialog.NewTerminal(form)
            if (host != null) selectHost(host.id)
        }

        fun selectHost(hostId: HostId) {
            val choice = hostChoices().firstOrNull { it.id == hostId } ?: return
            // Fallback until the configured folder is read: last used on this host, then home.
            val fallback = lastCwd.get(hostId) ?: NewTerminalForm.DEFAULT_CWD
            updateForm {
                it.copy(
                    hostId = hostId,
                    machine = null,
                    source = TerminalSource.Shell,
                    cwd = fallback,
                    templates = if (choice.supportsTemplates) TemplatesState.Loading else TemplatesState.Unsupported,
                    error = null,
                )
            }
            viewModelScope.launch {
                val configured =
                    hostSettings
                        .effective(hostId)
                        .first()
                        .terminal.defaultWorkingDirectory
                        .trim()
                // The configured default folder (host override, else app default) always wins over last used;
                // "~" is the unset value. Only while the user hasn't typed their own folder yet.
                if (configured.isNotEmpty() && configured != TerminalPreferences.DEFAULT_WORKING_DIRECTORY) {
                    updateForm { if (it.hostId == hostId && it.cwd == fallback) it.copy(cwd = configured) else it }
                }
            }
            if (choice.supportsTemplates) {
                viewModelScope.launch {
                    val state =
                        when (val r = connectionManager.session(hostId)?.templates()) {
                            is AppResult.Success -> TemplatesState.Loaded(r.value.filter { it.error == null })
                            else -> TemplatesState.Failed
                        }
                    updateForm { if (it.hostId == hostId) it.copy(templates = state) else it }
                }
            }
        }

        fun updateForm(transform: (NewTerminalForm) -> NewTerminalForm) {
            dialogState.update { d ->
                if (d is TerminalDialog.NewTerminal && !d.form.submitting) d.copy(form = transform(d.form)) else d
            }
        }

        fun submitNewTerminal() {
            val d = dialogState.value as? TerminalDialog.NewTerminal ?: return
            val form = d.form
            if (form.submitting) return
            if (!form.isValid) {
                updateForm { it.copy(showErrors = true) }
                return
            }
            val hostId = form.hostId ?: return
            val session = connectionManager.session(hostId)
            val online = session?.state?.value?.connection is ConnectionState.Online
            if (session == null || !online) {
                updateForm { it.copy(error = AppError.ConnectionLost.toUiError()) }
                return
            }
            val cwd = form.cwd.trim()
            val request =
                RunRequest(
                    line = (form.source as? TerminalSource.Command)?.let { form.command.trim() },
                    template = (form.source as? TerminalSource.Template)?.name,
                    cwd = cwd,
                    name = form.name.trim().ifEmpty { null },
                    machine = form.machine,
                    keep = form.keep,
                )
            dialogState.value = d.copy(form = form.copy(submitting = true, error = null))
            viewModelScope.launch {
                when (val r = session.run(request)) {
                    is AppResult.Success -> {
                        lastCwd.put(hostId, cwd)
                        dialogState.value = null
                        send(TerminalsEvent.OpenTerminal(hostId, r.value.address.value))
                    }

                    is AppResult.Failure -> {
                        val error = r.error
                        dialogState.update { cur ->
                            if (cur is TerminalDialog.NewTerminal) {
                                cur.copy(form = cur.form.copy(submitting = false, error = error.toUiError()))
                            } else {
                                cur
                            }
                        }
                    }
                }
            }
        }

        // ---- helpers ----------------------------------------------------------------------------

        private fun hostChoices(): List<HostChoice> =
            connectionManager.overview.value
                .filter { it.session.connection is ConnectionState.Online }
                .map { it.toChoice() }

        private fun HostOverview.toChoice() =
            HostChoice(
                id = host.id,
                label = host.label,
                machines = session.machines.filter { it.state == MachineState.Connected }.map { it.name },
                supportsTemplates = session.core?.supports(CoreInfo.TEMPLATES_LIST) == true,
            )

        private fun sessionFor(item: TerminalItem): CoreSession? = connectionManager.session(item.hostId)

        private fun guardWritable(item: TerminalItem): Boolean {
            val online =
                connectionManager.overview.value
                    .firstOrNull { it.host.id == item.hostId }
                    ?.session
                    ?.connection is ConnectionState.Online
            if (!online) send(TerminalsEvent.Info(UiText.Res(R.string.msg_read_only)))
            return online
        }

        private fun report(
            result: AppResult<*>,
            success: UiText,
        ) {
            when (result) {
                is AppResult.Success -> send(TerminalsEvent.Info(success))
                is AppResult.Failure -> reportError(result.error)
            }
        }

        private fun reportError(error: AppError) =
            send(
                if (error is AppError.OutcomeUnknown) {
                    TerminalsEvent.OutcomeUnknown
                } else {
                    TerminalsEvent.Failure(
                        error.toUiError(),
                    )
                },
            )

        private fun send(event: TerminalsEvent) {
            eventChannel.trySend(event)
        }

        private companion object {
            const val REFRESH_FEEDBACK_MS = 800L
        }
    }
