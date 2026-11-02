package com.mtajchert.flocket.feature.terminal.impl

import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.BellMode
import com.mtajchert.flocket.core.model.BlockKind
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.LayoutRef
import com.mtajchert.flocket.core.model.TerminalColorScheme
import com.mtajchert.flocket.core.terminal.engine.Key
import com.mtajchert.flocket.core.terminal.engine.KeyInput
import com.mtajchert.flocket.core.ui.error.UiError
import com.mtajchert.flocket.core.ui.error.UiText

/** Where the screen is in the attach lifecycle. The retained terminal content stays visible in every phase. */
sealed interface TerminalPhase {
    /** Waiting for the host's session (startup, process death) or for it to come Online. */
    data object Connecting : TerminalPhase

    /** `terminal.attach` in flight. */
    data object Attaching : TerminalPhase

    /** Bound to a stream (which may be temporarily reconnecting: see [TerminalUiState.streamConnected]). */
    data object Live : TerminalPhase

    /** The program exited or was killed. */
    data class Exited(
        val exitCode: Int?,
        val signal: Int?,
        /** The core's reason, e.g. `killed` (a kill may still report exit code 1). */
        val reason: String? = null,
    ) : TerminalPhase

    /** A kept terminal lost its program; re-attaches automatically when the core restarts the shell. */
    data object WaitingForShell : TerminalPhase

    /** The core closed the stream without a reason, or the terminal is gone. */
    data object StreamEnded : TerminalPhase

    /** Attaching failed for another reason; the user can retry. */
    data class Failed(
        val error: UiError,
    ) : TerminalPhase

    /** The host was deleted. */
    data object HostMissing : TerminalPhase
}

data class TerminalUiState(
    val address: String,
    val hostLabel: String = "",
    /** Federated machine name (`rohan` in `rohan/t7`), null for the host's own machine. */
    val machine: String? = null,
    val agentLabel: String = "",
    /** Program title (OSC 0/2) or the record's `where` / name, whichever is known. */
    val title: String = "",
    val status: AgentStatus = AgentStatus.Unknown,
    val connection: ConnectionState = ConnectionState.Idle,
    val phase: TerminalPhase = TerminalPhase.Connecting,
    /** The bound stream's link is live (false = show the reconnecting chip over the retained screen). */
    val streamConnected: Boolean = false,
    /** This viewer sets the terminal's size. "Fit to screen" is offered only while passive. */
    val isOwner: Boolean = false,
    /** Shown once after a kept terminal came back with a new shell. */
    val freshShell: Boolean = false,
    val fontSizeSp: Float = 13f,
    val showExtraKeys: Boolean = true,
    /** Full-screen mode: no top bar or tab strip, a small corner button brings them back. */
    val topBarHidden: Boolean = false,
    val keepScreenOn: Boolean = true,
    val colorScheme: TerminalColorScheme = TerminalColorScheme.FollowApp,
    val bell: BellMode = BellMode.Haptic,
    /** A program asked to set the clipboard (OSC 52): ask before copying. */
    val clipboardRequest: String? = null,
    val confirmKill: Boolean = false,
    val rename: RenameUi? = null,
    /**
     * Attached and the notification permission was never asked: the route may ask once (ADR-0006, the
     * keep-alive notification). The route still checks the platform state before showing anything.
     */
    val offerNotificationPermission: Boolean = false,
) {
    val isOnline: Boolean get() = connection is ConnectionState.Online

    /** Input-affecting actions (paste, interrupt, kill, keyboard) make sense only on a live stream. */
    val canInteract: Boolean get() = phase == TerminalPhase.Live && streamConnected

    /** Another viewer took the size (we always attach as owner, ADR-0007): drawn scaled down until we take it back. */
    val sizeTakenByOther: Boolean get() = canInteract && !isOwner
}

/** The open rename dialog for a tab of the strip. */
data class RenameUi(
    val ref: LayoutRef,
    /** The tab's automatic name, shown when the field is empty. */
    val placeholder: String,
    val text: String,
)

/** One-shot messages for the snackbar, and navigation. */
sealed interface TerminalScreenEvent {
    data class Message(
        val text: UiText,
    ) : TerminalScreenEvent

    data class Failure(
        val error: UiError,
    ) : TerminalScreenEvent

    /** A write's outcome is unknown: it may not have been delivered. Never retried. */
    data object OutcomeUnknown : TerminalScreenEvent

    /** Copy [text] to the clipboard (screen text, or an approved OSC 52 request). */
    data class CopyToClipboard(
        val text: String,
    ) : TerminalScreenEvent

    /** Leave the screen (after Close / Remove). */
    data object Close : TerminalScreenEvent
}

/** A one-tap reply above the extra keys; sent like an extra key (at most once, never replayed). */
enum class ReplyKey(
    /** What the chip's badge shows. */
    val badge: String,
    val keyInput: KeyInput,
) {
    One("1", KeyInput(Key.Character, "1")),
    Two("2", KeyInput(Key.Character, "2")),
    Three("3", KeyInput(Key.Character, "3")),

    /** Agents stop the current step on Esc. */
    Escape("Esc", KeyInput(Key.Escape)),
    ;

    companion object {
        /**
         * Chips for a status: a blocked agent gets the first three menu numbers (its own menu says
         * what each means), a working one Esc to interrupt. Sign-in prompts and other states get none.
         */
        fun forStatus(status: AgentStatus): List<ReplyKey> {
            val reported = status as? AgentStatus.Reported ?: return emptyList()
            return when (reported.state) {
                AgentState.Blocked -> if (reported.kind == BlockKind.Auth) emptyList() else listOf(One, Two, Three)
                AgentState.Working -> listOf(Escape)
                else -> emptyList()
            }
        }
    }
}
