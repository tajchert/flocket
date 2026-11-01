@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.terminals.impl

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FilterListOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.MachineState
import com.mtajchert.flocket.core.ui.component.CenteredContent
import com.mtajchert.flocket.core.ui.component.ConnectionStateBadge
import com.mtajchert.flocket.core.ui.component.EmptyState
import com.mtajchert.flocket.core.ui.component.ErrorBanner
import com.mtajchert.flocket.core.ui.component.StaleDataBanner
import com.mtajchert.flocket.core.ui.component.rememberNowMs
import com.mtajchert.flocket.core.ui.error.UiErrorAction
import com.mtajchert.flocket.feature.terminal.api.TerminalRoute

/** Everything the screen can ask for; the route wires it to the ViewModel. Defaults keep previews and tests terse. */
@Immutable
class TerminalsActions(
    val onFilter: (TerminalFilter) -> Unit = {},
    val onAttentionFirst: (Boolean) -> Unit = {},
    val onShowFinished: (Boolean) -> Unit = {},
    val onClearFilters: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOpen: (TerminalItem) -> Unit = {},
    val onKill: (TerminalItem) -> Unit = {},
    val onRemove: (TerminalItem) -> Unit = {},
    val onInterrupt: (TerminalItem) -> Unit = {},
    val onSendText: (TerminalItem) -> Unit = {},
    val onNewTerminal: () -> Unit = {},
    val onAddHost: () -> Unit = {},
    val onHostAction: (HostId, UiErrorAction) -> Unit = { _, _ -> },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalsScreen(
    state: TerminalsUiState,
    actions: TerminalsActions,
    modifier: Modifier = Modifier,
    selected: TerminalRoute? = null,
    nowMs: Long = rememberNowMs(intervalMs = 30_000L),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.terminals_title)) },
                actions = { if (state.emptyKind != EmptyKind.NoHosts) OptionsMenu(state, actions) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state.emptyKind != EmptyKind.NoHosts && state.loaded) {
                val label = stringResource(R.string.new_terminal)
                ExtendedFloatingActionButton(
                    onClick = actions.onNewTerminal,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(label) },
                    // The label lives on the button node itself, so TalkBack and UI dumps find it.
                    modifier = Modifier.semantics { contentDescription = label },
                )
            }
        },
    ) { padding ->
        CenteredContent(Modifier.padding(padding), maxWidth = 720.dp) {
            Column {
                if (state.loaded && state.emptyKind != EmptyKind.NoHosts) FilterRow(state.filter, actions.onFilter)
                when (state.emptyKind) {
                    EmptyKind.NoHosts -> {
                        EmptyState(
                            Icons.Outlined.Dns,
                            stringResource(R.string.empty_no_hosts_title),
                            stringResource(R.string.empty_no_hosts_message),
                            actionLabel = stringResource(R.string.empty_no_hosts_action),
                            onAction = actions.onAddHost,
                        )
                    }

                    EmptyKind.NoTerminals -> {
                        EmptyState(
                            Icons.Outlined.Terminal,
                            stringResource(R.string.empty_no_terminals_title),
                            stringResource(R.string.empty_no_terminals_message),
                            actionLabel = stringResource(R.string.empty_no_terminals_action),
                            onAction = actions.onNewTerminal,
                        )
                    }

                    EmptyKind.NoMatches -> {
                        EmptyState(
                            Icons.Outlined.FilterListOff,
                            stringResource(R.string.empty_no_matches_title),
                            stringResource(R.string.empty_no_matches_message),
                            actionLabel = stringResource(R.string.empty_no_matches_action),
                            onAction = actions.onClearFilters,
                        )
                    }

                    else -> {
                        TerminalList(state, actions, selected, nowMs)
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionsMenu(
    state: TerminalsUiState,
    actions: TerminalsActions,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.menu_options))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_sort_attention)) },
            onClick = { actions.onAttentionFirst(!state.attentionFirst) },
            trailingIcon = { Checkbox(checked = state.attentionFirst, onCheckedChange = null) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_show_finished)) },
            onClick = { actions.onShowFinished(!state.showFinished) },
            trailingIcon = { Checkbox(checked = state.showFinished, onCheckedChange = null) },
        )
    }
}

@Composable
private fun FilterRow(
    filter: TerminalFilter,
    onFilter: (TerminalFilter) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TerminalFilter.entries.forEach { entry ->
            FilterChip(
                selected = filter == entry,
                onClick = { onFilter(entry) },
                label = {
                    Text(
                        stringResource(
                            when (entry) {
                                TerminalFilter.All -> R.string.filter_all
                                TerminalFilter.NeedsAttention -> R.string.filter_attention
                                TerminalFilter.Working -> R.string.filter_working
                                TerminalFilter.Finished -> R.string.filter_finished
                            },
                        ),
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun TerminalList(
    state: TerminalsUiState,
    actions: TerminalsActions,
    selected: TerminalRoute?,
    nowMs: Long,
) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh) {
        LazyColumn(
            contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (state.emptyKind == EmptyKind.AllOffline) {
                item(key = "offline", contentType = "notice") { OfflineNotice(actions.onRefresh) }
            }
            state.groups.forEach { group ->
                stickyHeader(key = "host:${group.hostId.value}", contentType = "host") { HostHeader(group, nowMs) }
                group.error?.let { error ->
                    item(key = "error:${group.hostId.value}", contentType = "error") {
                        ErrorBanner(
                            error,
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            onAction = { actions.onHostAction(group.hostId, it) },
                        )
                    }
                }
                if (!group.online && (group.error == null || group.total > 0)) {
                    item(key = "stale:${group.hostId.value}", contentType = "stale") {
                        StaleDataBanner(
                            group.confirmedAtMs,
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            nowMs,
                        )
                    }
                }
                if (group.online && group.visibleCount == 0) {
                    item(key = "none:${group.hostId.value}", contentType = "none") {
                        Text(
                            stringResource(
                                if (group.total ==
                                    0
                                ) {
                                    R.string.host_no_terminals
                                } else {
                                    R.string.host_no_matches
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                group.sections.forEach { section ->
                    if (section.machine != null) {
                        item(key = "machine:${group.hostId.value}:${section.machine}", contentType = "machine") {
                            MachineHeader(section)
                        }
                    }
                    items(section.items.size, key = { section.items[it].key }, contentType = { "row" }) { index ->
                        val item = section.items[index]
                        TerminalRow(
                            item = item,
                            selected =
                                selected != null && selected.hostId == item.hostId.value &&
                                    selected.address == item.address,
                            nowMs = nowMs,
                            actions = actions,
                        )
                    }
                }
            }
            if (state.hiddenFinished > 0) {
                item(key = "hidden-finished", contentType = "footer") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            pluralStringResource(R.plurals.finished_hidden, state.hiddenFinished, state.hiddenFinished),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { actions.onShowFinished(true) },
                        ) { Text(stringResource(R.string.finished_hidden_show)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostHeader(
    group: HostGroup,
    nowMs: Long,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    group.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (group.total > 0) {
                    Text(
                        if (group.visibleCount == group.total) {
                            pluralStringResource(R.plurals.host_terminal_count, group.total, group.total)
                        } else {
                            stringResource(R.string.host_terminal_count_filtered, group.visibleCount, group.total)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ConnectionStateBadge(group.connection, nowMs = nowMs)
        }
    }
}

@Composable
private fun MachineHeader(section: MachineSection) {
    val scheme = MaterialTheme.colorScheme
    val problem =
        section.machineState != null && section.machineState != MachineState.Connected &&
            section.machineState != MachineState.Connecting
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Devices, null, Modifier.padding(end = 0.dp), tint = scheme.onSurfaceVariant)
        Text(
            stringResource(R.string.machine_header, section.machine.orEmpty()),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        val stateText =
            when (section.machineState) {
                MachineState.Connected -> R.string.machine_state_connected
                MachineState.Connecting -> R.string.machine_state_connecting
                MachineState.NeedsLogin -> R.string.machine_state_needs_login
                MachineState.NeedsUpdate -> R.string.machine_state_needs_update
                MachineState.Unreachable -> R.string.machine_state_unreachable
                else -> null
            }
        if (stateText != null) {
            Text(
                stringResource(stateText),
                style = MaterialTheme.typography.labelMedium,
                color = if (problem) scheme.error else scheme.onSurfaceVariant,
            )
        } else if (section.machineStateRaw != null) {
            Text(section.machineStateRaw, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OfflineNotice(onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.empty_offline_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.empty_offline_message), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.empty_offline_action)) }
        }
    }
}
