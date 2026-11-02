@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.hosts.impl.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtajchert.flocket.core.designsystem.theme.FlocketCorners
import com.mtajchert.flocket.core.designsystem.theme.FlocketMono
import com.mtajchert.flocket.core.designsystem.theme.flocketColors
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.MachineInfo
import com.mtajchert.flocket.core.model.MachineState
import com.mtajchert.flocket.core.ui.component.CenteredContent
import com.mtajchert.flocket.core.ui.component.ConnectionStateBadge
import com.mtajchert.flocket.core.ui.component.EmptyState
import com.mtajchert.flocket.core.ui.component.NeedYouPill
import com.mtajchert.flocket.core.ui.component.rememberNowMs
import com.mtajchert.flocket.core.ui.error.UiError
import com.mtajchert.flocket.core.ui.error.asString
import com.mtajchert.flocket.feature.hosts.impl.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    state: HostsUiState,
    onHostClick: (HostId) -> Unit,
    onAddHost: () -> Unit,
    onTour: () -> Unit,
    modifier: Modifier = Modifier,
    selectedHostId: HostId? = null,
    nowMs: Long = rememberNowMs(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hosts_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (state.hosts.isNotEmpty()) {
                val label = stringResource(R.string.hosts_add)
                ExtendedFloatingActionButton(
                    onClick = onAddHost,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(label) },
                    modifier = Modifier.semantics { contentDescription = label },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = FlocketCorners.Fab,
                )
            }
        },
    ) { padding ->
        if (state.hosts.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Dns,
                title = stringResource(R.string.hosts_empty_title),
                message = stringResource(R.string.hosts_empty_message),
                modifier = Modifier.padding(padding),
                actionLabel = stringResource(R.string.hosts_empty_action),
                onAction = onAddHost,
                secondaryLabel = stringResource(R.string.hosts_empty_tour),
                onSecondary = onTour,
            )
        } else {
            CenteredContent(Modifier.padding(padding), maxWidth = 720.dp) {
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.hosts, key = { it.id.value }) { host ->
                        HostCard(
                            host,
                            selected = host.id == selectedHostId,
                            nowMs = nowMs,
                            onClick = { onHostClick(host.id) },
                        )
                    }
                }
            }
        }
    }
}

/** Small "Custom" pill: this host overrides some app-level terminal defaults. */
@Composable
private fun CustomBadge() {
    Text(
        stringResource(R.string.host_custom_badge),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape)
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun CountChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, FlocketCorners.Chip)
                .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun AttentionNote(error: UiError) {
    val colors = MaterialTheme.flocketColors
    val action = error.primaryAction
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.attentionContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = colors.onAttentionContainer,
            modifier = Modifier.size(22.dp),
        )
        Text(
            error.message.asString(),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onAttentionContainer,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Text(
                stringResource(action.label),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onAttentionContainer,
            )
        }
    }
}

@Composable
private fun MachinesLine(machines: List<MachineInfo>) {
    val flocket = MaterialTheme.flocketColors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            Icons.Outlined.Hub,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            machines.forEach { machine ->
                val dot =
                    when (machine.state) {
                        MachineState.Connected -> flocket.done
                        MachineState.NeedsLogin, MachineState.NeedsUpdate -> flocket.attention
                        MachineState.Unreachable, MachineState.Other -> MaterialTheme.colorScheme.error
                        MachineState.Connecting -> MaterialTheme.colorScheme.outline
                    }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.size(6.dp).background(dot, CircleShape))
                    Text(
                        machine.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HostCard(
    host: HostItem,
    selected: Boolean,
    nowMs: Long,
    onClick: () -> Unit,
) {
    val colors =
        CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        )
    val modifier = Modifier.fillMaxWidth().semantics { this.selected = selected }
    Card(
        onClick = onClick,
        colors = colors,
        shape = FlocketCorners.CardLarge,
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .size(
                            40.dp,
                        ).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            host.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (host.customized) CustomBadge()
                    }
                    Text(
                        stringResource(R.string.host_address, host.address.user, host.address.host, host.address.port),
                        style = FlocketMono.small.copy(fontSize = 11.5.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ConnectionStateBadge(host.connection, nowMs = nowMs)
            }
            FlowRow(
                Modifier.padding(start = 52.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (host.counts.blocked > 0) NeedYouPill(host.counts.blocked)
                if (host.counts.working > 0) {
                    CountChip(
                        pluralStringResource(R.plurals.host_count_working, host.counts.working, host.counts.working),
                    )
                }
                CountChip(pluralStringResource(R.plurals.host_count_total, host.counts.total, host.counts.total))
            }
            host.error?.let { AttentionNote(it) }
            if (host.machines.isNotEmpty()) MachinesLine(host.machines)
        }
    }
}
