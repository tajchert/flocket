@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.hosts.impl.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Dns
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.ui.component.CenteredContent
import com.mtajchert.flocket.core.ui.component.ConnectionStateBadge
import com.mtajchert.flocket.core.ui.component.EmptyState
import com.mtajchert.flocket.core.ui.component.rememberNowMs
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
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
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
    Card(onClick = onClick, colors = colors, modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            host.label,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (host.customized) CustomBadge()
                    }
                    Text(
                        stringResource(R.string.host_address, host.address.user, host.address.host, host.address.port),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionStateBadge(host.connection, nowMs = nowMs)
                Text(
                    text =
                        if (host.coreName != null && host.coreVersion != null) {
                            stringResource(R.string.host_core_info, host.coreName, host.coreVersion)
                        } else {
                            stringResource(R.string.host_core_unknown)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (host.counts.blocked > 0) {
                    Text(
                        pluralStringResource(R.plurals.host_count_blocked, host.counts.blocked, host.counts.blocked),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (host.counts.working > 0) {
                    Text(
                        pluralStringResource(R.plurals.host_count_working, host.counts.working, host.counts.working),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text(
                    pluralStringResource(R.plurals.host_count_total, host.counts.total, host.counts.total),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
