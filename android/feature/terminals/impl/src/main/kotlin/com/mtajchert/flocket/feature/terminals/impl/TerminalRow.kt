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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtajchert.flocket.core.designsystem.theme.FlocketMono
import com.mtajchert.flocket.core.designsystem.theme.flocketColors
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.BlockKind
import com.mtajchert.flocket.core.ui.component.AgentIcon
import com.mtajchert.flocket.core.ui.component.AgentStatusChip
import com.mtajchert.flocket.core.ui.component.AgentTile
import com.mtajchert.flocket.core.ui.R as CoreUiR

/** One terminal. Tap opens it; long-press or the overflow button opens the action menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TerminalRow(
    item: TerminalItem,
    selected: Boolean,
    nowMs: Long,
    actions: TerminalsActions,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    /** Priority and inbox modes: where the terminal lives ("Space · Tab"), shown beside the agent. */
    place: String? = null,
    /** Priority and inbox modes: the row's space and tab, for the rename entries. */
    placeInfo: RowPlace? = null,
    /** Inbox with several hosts: which host the terminal is on. */
    hostLabel: String? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val label = item.agentLabel.ifBlank { stringResource(R.string.row_shell) }
    Surface(
        modifier = modifier.padding(horizontal = 8.dp).semantics { this.selected = selected },
        shape = MaterialTheme.shapes.large,
        color = if (selected) scheme.secondaryContainer else Color.Transparent,
    ) {
        Row(
            Modifier
                .clip(MaterialTheme.shapes.large)
                .combinedClickable(onClick = { actions.onOpen(item) }, onLongClick = { menuOpen = true })
                .heightIn(min = if (dense) 56.dp else 72.dp)
                .padding(
                    start = 8.dp,
                    top = if (dense) 6.dp else 10.dp,
                    bottom = if (dense) 6.dp else 10.dp,
                    end = 0.dp,
                ),
            verticalAlignment = Alignment.Top,
        ) {
            // The tile's status badge overhangs it by a fifth of its size, so the gap is wider than it looks.
            AgentTile(
                item.agentId,
                if (item.isFinished) AgentStatus.Unknown else item.status,
                Modifier.padding(end = 20.dp, top = 2.dp),
                ringColor = if (selected) scheme.secondaryContainer else scheme.surface,
                contentDescription = null,
            )
            RowBody(item, label, nowMs, place, hostLabel, Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.row_actions, label))
                }
                RowMenu(menuOpen, { menuOpen = false }, item, actions, placeInfo)
            }
        }
    }
}

/** Compact age: "now", "5m", "3h", "2d". */
@Composable
private fun shortAge(
    sinceMs: Long,
    nowMs: Long,
): String {
    val minutes = ((nowMs - sinceMs) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(R.string.age_now)
        minutes < 60 -> stringResource(R.string.age_minutes, minutes.toInt())
        minutes < 60 * 24 -> stringResource(R.string.age_hours, (minutes / 60).toInt())
        else -> stringResource(R.string.age_days, (minutes / (60 * 24)).toInt())
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

@Composable
private fun RowBody(
    item: TerminalItem,
    label: String,
    nowMs: Long,
    place: String?,
    hostLabel: String?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val fixed = MaterialTheme.flocketColors
    val reported = item.status as? AgentStatus.Reported
    val state = if (item.isFinished) null else reported?.state
    val title = item.name?.takeIf { it.isNotBlank() } ?: label
    val subtitle =
        listOfNotNull(
            label.takeIf { !item.name.isNullOrBlank() },
            place ?: item.cwd.takeIf { it.isNotBlank() },
            hostLabel,
        ).joinToString(" · ")
    val previewColor =
        when (state) {
            AgentState.Blocked -> fixed.attention
            AgentState.Error -> scheme.error
            else -> scheme.onSurfaceVariant
        }
    val preview = item.headline.ifBlank { statusWord(item) }
    val progress = if (state == AgentState.Working) reported?.progress else null
    Column(modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            if (!item.writable) {
                Icon(
                    Icons.Outlined.CloudOff,
                    contentDescription = stringResource(R.string.row_stale),
                    Modifier.size(14.dp),
                    tint = scheme.onSurfaceVariant,
                )
            }
            item.sinceMs?.let {
                Text(
                    shortAge(it, nowMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Text(
            preview,
            style = FlocketMono.small,
            color = previewColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0, 100) / 100f },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                color = scheme.primary,
                trackColor = scheme.primaryContainer,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        if (item.liveness != LivenessKind.Running) LivenessLabel(item)
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
