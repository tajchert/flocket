package com.mtajchert.flocket.core.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.model.AgentCatalog
import com.mtajchert.flocket.core.ui.R

/** The generic tile: a terminal prompt on the catalog's usual tile colour. */
private val GenericTile = Color(0xFF1C1D21)

/**
 * The icon of a coding agent in a rounded tile, as on the desktop. [agentId] is an [AgentCatalog] id;
 * `null` or an unknown id draws the generic terminal tile. Icons come from LobeHub icons (MIT, see
 * `THIRD_PARTY_NOTICES.md`). [contentDescription] defaults to the agent name or "Terminal"; pass `null`
 * where a text label next to the icon already names the terminal, so TalkBack does not read it twice.
 */
@Composable
fun AgentIcon(
    agentId: String?,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    contentDescription: String? = agentIconDescription(agentId),
) {
    val agent = remember(agentId) { AgentCatalog.agents.firstOrNull { it.id == agentId } }
    val res = agentIconRes(agent?.id)
    val tile = if (agent != null) Color(agent.tileArgb) else GenericTile
    // Tinted icons are drawn in white and recoloured; the others keep their own colours.
    val filter = if (agent != null && agent.tint && res != null) ColorFilter.tint(Color(agent.colorArgb)) else null
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(size / 4)).background(tile),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(res ?: R.drawable.ic_agent_terminal),
            contentDescription = contentDescription,
            colorFilter = if (res == null) null else filter,
            modifier = Modifier.padding(size / 6).size(size * 2 / 3),
        )
    }
}

/** The agent's name, or "Terminal" for the generic tile. */
@Composable
private fun agentIconDescription(agentId: String?): String =
    AgentCatalog.agents.firstOrNull { it.id == agentId }?.name ?: stringResource(R.string.agent_icon_terminal)

/**
 * The drawable of the desktop agent [id] (resource names use `_` for `-`), or null for an unknown id
 * and for agents LobeHub has no icon for (they use the generic tile).
 */
fun agentIconRes(id: String?): Int? =
    when (id) {
        "amp" -> R.drawable.ic_agent_amp
        "antigravity" -> R.drawable.ic_agent_antigravity
        "claude" -> R.drawable.ic_agent_claude
        "codex" -> R.drawable.ic_agent_codex
        "command-code" -> R.drawable.ic_agent_command_code
        "copilot" -> R.drawable.ic_agent_copilot
        "cursor" -> R.drawable.ic_agent_cursor
        "gemini" -> R.drawable.ic_agent_gemini
        "grok" -> R.drawable.ic_agent_grok
        "hermes" -> R.drawable.ic_agent_hermes
        "kimi" -> R.drawable.ic_agent_kimi
        "kiro" -> R.drawable.ic_agent_kiro
        "muse" -> R.drawable.ic_agent_muse
        "ollama" -> R.drawable.ic_agent_ollama
        "opencode" -> R.drawable.ic_agent_opencode
        "pi" -> R.drawable.ic_agent_pi
        "qoder" -> R.drawable.ic_agent_qoder
        else -> null
    }
