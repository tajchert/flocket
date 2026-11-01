package com.mtajchert.flocket.core.model

/**
 * What a core told us in `hello` (protocol/SPEC.md §2.1). [id] is stable across core restarts;
 * [version] is informational only — compatibility is decided by generation, features and methods.
 */
data class CoreInfo(
    val id: String,
    val name: String,
    val version: String,
    val generation: Int,
    val features: Set<String>,
    val methods: Set<String>,
) {
    fun supports(method: String): Boolean = method in methods

    companion object Methods {
        const val TERMINAL_RUN = "terminal.run"
        const val TERMINAL_KILL = "terminal.kill"
        const val TERMINAL_RM = "terminal.rm"
        const val TERMINAL_SEND = "terminal.send"
        const val TERMINAL_KEYS = "terminal.keys"
        const val TERMINAL_INTERRUPT = "terminal.interrupt"
        const val TERMINAL_LABEL = "terminal.label"
        const val TERMINAL_READ = "terminal.read"
        const val TEMPLATES_LIST = "templates.list"
        const val MACHINE_LIST = "machine.list"
        const val MACHINE_EXEC = "machine.exec"
    }
}

/** A machine federated into the core (`machine.list`, protocol/SPEC.md §10). */
data class MachineInfo(
    val name: String,
    val state: MachineState,
    val rawState: String,
    val version: String? = null,
    val temporary: Boolean = false,
    val disconnectedSinceMs: Long? = null,
    val why: String? = null,
)

enum class MachineState {
    Connecting,
    Connected,
    NeedsLogin,
    NeedsUpdate,
    Unreachable,
    Other,
    ;

    companion object {
        fun fromWire(word: String): MachineState =
            when (word) {
                "connecting" -> Connecting
                "connected" -> Connected
                "needs_login" -> NeedsLogin
                "needs_update" -> NeedsUpdate
                "unreachable" -> Unreachable
                else -> Other
            }
    }
}

/** A runnable template offered by the core (`templates.list`). */
data class TerminalTemplate(
    val name: String,
    val description: String,
    val command: String,
    val error: String?,
)
