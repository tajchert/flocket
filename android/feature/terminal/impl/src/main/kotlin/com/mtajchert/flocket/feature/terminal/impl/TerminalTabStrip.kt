package com.mtajchert.flocket.feature.terminal.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.ui.component.AgentIcon

/** The whole strip is this tall, so it costs one text row on a landscape phone. */
private val StripHeight = 40.dp
private val ChipHeight = 32.dp

/**
 * The tabs of the open terminal's space (scrolling), then, when its tab has several panes, a pane
 * switcher. Tapping opens the terminal through [onOpen]; the open one is [StripTab.selected].
 */
@Composable
fun TerminalTabStrip(
    strip: TabStripUi,
    onOpen: (address: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(StripHeight)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 8.dp)
                .testTag("terminal_tab_strip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (strip.tabs.size >= 2) {
            // Starts one tab before the open one, so the open tab is on screen from the first frame.
            val listState =
                rememberLazyListState(
                    initialFirstVisibleItemIndex = (strip.tabs.indexOfFirst { it.selected } - 1).coerceAtLeast(0),
                )
            LazyRow(
                modifier = Modifier.weight(1f),
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(strip.tabs, key = { it.key }) { tab ->
                    StripChip(
                        tab.name.ifBlank {
                            stringResource(R.string.workspace_tab_fallback, tab.number)
                        },
                        tab.state,
                        tab.selected,
                        "terminal_tab",
                        showIcon = true,
                        agentId = tab.agentId,
                    ) { onOpen(tab.target) }
                }
            }
        } else {
            Box(Modifier.weight(1f))
        }
        if (strip.panes.size >= 2) {
            if (strip.tabs.size >= 2) VerticalDivider(Modifier.padding(horizontal = 8.dp).height(20.dp))
            Row(
                modifier = Modifier.widthIn(max = 300.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                strip.panes.forEachIndexed { index, pane ->
                    StripChip("${index + 1} ${pane.label}", pane.state, pane.selected, "terminal_pane") {
                        onOpen(pane.address)
                    }
                }
            }
        }
    }
}

/** The tap target is the full strip height; the chip drawn inside is the usual 32dp. */
@Composable
private fun StripChip(
    text: String,
    state: AgentState?,
    selected: Boolean,
    tag: String,
    showIcon: Boolean = false,
    agentId: String? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // The open tab/pane scrolls into view when the strip first shows it.
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) requester.bringIntoView() }
    val described = state?.let { stringResource(R.string.terminal_tab_format, text, stateWord(it)) } ?: text
    Box(
        modifier =
            Modifier
                .bringIntoViewRequester(requester)
                .height(StripHeight)
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) {
                    role = Role.Tab
                    this.selected = selected
                    contentDescription = described
                }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (selected) scheme.secondaryContainer else Color.Transparent,
            contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            modifier = Modifier.height(ChipHeight),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showIcon) AgentIcon(agentId, size = 16.dp)
                if (state != null) StatusDot(state)
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusDot(state: AgentState) {
    val scheme = MaterialTheme.colorScheme
    val color =
        when (state) {
            AgentState.Working -> scheme.secondary
            AgentState.Done -> scheme.primary
            AgentState.Blocked -> scheme.tertiary
            AgentState.Error -> scheme.error
            AgentState.Idle, AgentState.Other -> scheme.outline
        }
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

@Composable
private fun stateWord(state: AgentState): String =
    stringResource(
        when (state) {
            AgentState.Idle -> R.string.terminal_tab_state_idle
            AgentState.Working -> R.string.terminal_tab_state_working
            AgentState.Done -> R.string.terminal_tab_state_done
            AgentState.Blocked -> R.string.terminal_tab_state_blocked
            AgentState.Error -> R.string.terminal_tab_state_error
            AgentState.Other -> R.string.terminal_tab_state_other
        },
    )
