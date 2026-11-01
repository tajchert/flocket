package com.mtajchert.flocket.core.model

/**
 * A coding agent known to the desktop app, adapted from the `programs` table of Herdr.app 0.1.11
 * (Apache-2.0, see `THIRD_PARTY_NOTICES.md`). Only the agent entries are kept; editors, tools and
 * the generic `agent`, `shell` and `program` entries are left out.
 *
 * [id] is the desktop's id, with `-` kept. [run] is how the agent is started and [match] lists other
 * app names it reports. [tileArgb] and [colorArgb] are the tile and glyph colours.
 */
data class AgentKind(
    val id: String,
    val name: String,
    val run: List<String>,
    val match: List<String>,
    val tint: Boolean,
    val tileArgb: Long,
    val colorArgb: Long,
)

object AgentCatalog {
    val agents: List<AgentKind> =
        listOf(
            AgentKind(
                "claude",
                "Claude Code",
                listOf("claude"),
                listOf("claude-code"),
                false,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "codex",
                "Codex",
                listOf("codex"),
                emptyList(),
                false,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "pi",
                "Pi",
                listOf("pi"),
                emptyList(),
                true,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "opencode",
                "OpenCode",
                listOf("opencode"),
                emptyList(),
                true,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "cursor",
                "Cursor",
                listOf("cursor-agent"),
                listOf("cursor-agent"),
                true,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "copilot",
                "Copilot",
                listOf("copilot"),
                listOf("github-copilot"),
                true,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "amp",
                "Amp",
                listOf("amp"),
                emptyList(),
                false,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "grok",
                "Grok",
                listOf("grok"),
                emptyList(),
                true,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "antigravity",
                "Antigravity",
                listOf("agy"),
                listOf("agy"),
                false,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "kimi",
                "Kimi",
                listOf("kimi"),
                emptyList(),
                false,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "kiro",
                "Kiro",
                listOf("kiro-cli"),
                listOf("kiro-cli"),
                false,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "hermes",
                "Hermes",
                listOf("hermes"),
                emptyList(),
                true,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "mastracode",
                "Mastra Code",
                emptyList(),
                emptyList(),
                true,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "qoder",
                "Qoder",
                listOf("qodercli"),
                listOf("qodercli"),
                true,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "muse",
                "Muse",
                emptyList(),
                emptyList(),
                true,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "crush",
                "Crush",
                listOf("crush"),
                emptyList(),
                false,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "command-code",
                "Command Code",
                emptyList(),
                emptyList(),
                true,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "prime-agent",
                "Prime",
                emptyList(),
                emptyList(),
                true,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "gemini",
                "Gemini",
                listOf("gemini"),
                emptyList(),
                true,
                0xFF8E75B2L,
                0xFFFFFFFFL,
            ),
            AgentKind(
                "ollama",
                "Ollama",
                emptyList(),
                emptyList(),
                true,
                0xFF1C1D21L,
                0xFFFFFFFFL,
            ),
        )

    /** Launchers that wrap the real program; skipped when looking for the program in `argv`. */
    val interpreters: Set<String> =
        setOf(
            "node",
            "bun",
            "bunx",
            "deno",
            "python",
            "python3",
            "npx",
            "pnpm",
            "yarn",
            "uv",
            "uvx",
            "ruby",
            "bundle",
            "env",
            "sudo",
            "nice",
            "time",
            "watch",
            "nodemon",
        )

    /**
     * The agent a terminal runs, or null for a generic terminal tile. Uses the reported app name
     * first, then the first `argv` entry that is not an interpreter. Never uses the foreground
     * process.
     */
    fun of(record: TerminalRecord): AgentKind? {
        val app = (record.status as? AgentStatus.Reported)?.app
        if (app != null) {
            agents.firstOrNull { app == it.id || app in it.match }?.let { return it }
        }
        val program =
            record.argv
                .map { it.substringAfterLast('/') }
                .firstOrNull { it.isNotEmpty() && it !in interpreters }
                ?: return null
        return agents.firstOrNull { program in it.run }
    }
}
