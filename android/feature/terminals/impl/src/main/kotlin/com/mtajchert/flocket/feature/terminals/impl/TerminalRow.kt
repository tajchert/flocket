@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.terminals.impl

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.ui.component.AgentStatusChip

/** One terminal. Tap opens it; long-press or the overflow button opens the action menu. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun TerminalRow(
    item: TerminalItem,
    selected: Boolean,
    nowMs: Long,
    actions: TerminalsActions,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val label = item.agentLabel.ifBlank { stringResource(R.string.row_shell) }
    Surface(
        modifier = modifier.padding(horizontal = 8.dp).semantics { this.selected = selected },
        shape = MaterialTheme.shapes.large,
        color = if (selected) scheme.secondaryContainer else scheme.surface,
    ) {
        Row(
            Modifier
                .clip(MaterialTheme.shapes.large)
                .combinedClickable(onClick = { actions.onOpen(item) }, onLongClick = { menuOpen = true })
                .heightIn(min = if (dense) 56.dp else 72.dp)
                .padding(
                    start = 16.dp,
                    top = if (dense) 4.dp else 10.dp,
                    bottom = if (dense) 4.dp else 10.dp,
                    end = 4.dp,
                ),
            verticalAlignment = if (dense) Alignment.CenterVertically else Alignment.Top,
        ) {
            if (dense) {
                DenseBody(item, label, nowMs, Modifier.weight(1f))
            } else {
                FullBody(item, label, nowMs, Modifier.weight(1f))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.row_actions, label))
                }
                RowMenu(menuOpen, { menuOpen = false }, item, actions)
            }
        }
    }
}

/** Two lines: agent and name; then status chip with the headline (or folder) and the age. */
@Composable
private fun DenseBody(
    item: TerminalItem,
    label: String,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!item.name.isNullOrBlank()) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentStatusChip(item.status)
            LivenessLabel(item)
            item.sinceMs?.let {
                Text(
                    DateUtils.getRelativeTimeSpanString(it, nowMs, DateUtils.MINUTE_IN_MILLIS).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FullBody(
    item: TerminalItem,
    label: String,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    run {
        run {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!item.name.isNullOrBlank()) {
                        Text(
                            item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
                if (item.headline.isNotBlank()) {
                    Text(
                        item.headline,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.cwd.isNotBlank()) {
                    Text(
                        item.cwd,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlowRow(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    AgentStatusChip(item.status)
                    LivenessLabel(item)
                    item.sinceMs?.let {
                        Text(
                            DateUtils.getRelativeTimeSpanString(it, nowMs, DateUtils.MINUTE_IN_MILLIS).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LivenessLabel(item: TerminalItem) {
    val scheme = MaterialTheme.colorScheme
    val text =
        when (item.liveness) {
            LivenessKind.Running -> {
                return
            }

            LivenessKind.Waiting -> {
                stringResource(R.string.row_waiting)
            }

            LivenessKind.Finished -> {
                when {
                    item.exitCode != null -> stringResource(R.string.row_finished_exit, item.exitCode)
                    item.signal != null -> stringResource(R.string.row_finished_signal, item.signal)
                    else -> stringResource(R.string.row_finished)
                }
            }
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (item.liveness == LivenessKind.Waiting) {
            Icon(Icons.Outlined.HourglassEmpty, null, Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun RowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    item: TerminalItem,
    actions: TerminalsActions,
) {
    fun run(action: (TerminalItem) -> Unit): () -> Unit =
        {
            onDismiss()
            action(item)
        }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!item.writable) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.row_read_only)) },
                onClick = {},
                enabled = false,
                leadingIcon = { Icon(Icons.Outlined.CloudOff, contentDescription = null) },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_open)) },
            onClick = run(actions.onOpen),
            leadingIcon = { Icon(Icons.Outlined.OpenInNew, contentDescription = null) },
        )
        if (item.isFinished) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_remove)) },
                onClick = run(actions.onRemove),
                enabled = item.writable,
                leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
            )
        } else {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_interrupt)) },
                onClick = run(actions.onInterrupt),
                enabled = item.writable,
                leadingIcon = { Icon(Icons.Outlined.StopCircle, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_send_text)) },
                onClick = run(actions.onSendText),
                enabled = item.writable,
                leadingIcon = { Icon(Icons.Outlined.Send, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_kill), color = MaterialTheme.colorScheme.error) },
                onClick = run(actions.onKill),
                enabled = item.writable,
                leadingIcon = {
                    Icon(
                        Icons.Outlined.PowerSettingsNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
    }
}
