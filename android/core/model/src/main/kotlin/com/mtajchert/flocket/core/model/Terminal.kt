package com.mtajchert.flocket.core.model

/**
 * App model of one core terminal record (protocol/SPEC.md §6.2), already reduced from events.
 *
 * Two independent axes, never conflate them:
 * - [liveness]: whether a program process exists (core-observed).
 * - [status]: what the program *reported* via OSC 7501 (may be [AgentStatus.Unknown]).
 */
data class TerminalRecord(
    val ref: TerminalRef,
    val name: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val argv: List<String> = emptyList(),
    /** Start folder. For the folder the shell is in right now use [foreground]`.cwd`. */
    val cwd: String = "",
    val liveness: Liveness,
    val status: AgentStatus = AgentStatus.Unknown,
    /** Increments every time the root status leaves `working`. */
    val turn: Long = 0,
    val title: String = "",
    val cols: Int,
    val rows: Int,
    val createdAtMs: Long,
    /** When the status word last changed, if known (events don't carry it; stamped locally). */
    val statusChangedAtMs: Long? = null,
    val foreground: ForegroundProcess? = null,
    val keep: Boolean = false,
    val template: String? = null,
    val parent: String? = null,
) {
    val address: TerminalAddress get() = ref.address

    /** What `hc ls` shows as "agent": the program's self-reported app name, else argv[0]'s basename. */
    val agentLabel: String
        get() =
            (status as? AgentStatus.Reported)?.app
                ?: argv.firstOrNull()?.substringAfterLast('/')
                ?: ""

    /** Human-readable place from the `where` label convention, if any. */
    val where: String? get() = labels[LABEL_WHERE]

    /** The folder to show: where the foreground process is, else where the terminal started. */
    val displayCwd: String get() = foreground?.cwd ?: cwd

    companion object {
        const val LABEL_WHERE = "where"
    }
}

/** Process-level state of a terminal, as observed by the core. */
sealed interface Liveness {
    /** A program is running (pid known). */
    data object Running : Liveness

    /**
     * Record is live but has no process right now: initial spawn pending, or a kept terminal that
     * lost its program and waits for a fresh shell ([reason] e.g. `holder_lost`, `spawn_pending`).
     * Input is unavailable until the core emits `restarted`.
     */
    data class Waiting(
        val reason: String?,
    ) : Liveness

    /** The program ended. A finished record never comes back to life. */
    data class Finished(
        val reason: String?,
        val exitCode: Int?,
        val signal: Int?,
        val finishedAtMs: Long?,
    ) : Liveness
}

/** Foreground process group leader of a live terminal (polled by the core ~2×/s). */
data class ForegroundProcess(
    val pid: Int,
    val name: String,
    val argv: List<String>,
    val cwd: String,
)

/** Program-reported status (OSC 7501). [Unknown] means the program never reported or cleared it. */
sealed interface AgentStatus {
    data object Unknown : AgentStatus

    data class Reported(
        val state: AgentState,
        /** The raw word as sent by the program (kept for unknown states and diagnostics). */
        val rawState: String,
        val kind: BlockKind? = null,
        val progress: Int? = null,
        val app: String? = null,
        val title: String? = null,
        val message: String? = null,
    ) : AgentStatus
}

enum class AgentState {
    Idle,
    Working,
    Done,
    Blocked,
    Error,

    /** A word this app version doesn't know yet. Show [AgentStatus.Reported.rawState]. */
    Other,
    ;

    /** Ordering used for "needs attention first" lists (matches `hc`'s picker). */
    val urgency: Int
        get() =
            when (this) {
                Blocked, Error -> 3
                Working -> 2
                Done, Idle -> 1
                Other -> 0
            }

    companion object {
        fun fromWire(word: String): AgentState =
            when (word) {
                "idle" -> Idle
                "working" -> Working
                "done" -> Done
                "blocked" -> Blocked
                "error" -> Error
                else -> Other
            }
    }
}

enum class BlockKind {
    Permission,
    Question,
    Auth,
    Other,
    ;

    companion object {
        fun fromWire(word: String): BlockKind =
            when (word) {
                "permission" -> Permission
                "question" -> Question
                "auth" -> Auth
                else -> Other
            }
    }
}
