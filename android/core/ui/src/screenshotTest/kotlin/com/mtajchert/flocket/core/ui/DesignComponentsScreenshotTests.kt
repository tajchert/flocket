package com.mtajchert.flocket.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.flocket.core.designsystem.preview.PreviewComponentThemes
import com.mtajchert.flocket.core.designsystem.theme.FlocketMono
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.designsystem.theme.flocketColors
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.BlockKind
import com.mtajchert.flocket.core.ui.component.AgentTile
import com.mtajchert.flocket.core.ui.component.CountBadge
import com.mtajchert.flocket.core.ui.component.DotFilterChip
import com.mtajchert.flocket.core.ui.component.GroupHeader
import com.mtajchert.flocket.core.ui.component.NeedYouPill
import com.mtajchert.flocket.core.ui.component.StatusGroup
import com.mtajchert.flocket.core.ui.component.agentStatusStyle
import com.mtajchert.flocket.core.ui.component.color
import com.mtajchert.flocket.core.ui.component.title

@Composable
private fun Shot(content: @Composable () -> Unit) {
    FlocketTheme(dynamicColor = false) {
        Surface { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
    }
}

private fun reported(
    state: AgentState,
    kind: BlockKind? = null,
) = AgentStatus.Reported(state, state.name.lowercase(), kind, null)

private val allStatuses =
    listOf(
        reported(AgentState.Blocked, BlockKind.Permission),
        reported(AgentState.Blocked, BlockKind.Question),
        reported(AgentState.Error),
        reported(AgentState.Working),
        reported(AgentState.Done),
        reported(AgentState.Idle),
        AgentStatus.Unknown,
    )

@PreviewTest
@PreviewComponentThemes
@Composable
private fun AgentTilesScreenshot() =
    Shot {
        val agents = listOf("claude", "codex", "gemini", null, "cursor", "opencode", "pi")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            allStatuses.forEachIndexed {
                i,
                s,
                ->
                AgentTile(agents[i], s, modifier = Modifier.padding(end = 6.dp, bottom = 6.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AgentTile("claude", reported(AgentState.Done), size = 20.dp, showBadge = false)
            AgentTile("codex", reported(AgentState.Done), size = 56.dp)
        }
    }

@PreviewTest
@PreviewComponentThemes
@Composable
private fun NeedYouAndBadgesScreenshot() =
    Shot {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NeedYouPill(2, onClick = {})
            NeedYouPill(1)
            NeedYouPill(3, label = "3", trailingArrow = true, onClick = {})
            CountBadge(2)
            CountBadge(12, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DotFilterChip("All", selected = true, onClick = {})
            DotFilterChip(
                "Needs you",
                selected = false,
                onClick = {},
                dotColor = MaterialTheme.flocketColors.attention,
                count = 2,
            )
            DotFilterChip(
                "flocket",
                selected = true,
                onClick = {},
                dotColor = MaterialTheme.flocketColors.done,
                count = 4,
            )
        }
    }

@PreviewTest
@PreviewComponentThemes
@Composable
private fun GroupHeadersScreenshot() =
    Shot {
        StatusGroup.entries.forEachIndexed { i, g ->
            GroupHeader(g.title(), count = i + 1, color = g.color(), expanded = i % 2 == 0, onToggle = {})
        }
        Text("studio.tail1234.ts.net:22", style = FlocketMono.small)
        Text("SHA256:9fT2Kx0aPq3LmZ7v", style = FlocketMono.label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            allStatuses.forEach { Text(agentStatusStyle(it).group.name, style = FlocketMono.body) }
        }
    }
