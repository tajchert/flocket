@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.terminals.impl

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.BlockKind
import com.mtajchert.flocket.core.ui.component.AgentIcon
import com.mtajchert.flocket.core.ui.component.AgentStatusChip
import com.mtajchert.flocket.core.ui.R as CoreUiR

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
    /** Priority mode: where the terminal lives ("Space · Tab"), shown under it. */
    place: String? = null,
    /** Priority mode: the row's space and tab, for the rename entries. */
    placeInfo: RowPlace? = null,
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
                .heightIn(min = if (dense) 48.dp else 72.dp)
                .padding(
                    start = 16.dp,
                    top = if (dense) 4.dp else 10.dp,
                    bottom = if (dense) 4.dp else 10.dp,
                    end = 4.dp,
                ),
            verticalAlignment = if (dense) Alignment.CenterVertically else Alignment.Top,
        ) {
            AgentIcon(
                item.agentId,
                Modifier.padding(end = 12.dp, top = if (dense) 0.dp else 2.dp),
                contentDescription = null,
            )
            if (dense) {
                DenseBody(item, label, nowMs, place, Modifier.weight(1f))
            } else {
                FullBody(item, label, nowMs, place, Modifier.weight(1f))
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = if (dense) Modifier.size(40.dp) else Modifier) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.row_actions, label))
                }
                RowMenu(menuOpen, { menuOpen = false }, item, actions, placeInfo)
            }
        }
    }
}

/** Two compact lines: dot, agent and name; then status word and short age. */
@Composable
private fun DenseBody(
    item: TerminalItem,
    label: String,
    nowMs: Long,
    place: String?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val state = (item.status as? AgentStatus.Reported)?.state
    val word = statusWord(item)
    val age =
        item.sinceMs?.let {
            DateUtils
                .getRelativeTimeSpanString(
                    it,
                    nowMs,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE,
                ).toString()
        }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(statusColor(if (item.isFinished) null else state, scheme), CircleShape))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
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
        Text(
            listOfNotNull(place, word, age).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun statusWord(item: TerminalItem): String {
    if (item.isFinished) {
        return when {
            item.exitCode != null -> stringResource(R.string.row_finished_exit, item.exitCode)
            item.signal != null -> stringResource(R.string.row_finished_signal, item.signal)
            else -> stringResource(R.string.row_finished)
        }
    }
    val status = item.status as? AgentStatus.Reported ?: return stringResource(R.string.space_status_none)
    val progress = status.progress
    return when (status.state) {
        AgentState.Working -> {
            if (progress != null) {
                stringResource(R.string.row_working_progress, progress.coerceIn(0, 100))
            } else {
                stringResource(R.string.space_status_working)
            }
        }

        AgentState.Blocked -> {
            when (status.kind) {
                BlockKind.Permission -> stringResource(R.string.row_blocked_permission)
                BlockKind.Question -> stringResource(R.string.row_blocked_question)
                BlockKind.Auth -> stringResource(R.string.row_blocked_auth)
                else -> stringResource(R.string.space_status_blocked)
            }
        }

        AgentState.Idle -> {
            stringResource(R.string.space_status_idle)
        }

        AgentState.Done -> {
            stringResource(R.string.space_status_done)
        }

        AgentState.Error -> {
            stringResource(R.string.space_status_error)
        }

        AgentState.Other -> {
            status.rawState
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FullBody(
    item: TerminalItem,
    label: String,
    nowMs: Long,
    place: String?,
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
                if (place != null) {
                    Text(
                        place,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
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
    placeInfo: RowPlace?,
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
        if (placeInfo != null) RenameItems(item, placeInfo, actions, onDismiss)
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

/** "Rename tab…" and "Rename space…" for rows that live in a desktop layout; disabled while unreachable. */
@Composable
private fun ColumnScope.RenameItems(
    item: TerminalItem,
    place: RowPlace,
    actions: TerminalsActions,
    onDismiss: () -> Unit,
) {
    val enabled = item.writable && place.canRename
    place.tabRef?.let { ref ->
        val fallback = stringResource(R.string.workspace_tab_fallback, place.tabNumber)
        DropdownMenuItem(
            text = { Text(stringResource(CoreUiR.string.menu_rename_tab)) },
            onClick = {
                onDismiss()
                actions.onRename(item.hostId, ref, place.tabName, fallback)
            },
            enabled = enabled,
            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
        )
    }
    place.spaceRef?.let { ref ->
        val auto = place.spaceAutoName.ifBlank { stringResource(R.string.workspace_space_fallback, place.spaceNumber) }
        DropdownMenuItem(
            text = { Text(stringResource(CoreUiR.string.menu_rename_space)) },
            onClick = {
                onDismiss()
                actions.onRename(item.hostId, ref, place.spaceGivenName, auto)
            },
            enabled = enabled,
            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
        )
    }
}
