@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.terminals.impl

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material.icons.filled.FrontHand
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterListOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.designsystem.theme.FlocketCorners
import com.mtajchert.flocket.core.designsystem.theme.flocketColors
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.LayoutRef
import com.mtajchert.flocket.core.model.MachineState
import com.mtajchert.flocket.core.model.TerminalSort
import com.mtajchert.flocket.core.ui.component.CenteredContent
import com.mtajchert.flocket.core.ui.component.ConnectionStateBadge
import com.mtajchert.flocket.core.ui.component.DotFilterChip
import com.mtajchert.flocket.core.ui.component.EmptyState
import com.mtajchert.flocket.core.ui.component.ErrorBanner
import com.mtajchert.flocket.core.ui.component.GroupHeader
import com.mtajchert.flocket.core.ui.component.StaleDataBanner
import com.mtajchert.flocket.core.ui.component.StatusGroup
import com.mtajchert.flocket.core.ui.component.color
import com.mtajchert.flocket.core.ui.component.rememberNowMs
import com.mtajchert.flocket.core.ui.component.title
import com.mtajchert.flocket.core.ui.error.UiErrorAction
import com.mtajchert.flocket.core.ui.nav.LocalPaneLayout
import com.mtajchert.flocket.core.ui.nav.LocalSidebarToggle
import com.mtajchert.flocket.feature.terminal.api.TerminalRoute
import com.mtajchert.flocket.core.ui.R as CoreUiR

/** Everything the screen can ask for; the route wires it to the ViewModel. Defaults keep previews and tests terse. */
@Immutable
data class TerminalsActions(
    val onFilter: (TerminalFilter) -> Unit = {},
    val onSort: (TerminalSort) -> Unit = {},
    val onClearFilters: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOpen: (TerminalItem) -> Unit = {},
    val onKill: (TerminalItem) -> Unit = {},
    /** Pin (true) or unpin (false) a terminal. */
    val onPin: (TerminalItem, Boolean) -> Unit = { _, _ -> },
    val onMarkUnread: (TerminalItem) -> Unit = {},
    val onOpenChild: (ChildItem) -> Unit = {},
    val onInterrupt: (TerminalItem) -> Unit = {},
    val onSendText: (TerminalItem) -> Unit = {},
    /** Rename a desktop space or tab: host, target, current given name, automatic name. */
    val onRename: (HostId, LayoutRef, String, String) -> Unit = { _, _, _, _ -> },
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
    val paneLayout = LocalPaneLayout.current
    val sidebar = LocalSidebarToggle.current
    val dense = paneLayout.multiPane
    val canAdd = state.emptyKind != EmptyKind.NoHosts && state.loaded
    // The navigation rail carries the New terminal button; the list shows its own only without the rail.
    val showAdd = canAdd && !paneLayout.navigationRail
    val priority = state.sort == TerminalSort.Priority
    // The host chip selection; a host that has gone away counts as "All".
    var hostFilterValue by rememberSaveable { mutableStateOf<String?>(null) }
    val hostFilter = state.groups.firstOrNull { it.hostId.value == hostFilterValue }?.hostId
    // A row pinned from this list joins the Pinned group at the top, out of view; once it shows as pinned the list
    // scrolls up to it. Pins that arrive any other way (another device, a restore) leave the scroll alone.
    var justPinned by remember { mutableStateOf<String?>(null) }
    val listActions =
        remember(actions) {
            actions.copy(onPin = { item, pin ->
                justPinned = item.key.takeIf { pin }
                actions.onPin(item, pin)
            })
        }
    val revealPinned = remember(state.groups, justPinned) { justPinned?.let { state.groups.showsPinned(it) } == true }
    val onPinnedReveal = { justPinned = null }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.terminals_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                // Beside a terminal both top bars share one container so the status bar strip is uniform.
                // As a pane card (the status bar sits above the cards) the bar is the card itself. Compact height
                // is the only visible list without cards; checking it, not paneCards, keeps the bar's colour while
                // the list slides away into focus mode.
                colors =
                    if (dense && paneLayout.compactHeight) {
                        TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    } else {
                        TopAppBarDefaults.topAppBarColors()
                    },
                actions = {
                    if (dense && showAdd) {
                        IconButton(onClick = actions.onNewTerminal) {
                            Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.new_terminal))
                        }
                    }
                    // Beside an open terminal the list can step aside (focus mode), from just before the menu.
                    if (dense && selected != null) {
                        IconButton(
                            onClick = { sidebar.setSidebarVisible(false) },
                            modifier = Modifier.testTag("terminals_collapse"),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.MenuOpen,
                                contentDescription = stringResource(R.string.terminals_collapse),
                            )
                        }
                    }
                    if (state.emptyKind !=
                        EmptyKind.NoHosts
                    ) {
                        OptionsMenu(state, actions, showFilters = priority || dense)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (showAdd && !dense) {
                val label = stringResource(R.string.new_terminal)
                ExtendedFloatingActionButton(
                    onClick = actions.onNewTerminal,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(label) },
                    shape = FlocketCorners.Fab,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    // The label lives on the button node itself, so TalkBack and UI dumps find it.
                    modifier = Modifier.semantics { contentDescription = label },
                )
            }
        },
    ) { padding ->
        CenteredContent(Modifier.padding(padding), maxWidth = 720.dp) {
            Column {
                if (priority && state.loaded && state.emptyKind != EmptyKind.NoHosts) {
                    PriorityHeader(state, hostFilter, onHost = { hostFilterValue = it?.value })
                } else if (!dense && state.loaded && state.emptyKind != EmptyKind.NoHosts) {
                    FilterRow(state.filter, actions.onFilter)
                }
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
                        if (priority) {
                            PriorityList(
                                state,
                                listActions,
                                selected,
                                nowMs,
                                dense,
                                hostFilter,
                                revealPinned,
                                onPinnedReveal,
                            )
                        } else {
                            TerminalList(state, listActions, selected, nowMs, dense, revealPinned, onPinnedReveal)
                        }
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
    showFilters: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    // With the status filter inside the menu, a dot on the button says a filter is narrowing the list.
    val filtered = showFilters && state.filter != TerminalFilter.All
    IconButton(onClick = { open = true }) {
        BadgedBox(badge = { if (filtered) Badge() }) {
            val description = if (filtered) R.string.menu_options_filtered else R.string.menu_options
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(description))
        }
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (showFilters) {
            // The status filter is a chip row only on a phone outside the priority list; otherwise it lives here.
            Text(
                stringResource(R.string.menu_filter_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            TerminalFilter.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(stringResource(entry.label())) },
                    onClick = {
                        actions.onFilter(entry)
                        open = false
                    },
                    leadingIcon = { RadioButton(selected = state.filter == entry, onClick = null) },
                )
            }
            HorizontalDivider()
        }
        Text(
            stringResource(R.string.menu_sort_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        listOf(
            TerminalSort.Priority to R.string.menu_sort_priority,
            TerminalSort.Grouped to R.string.menu_sort_grouped,
        ).forEach { (sort, label) ->
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                onClick = {
                    actions.onSort(sort)
                    open = false
                },
                leadingIcon = { RadioButton(selected = state.sort == sort, onClick = null) },
            )
        }
    }
}

private fun TerminalFilter.label() =
    when (this) {
        TerminalFilter.All -> R.string.filter_all
        TerminalFilter.NeedsAttention -> R.string.filter_attention
        TerminalFilter.Working -> R.string.filter_working
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
                        style = MaterialTheme.typography.labelLarge,
                        text = stringResource(entry.label()),
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
    dense: Boolean,
    revealPinned: Boolean = false,
    onPinnedReveal: () -> Unit = {},
) {
    // Keys of collapsed spaces ("host/space"); saved across rotation and process death.
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val listState = rememberLazyListState()
    // The Pinned group is first: open it and scroll up so a row just pinned is in view.
    val revealed by rememberUpdatedState(onPinnedReveal)
    LaunchedEffect(revealPinned) {
        if (revealPinned) {
            collapsed = collapsed - PINNED_KEY
            listState.animateScrollToItem(0)
            revealed()
        }
    }
    // Keys of parents whose sub-agents are folded away.
    var folded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val pinned = remember(state.groups) { pinnedGroupedRows(state.groups) }
    val unpinnedSpaces =
        remember(state.groups) {
            val map = java.util.IdentityHashMap<MachineSection, List<SpaceSection>>()
            state.groups.forEach { g ->
                g.sections.forEach { map[it] = it.spaces.mapNotNull { sp -> sp.withoutPinned() } }
            }
            map
        }
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (state.emptyKind == EmptyKind.AllOffline) {
                item(key = "offline", contentType = "notice") { OfflineNotice(actions.onRefresh) }
            }
            if (pinned.isNotEmpty()) {
                val open = PINNED_KEY !in collapsed
                item(key = "group:$PINNED_KEY", contentType = "group") {
                    GroupHeader(
                        title = stringResource(R.string.priority_pinned),
                        count = pinned.size,
                        color = StatusGroup.NoStatus.color(),
                        expanded = open,
                        onToggle = { collapsed = if (open) collapsed + PINNED_KEY else collapsed - PINNED_KEY },
                    )
                }
                if (open) {
                    items(pinned.size, key = { "pinned:" + pinned[it].item.key }, contentType = { "row" }) { index ->
                        val row = pinned[index]
                        TerminalRowTree(
                            item = row.item,
                            selected = selected,
                            nowMs = nowMs,
                            actions = actions,
                            dense = dense,
                            names = pinnedNames(row.item, row.space),
                            hostLabel =
                                listOfNotNull(
                                    row.hostLabel.takeIf { state.groups.size > 1 },
                                    row.item.ref.address.machine,
                                ).joinToString("/").ifEmpty { null },
                            folded = row.item.key in folded,
                            onToggleFold = {
                                folded = if (row.item.key in folded) folded - row.item.key else folded + row.item.key
                            },
                        )
                    }
                }
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
                    unpinnedSpaces[section].orEmpty().forEach { space ->
                        val spaceKey = "${group.hostId.value}/${section.machine.orEmpty()}/${space.key}"
                        val isCollapsed = spaceKey in collapsed
                        item(key = "space:$spaceKey", contentType = "space") {
                            val placeholder =
                                space.autoName.ifBlank {
                                    stringResource(
                                        R.string.workspace_space_fallback,
                                        space.number,
                                    )
                                }
                            SpaceHeader(
                                space,
                                isCollapsed,
                                dense,
                                onToggle = {
                                    collapsed =
                                        if (spaceKey in collapsed) collapsed - spaceKey else collapsed + spaceKey
                                },
                                onRename =
                                    space.layoutRef?.takeIf { space.canRename }?.let { ref ->
                                        { actions.onRename(group.hostId, ref, space.givenName, placeholder) }
                                    },
                            )
                        }
                        if (!isCollapsed) {
                            space.tabs.forEach { tab ->
                                if (space.showTabLabels) {
                                    item(key = "tab:$spaceKey/${tab.key}", contentType = "tab") {
                                        val fallback = stringResource(R.string.workspace_tab_fallback, tab.number)
                                        TabLabel(
                                            tab.name.ifBlank { fallback },
                                            onRename =
                                                tab.layoutRef?.takeIf { tab.canRename }?.let { ref ->
                                                    { actions.onRename(group.hostId, ref, tab.name, fallback) }
                                                },
                                        )
                                    }
                                }
                                items(tab.items.size, key = { tab.items[it].key }, contentType = { "row" }) { index ->
                                    val item = tab.items[index]
                                    TerminalRowTree(
                                        item = item,
                                        selected = selected,
                                        nowMs = nowMs,
                                        actions = actions,
                                        indent = 12.dp,
                                        dense = dense,
                                        names = groupedNames(item, space),
                                        folded = item.key in folded,
                                        onToggleFold = {
                                            folded =
                                                if (item.key in folded) folded - item.key else folded + item.key
                                        },
                                    )
                                }
                            }
                        }
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

internal fun statusColor(
    state: AgentState?,
    scheme: ColorScheme,
): Color =
    when (state) {
        null -> scheme.outline
        AgentState.Idle -> scheme.onSurfaceVariant
        AgentState.Working -> scheme.secondary
        AgentState.Done -> scheme.primary
        AgentState.Blocked -> scheme.tertiary
        AgentState.Error -> scheme.error
        AgentState.Other -> scheme.onSurfaceVariant
    }

@Composable
private fun SpaceHeader(
    space: SpaceSection,
    collapsed: Boolean,
    dense: Boolean,
    onToggle: () -> Unit,
    /** Long-press action; null when the space can't be renamed (folder space, stale host, unreachable machine). */
    onRename: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val statusText =
        stringResource(
            when (space.topState) {
                null -> R.string.space_status_none
                AgentState.Idle -> R.string.space_status_idle
                AgentState.Working -> R.string.space_status_working
                AgentState.Done -> R.string.space_status_done
                AgentState.Blocked -> R.string.space_status_blocked
                AgentState.Error -> R.string.space_status_error
                AgentState.Other -> R.string.space_status_other
            },
        )
    val spaceName = space.name.ifBlank { stringResource(R.string.workspace_space_fallback, space.number) }
    val toggleLabel = stringResource(if (collapsed) R.string.space_expand else R.string.space_collapse, spaceName)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClickLabel = toggleLabel,
                onClick = onToggle,
                onLongClickLabel = onRename?.let { stringResource(CoreUiR.string.menu_rename_space) },
                onLongClick = onRename,
            ).semantics(mergeDescendants = true) { stateDescription = statusText }
            .padding(start = 16.dp, end = 8.dp, top = if (dense) 6.dp else 10.dp, bottom = if (dense) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).background(statusColor(space.topState, scheme), CircleShape))
        Column(Modifier.weight(1f)) {
            Text(
                spaceName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (space.folderSubtitle != null) {
                Text(
                    space.folderSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(space.count.toString(), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Icon(
            if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TabLabel(
    name: String,
    onRename: (() -> Unit)? = null,
) {
    val renameLabel = stringResource(CoreUiR.string.menu_rename_tab)
    Text(
        name,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (onRename != null) {
                        // Long press only: a no-op click would show TalkBack a "double tap to activate".
                        Modifier
                            .pointerInput(onRename) { detectTapGestures(onLongPress = { onRename() }) }
                            .semantics {
                                onLongClick(label = renameLabel) {
                                    onRename()
                                    true
                                }
                            }
                    } else {
                        Modifier
                    },
                ).padding(start = 36.dp, end = 16.dp, top = 4.dp),
    )
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

/** Summary line and host chips above the priority list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PriorityHeader(
    state: TerminalsUiState,
    hostFilter: HostId?,
    onHost: (HostId?) -> Unit,
) {
    val fixed = MaterialTheme.flocketColors
    val scheme = MaterialTheme.colorScheme
    val needsYou = state.groups.sumOf { it.needsYouCount() }
    val working = state.groups.sumOf { g -> g.sections.sumOf { s -> s.items.count { it.isWorking() } } }
    val online = state.groups.count { it.online }
    val total = state.groups.size
    Column {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (needsYou > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Filled.FrontHand,
                        contentDescription = null,
                        Modifier.size(16.dp),
                        tint = fixed.attention,
                    )
                    Text(
                        pluralStringResource(CoreUiR.plurals.need_you_count, needsYou, needsYou),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = fixed.attention,
                    )
                }
            }
            if (working > 0) {
                Text(
                    pluralStringResource(R.plurals.summary_working, working, working),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (total > 0) {
                Text(
                    pluralStringResource(R.plurals.summary_hosts_online, total, online, total),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        if (state.groups.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DotFilterChip(
                    label = stringResource(R.string.filter_all),
                    selected = hostFilter == null,
                    onClick = { onHost(null) },
                    count = needsYou.takeIf { it > 0 },
                )
                state.groups.forEach { group ->
                    DotFilterChip(
                        label = group.label,
                        selected = hostFilter == group.hostId,
                        onClick = { onHost(group.hostId) },
                        dotColor = if (group.online) scheme.primary else scheme.outline,
                        count = group.needsYouCount().takeIf { it > 0 },
                    )
                }
            }
        }
    }
}

private fun PriorityBucket.group(): StatusGroup =
    when (this) {
        PriorityBucket.NeedsYou -> StatusGroup.NeedsYou
        PriorityBucket.Pinned -> StatusGroup.NoStatus
        PriorityBucket.Working -> StatusGroup.Working
        PriorityBucket.Done -> StatusGroup.Done
        PriorityBucket.Idle -> StatusGroup.Idle
        PriorityBucket.NoStatus -> StatusGroup.NoStatus
    }

/** The cross-host priority list: Needs you, Done, Working, Idle, then Other terminals (collapsed unless no agents). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PriorityList(
    state: TerminalsUiState,
    actions: TerminalsActions,
    selected: TerminalRoute?,
    nowMs: Long,
    dense: Boolean,
    hostFilter: HostId?,
    revealPinned: Boolean = false,
    onPinnedReveal: () -> Unit = {},
) {
    // Group keys the user flipped away from their default (Other terminals start collapsed when agents exist, the rest expanded).
    var flipped by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val listState = rememberLazyListState()
    // The Pinned group is first: open it and scroll up so a row just pinned is in view.
    val revealed by rememberUpdatedState(onPinnedReveal)
    LaunchedEffect(revealPinned) {
        if (revealPinned) {
            flipped = flipped - PriorityBucket.Pinned.name
            listState.animateScrollToItem(0)
            revealed()
        }
    }
    var folded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val sections = remember(state.groups, hostFilter) { prioritySections(state.groups, hostFilter) }
    val shownGroups = state.groups.filter { hostFilter == null || it.hostId == hostFilter }
    val showHost = state.groups.size > 1 && hostFilter == null
    // Other terminals start expanded when no agent row is listed, so a lone shell is not hidden behind a header.
    val hasAgentRows =
        sections.any {
            it.bucket != PriorityBucket.NoStatus && it.bucket != PriorityBucket.Pinned &&
                it.rows.isNotEmpty()
        }
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (state.emptyKind == EmptyKind.AllOffline) {
                item(key = "offline", contentType = "notice") { OfflineNotice(actions.onRefresh) }
            }
            shownGroups.filter { !it.online || it.error != null }.forEach { group ->
                item(key = "host-note:${group.hostId.value}", contentType = "host-note") {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            group.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        group.error?.let { error ->
                            ErrorBanner(error, onAction = { actions.onHostAction(group.hostId, it) })
                        }
                        if (!group.online && (group.error == null || group.total > 0)) {
                            StaleDataBanner(group.confirmedAtMs, Modifier, nowMs)
                        }
                    }
                }
            }
            sections.forEach { section ->
                val key = section.bucket.name
                val expanded = (section.bucket != PriorityBucket.NoStatus || !hasAgentRows) != (key in flipped)
                item(key = "group:$key", contentType = "group") {
                    val group = section.bucket.group()
                    GroupHeader(
                        title =
                            if (section.bucket == PriorityBucket.NoStatus) {
                                stringResource(R.string.priority_other_terminals)
                            } else if (section.bucket == PriorityBucket.Pinned) {
                                stringResource(R.string.priority_pinned)
                            } else {
                                group.title()
                            },
                        count = section.rows.size,
                        color = group.color(),
                        expanded = expanded,
                        onToggle = { flipped = if (key in flipped) flipped - key else flipped + key },
                    )
                }
                if (expanded) {
                    items(
                        section.rows.size,
                        key = {
                            section.rows[it]
                                .row.item.key
                        },
                        contentType = { "row" },
                    ) { index ->
                        val listRow = section.rows[index]
                        val item = listRow.row.item
                        TerminalRowTree(
                            item = item,
                            selected = selected,
                            folded = item.key in folded,
                            onToggleFold = {
                                folded =
                                    if (item.key in
                                        folded
                                    ) {
                                        folded - item.key
                                    } else {
                                        folded + item.key
                                    }
                            },
                            nowMs = nowMs,
                            actions = actions,
                            dense = dense,
                            names = listRow.row.place.names(item),
                            placeInfo = listRow.row.place,
                            hostLabel =
                                listOfNotNull(listRow.hostLabel.takeIf { showHost }, item.ref.address.machine)
                                    .joinToString("/")
                                    .ifEmpty { null },
                        )
                    }
                }
            }
        }
    }
}

/** Whether the row with [key] is listed as pinned (Grouped and Priority sections both carry their rows in `items`). */
private fun List<HostGroup>.showsPinned(key: String) =
    any { g -> g.sections.any { section -> section.items.any { it.key == key && it.pinned } } }

private const val PINNED_KEY = "pinned"
