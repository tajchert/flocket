@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.core.testing

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.data.HostOverview
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.BlockKind
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.CoreEndpoint
import com.mtajchert.flocket.core.model.CoreInfo
import com.mtajchert.flocket.core.model.DeviceKey
import com.mtajchert.flocket.core.model.ForegroundProcess
import com.mtajchert.flocket.core.model.Host
import com.mtajchert.flocket.core.model.HostAuth
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.MachineInfo
import com.mtajchert.flocket.core.model.MachineState
import com.mtajchert.flocket.core.model.PinnedHostKey
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalRecord
import com.mtajchert.flocket.core.model.TerminalRef
import com.mtajchert.flocket.core.session.SessionState

/** Deterministic, realistic fixtures for previews, screenshot tests and unit tests. */
object SampleData {
    const val NOW_MS = 1_767_225_600_000L

    val coreInfo =
        CoreInfo(
            id = "core-studio",
            name = "studio-mac",
            version = "0.9.2",
            generation = 1,
            features = setOf("events"),
            methods = setOf(CoreInfo.TERMINAL_RUN, CoreInfo.MACHINE_LIST),
        )

    val deviceKey =
        DeviceKey(
            alias = "key-1",
            publicKeyOpenSsh = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOb7Qk3mXhYw1fP9sT2dVu6rJc0LzAeN8gKq4yH5BtWn flocket-pixel",
            fingerprint = "SHA256:4Hq8Zr1mXjT0bK9vYcWd2eLpN5oUaS7gFiR3tEyBxM0",
            keyType = "ssh-ed25519",
            createdAtMs = NOW_MS - 86_400_000L,
        )

    val hostKeyUnknown =
        AppError.HostKeyUnknown(
            host = "studio.tail1234.ts.net",
            keyType = "ssh-ed25519",
            fingerprint = "SHA256:9fT2Kx0aPq3LmZ7vBn5RcYd8WuEh1JgSoXiA4tNbVeM",
        )

    val hostKeyChanged =
        AppError.HostKeyChanged(
            host = "studio.tail1234.ts.net",
            keyType = "ssh-ed25519",
            expectedFingerprint = "SHA256:9fT2Kx0aPq3LmZ7vBn5RcYd8WuEh1JgSoXiA4tNbVeM",
            actualFingerprint = "SHA256:Zc7Lq1WnB4yTe9RmKx2VhPd0SgAo5UjFiN8tXbYvE3M",
        )

    val pinnedKey =
        PinnedHostKey("studio.tail1234.ts.net", 22, "ssh-ed25519", hostKeyUnknown.fingerprint, NOW_MS - 3_600_000L)

    val studio = Host(HostId("studio"), "Studio Mac", "studio.tail1234.ts.net", 22, "mtajchert", HostAuth.Key("key-1"))
    val hetzner =
        Host(
            HostId("hetzner"),
            "Hetzner build box",
            "build.example.org",
            2222,
            "deploy",
            HostAuth.Key("key-1"),
            "/usr/local/bin/hc",
            "ci",
        )
    val pi = Host(HostId("pi"), "Raspberry Pi", "192.168.1.42", 22, "pi", HostAuth.Password)
    val staleHost = hetzner
    val hosts = listOf(studio, hetzner, pi)

    private fun ref(
        host: Host,
        address: String,
    ) = TerminalRef(CoreEndpoint(host.id, host.session), TerminalAddress(address))

    fun terminal(
        host: Host = studio,
        address: String,
        name: String?,
        argv: List<String>,
        status: AgentStatus = AgentStatus.Unknown,
        liveness: Liveness = Liveness.Running,
        cwd: String = "/Users/mtajchert/code/flocket",
        title: String = "",
    ) = TerminalRecord(
        ref = ref(host, address),
        name = name,
        argv = argv,
        cwd = cwd,
        liveness = liveness,
        status = status,
        title = title,
        cols = 120,
        rows = 40,
        createdAtMs = NOW_MS - 7_200_000L,
        statusChangedAtMs = NOW_MS - 300_000L,
        foreground = ForegroundProcess(1234, argv.first().substringAfterLast('/'), argv, cwd),
    )

    private fun reported(
        state: AgentState,
        app: String?,
        kind: BlockKind? = null,
        progress: Int? = null,
        message: String? = null,
    ) = AgentStatus.Reported(state, state.name.lowercase(), kind, progress, app, null, message)

    val terminals =
        listOf(
            terminal(
                address = "t1",
                name = "refactor-auth",
                argv = listOf("claude"),
                status = reported(AgentState.Working, "claude", progress = 62),
            ),
            terminal(
                address = "t2",
                name = "fix-ci",
                argv = listOf("codex"),
                status =
                    reported(
                        AgentState.Blocked,
                        "codex",
                        BlockKind.Permission,
                        message = "Allow running `npm test`?",
                    ),
            ),
            terminal(
                address = "t3",
                name = "docs",
                argv = listOf("claude"),
                status = reported(AgentState.Done, "claude"),
            ),
            terminal(
                address = "t4",
                name = "scratch",
                argv = listOf("/bin/zsh"),
                status = reported(AgentState.Idle, null),
            ),
            terminal(address = "t5", name = null, argv = listOf("/bin/zsh")),
            terminal(
                address = "t6",
                name = "old-run",
                argv = listOf("codex"),
                status = reported(AgentState.Done, "codex"),
                liveness = Liveness.Finished("exit", 0, null, NOW_MS - 600_000L),
            ),
            terminal(
                address = "rohan/t7",
                name = "benchmarks",
                argv = listOf("claude"),
                status = reported(AgentState.Error, "claude"),
            ),
        )

    val machines =
        listOf(
            MachineInfo("rohan", MachineState.Connected, "connected", "0.9.1"),
            MachineInfo(
                "lab-gpu",
                MachineState.Unreachable,
                "unreachable",
                why = "timed out",
                disconnectedSinceMs =
                    NOW_MS - 900_000L,
            ),
            MachineInfo("old-nas", MachineState.NeedsUpdate, "needs_update", "0.7.0"),
        )

    val studioOnline =
        SessionState(studio.endpoint, ConnectionState.Online(coreInfo), coreInfo, terminals, machines, NOW_MS)
    val hetznerReconnecting =
        SessionState(
            hetzner.endpoint,
            ConnectionState.Reconnecting(2, AppError.ConnectionLost, NOW_MS + 12_000L),
            coreInfo.copy(id = "core-hetzner", name = "build-box", version = "0.9.0"),
            terminals.take(2).map { it.copy(ref = it.ref.copy(endpoint = hetzner.endpoint)) },
            emptyList(),
            NOW_MS - 600_000L,
        )
    val piNeedsTrust = SessionState(pi.endpoint, ConnectionState.NeedsAttention(hostKeyUnknown))

    val overview =
        listOf(
            HostOverview(studio, studioOnline),
            HostOverview(hetzner, hetznerReconnecting),
            HostOverview(pi, piNeedsTrust),
        )

    /**
     * A Claude Code session in ~/code/flocket as terminal bytes (fits 50 columns): a diff and, unless [exited],
     * "Running tests…" with the cursor in the input box.
     */
    fun claudeCodeTranscript(exited: Boolean = false): String {
        val e = "\u001b"
        val s = StringBuilder()
        s.append("$e[1;38;5;75m~/code/flocket$e[0m on $e[35m main$e[0m\r\n")
        s.append("$e[32m❯$e[0m claude\r\n\r\n")
        s.append("$e[38;5;208m╭──────────────────────────────────────────╮$e[0m\r\n")
        s.append("$e[38;5;208m│$e[0m $e[1m✻ Welcome to Claude Code$e[0m                 $e[38;5;208m│$e[0m\r\n")
        s.append("$e[38;5;208m│$e[0m   $e[2mcwd: ~/code/flocket$e[0m                    $e[38;5;208m│$e[0m\r\n")
        s.append("$e[38;5;208m╰──────────────────────────────────────────╯$e[0m\r\n\r\n")
        s.append("$e[1m>$e[0m Refactor the auth module to use the new\r\n  token store\r\n\r\n")
        s.append("$e[32m●$e[0m I'll start by reading the current module.\r\n\r\n")
        s.append("$e[32m●$e[0m $e[1mRead$e[0m(core/security/KeyVault.kt)\r\n")
        s.append("  $e[2m⎿  Read 214 lines$e[0m\r\n\r\n")
        s.append("$e[32m●$e[0m $e[1mUpdate$e[0m(core/data/AuthRepository.kt)\r\n")
        s.append("  $e[2m⎿  Updated with 12 additions and 4 removals$e[0m\r\n")
        s.append("     $e[48;5;22;38;5;255m+ private val store = TokenStore(vault)     $e[0m\r\n")
        s.append("     $e[48;5;52;38;5;255m- private var token: String? = null        $e[0m\r\n\r\n")
        if (exited) {
            s.append("$e[32m●$e[0m Done. All 42 tests pass.\r\n\r\n")
            s.append("$e[32m❯$e[0m exit\r\n")
        } else {
            s.append("$e[38;5;208m✻$e[0m Running tests… $e[2m(esc to interrupt)$e[0m\r\n\r\n")
            s.append("$e[2m╭──────────────────────────────────────────╮$e[0m\r\n")
            s.append("$e[2m│$e[0m >                                        $e[2m│$e[0m\r\n")
            s.append("$e[2m╰──────────────────────────────────────────╯$e[0m$e[1A$e[5G")
        }
        return s.toString()
    }
}
