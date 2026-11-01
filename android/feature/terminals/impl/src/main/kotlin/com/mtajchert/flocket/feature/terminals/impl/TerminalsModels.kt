package com.mtajchert.flocket.feature.terminals.impl

import androidx.compose.runtime.Immutable
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.MachineState
import com.mtajchert.flocket.core.model.TerminalRef
import com.mtajchert.flocket.core.model.TerminalTemplate
import com.mtajchert.flocket.core.ui.error.UiError

/** Which terminals the list shows. Finished ones additionally need [TerminalsUiState.showFinished] (or [Finished]). */
enum class TerminalFilter { All, NeedsAttention, Working, Finished }

enum class LivenessKind { Running, Waiting, Finished }

/** One row. Plain values only, so a row recomposes only when something it shows changed. */
@Immutable
data class TerminalItem(
    val ref: TerminalRef,
    /** Stable list key; unique across hosts, sessions and federated machines. */
    val key: String,
    val agentLabel: String,
    val name: String?,
    val status: AgentStatus,
    /** The terminal's title, else its `where` label, else empty. */
    val headline: String,
    /** Display folder, home shortened to `~`. */
    val cwd: String,
    /** When the status changed (or the program finished), if known. */
    val sinceMs: Long?,
    val liveness: LivenessKind,
    val exitCode: Int?,
    val signal: Int?,
    val urgency: Int,
    /** False while the host is not online: all write actions are disabled. */
    val writable: Boolean,
) {
    val hostId: HostId get() = ref.endpoint.hostId
    val address: String get() = ref.address.value
    val isFinished: Boolean get() = liveness == LivenessKind.Finished
}

@Immutable
data class MachineSection(
    /** Federated machine name (`rohan` in `rohan/t7`), null for the host's own terminals. */
    val machine: String?,
    val machineState: MachineState?,
    val machineStateRaw: String?,
    val items: List<TerminalItem>,
)

@Immutable
data class HostGroup(
    val hostId: HostId,
    val label: String,
    val connection: ConnectionState,
    val confirmedAtMs: Long?,
    /** Set when [connection] is NeedsAttention: what happened and what the user can do about it. */
    val error: UiError?,
    val sections: List<MachineSection>,
    /** All terminals the host has, before filtering. */
    val total: Int,
) {
    val online: Boolean get() = connection is ConnectionState.Online
    val visibleCount: Int get() = sections.sumOf { it.items.size }
}

enum class EmptyKind { NoHosts, NoTerminals, AllOffline, NoMatches }

@Immutable
data class TerminalsUiState(
    val loaded: Boolean = false,
    val groups: List<HostGroup> = emptyList(),
    val filter: TerminalFilter = TerminalFilter.All,
    val attentionFirst: Boolean = true,
    val showFinished: Boolean = false,
    val refreshing: Boolean = false,
    val emptyKind: EmptyKind? = null,
    /** At least one host is online, so a terminal can be started. */
    val canCreate: Boolean = false,
    val hiddenFinished: Int = 0,
)

sealed interface TerminalDialog {
    data class ConfirmKill(
        val item: TerminalItem,
    ) : TerminalDialog

    data class SendText(
        val item: TerminalItem,
        val text: String = "",
        val phase: SendPhase = SendPhase.Editing,
    ) : TerminalDialog

    data class NewTerminal(
        val form: NewTerminalForm,
    ) : TerminalDialog
}

sealed interface SendPhase {
    data object Editing : SendPhase

    /** The core waits for ~3 s of quiet before pasting, so this can last a while. */
    data object Queued : SendPhase

    /** The core refused because the program is blocked; resending needs an explicit `force`. */
    data class Blocked(
        val message: String,
    ) : SendPhase

    data class Sent(
        val statusWord: String?,
    ) : SendPhase

    data class Failed(
        val error: UiError,
    ) : SendPhase

    /** The connection broke mid-call: never retried automatically. */
    data object OutcomeUnknown : SendPhase
}

@Immutable
data class HostChoice(
    val id: HostId,
    val label: String,
    /** Connected federated machines this host can start terminals on. */
    val machines: List<String>,
    val supportsTemplates: Boolean,
)

sealed interface TemplatesState {
    data object Unsupported : TemplatesState

    data object Loading : TemplatesState

    data class Loaded(
        val templates: List<TerminalTemplate>,
    ) : TemplatesState

    data object Failed : TemplatesState
}

sealed interface TerminalSource {
    data object Shell : TerminalSource

    data class Template(
        val name: String,
    ) : TerminalSource

    data object Command : TerminalSource
}

@Immutable
data class NewTerminalForm(
    val hosts: List<HostChoice> = emptyList(),
    val hostId: HostId? = null,
    /** Null = the host's own machine. */
    val machine: String? = null,
    val source: TerminalSource = TerminalSource.Shell,
    val command: String = "",
    val cwd: String = DEFAULT_CWD,
    val name: String = "",
    val keep: Boolean = false,
    val templates: TemplatesState = TemplatesState.Unsupported,
    val submitting: Boolean = false,
    /** Validation messages appear only after the first submit attempt. */
    val showErrors: Boolean = false,
    val error: UiError? = null,
) {
    val host: HostChoice? get() = hosts.firstOrNull { it.id == hostId }
    val hostMissing: Boolean get() = hostId == null
    val cwdMissing: Boolean get() = cwd.isBlank()
    val commandMissing: Boolean get() = source is TerminalSource.Command && command.isBlank()
    val isValid: Boolean get() = !hostMissing && !cwdMissing && !commandMissing

    companion object {
        const val DEFAULT_CWD = "~"
    }
}

/** Ordering for "needs attention first": blocked/error, working, done/idle, no status, finished. */
internal fun urgencyOf(
    status: AgentStatus,
    finished: Boolean,
): Int =
    when {
        finished -> -2
        status is AgentStatus.Reported -> status.state.urgency
        else -> -1
    }

internal fun TerminalItem.needsAttention(): Boolean =
    !isFinished && (status as? AgentStatus.Reported)?.state.let { it == AgentState.Blocked || it == AgentState.Error }

internal fun TerminalItem.isWorking(): Boolean =
    !isFinished && (status as? AgentStatus.Reported)?.state == AgentState.Working
