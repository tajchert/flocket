package com.mtajchert.flocket.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentCatalogTest {
    private fun term(
        argv: List<String> = emptyList(),
        app: String? = null,
        foreground: ForegroundProcess? = null,
    ) = TerminalRecord(
        ref = TerminalRef(CoreEndpoint(HostId("h1")), TerminalAddress("t1")),
        argv = argv,
        liveness = Liveness.Running,
        status = app?.let { AgentStatus.Reported(AgentState.Working, "working", app = it) } ?: AgentStatus.Unknown,
        cols = 80,
        rows = 24,
        createdAtMs = 0,
        foreground = foreground,
    )

    @Test
    fun `reported app matches by match list`() {
        assertEquals("claude", AgentCatalog.of(term(app = "claude-code"))?.id)
    }

    @Test
    fun `reported app matches by id`() {
        assertEquals("codex", AgentCatalog.of(term(app = "codex"))?.id)
    }

    @Test
    fun `argv basename matches run`() {
        assertEquals("cursor", AgentCatalog.of(term(argv = listOf("/opt/bin/cursor-agent")))?.id)
    }

    @Test
    fun `interpreters are skipped in argv`() {
        assertEquals("codex", AgentCatalog.of(term(argv = listOf("node", "/x/bin/codex")))?.id)
    }

    @Test
    fun `foreground name is never used`() {
        val fg = ForegroundProcess(1, "claude", listOf("claude"), "/")
        assertNull(AgentCatalog.of(term(argv = listOf("sh"), foreground = fg)))
    }

    @Test
    fun `unknown reported app under node is generic`() {
        assertNull(AgentCatalog.of(term(app = "mytool", argv = listOf("node", "/x/mytool.js"))))
    }

    @Test
    fun `catalog has 20 agents with unique ids`() {
        assertEquals(20, AgentCatalog.agents.size)
        assertEquals(
            20,
            AgentCatalog.agents
                .map { it.id }
                .toSet()
                .size,
        )
    }
}
