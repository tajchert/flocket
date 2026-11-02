@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.terminals.impl

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtajchert.flocket.core.designsystem.theme.FlocketMono
import com.mtajchert.flocket.core.designsystem.theme.flocketColors
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.BlockKind
import com.mtajchert.flocket.core.model.ShownStatus
import com.mtajchert.flocket.core.model.folderName
import com.mtajchert.flocket.core.ui.component.AgentIcon
import com.mtajchert.flocket.core.ui.component.AgentStatusChip
import com.mtajchert.flocket.core.ui.component.AgentTile
import com.mtajchert.flocket.feature.terminal.api.TerminalRoute
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
    /** Project and session, as the desktop's Agents panel names a row; see [rowNames]. */
    names: RowNames? = null,
    /** Priority mode: the row's space and tab, for the rename entries. */
    placeInfo: RowPlace? = null,
    /** Priority with several hosts: which host the terminal is on. */
    hostLabel: String? = null,
    /** Sub-agents are folded away: the row then says how many there are. */
    folded: Boolean = false,
    onToggleFold: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val shown = names ?: RowPlace.None.names(item)
    Surface(
        modifier = modifier.padding(horizontal = 8.dp).semantics { this.selected = selected },
        shape = MaterialTheme.shapes.large,
        color = if (selected) scheme.secondaryContainer else Color.Transparent,
    ) {
        Row(
            Modifier
                .clip(MaterialTheme.shapes.large)
                .combinedClickable(onClick = { actions.onOpen(item) }, onLongClick = { menuOpen = true })
                .heightIn(min = if (dense) 56.dp else 64.dp)
                .padding(
                    start = 8.dp,
                    top = if (dense) 6.dp else 8.dp,
                    bottom = if (dense) 6.dp else 8.dp,
                    end = 0.dp,
                ),
            verticalAlignment = Alignment.Top,
        ) {
            // The tile's status badge overhangs it by a fifth of its size, so the gap is wider than it looks.
            AgentTile(
                item.agentId,
                if (item.isFinished) AgentStatus.Unknown else badgeStatus(item),
                Modifier.padding(end = 20.dp, top = 2.dp),
                ringColor = if (selected) scheme.secondaryContainer else scheme.surface,
                contentDescription = null,
            )
            RowBody(item, shown, nowMs, hostLabel, selected, folded, onToggleFold, Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription =
                            stringResource(
                                R.string.row_actions,
                                listOfNotNull(shown.project, shown.session).joinToString(" · "),
                            ),
                    )
                }
                RowMenu(menuOpen, { menuOpen = false }, item, actions, placeInfo)
            }
        }
    }
}

/** The status the tile's badge shows: Done and Idle follow the shown status (seen or not), not the raw word. */
private fun badgeStatus(item: TerminalItem): AgentStatus {
    val reported = item.status as? AgentStatus.Reported ?: return item.status
    return when (item.shown) {
        ShownStatus.Done -> reported.copy(state = AgentState.Done)
        ShownStatus.Idle -> reported.copy(state = AgentState.Idle)
        else -> reported
    }
}

/**
 * How the desktop's Agents panel names a row: the project (its space, e.g. `flocket`) then the session
 * ([TerminalItem.detail], e.g. `QA`). The agent is not named: its icon already shows it.
 */
@Immutable
internal data class RowNames(
    val project: String,
    val session: String?,
)

/** Priority rows: no space header above, so the row names its space first. */
@Composable
internal fun RowPlace.names(item: TerminalItem): RowNames {
    val project =
        when {
            spaceName.isNotBlank() -> spaceName
            spaceNumber > 0 -> stringResource(R.string.workspace_space_fallback, spaceNumber)
            else -> folderName(item.cwd)
        } ?: return RowNames(item.detail ?: unnamed(item), null)
    return RowNames(project, item.detail?.takeIf { it != project })
}

/**
 * Grouped rows: the space header already names the project, so the session alone. Under tab labels the
 * tab is named already too, so only the program's title or the terminal's name; else the project.
 */
@Composable
internal fun groupedNames(
    item: TerminalItem,
    space: SpaceSection,
): RowNames {
    val own = item.liveTitle ?: item.name?.takeIf { it.isNotBlank() }
    val session = own ?: item.detail.takeUnless { space.showTabLabels }
    return RowNames(
        session ?: space.name.ifBlank { stringResource(R.string.workspace_space_fallback, space.number) },
        null,
    )
}

/** A row with neither a place, a folder nor a title (should not happen). */
@Composable
private fun unnamed(item: TerminalItem): String = item.agentLabel.ifBlank { stringResource(R.string.row_shell) }

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

        AgentState.Idle, AgentState.Done -> {
            stringResource(
                if (item.shown ==
                    ShownStatus.Done
                ) {
                    R.string.space_status_done
                } else {
                    R.string.space_status_idle
                },
            )
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
    names: RowNames,
    nowMs: Long,
    hostLabel: String?,
    selected: Boolean,
    folded: Boolean,
    onToggleFold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val fixed = MaterialTheme.flocketColors
    val reported = item.status as? AgentStatus.Reported
    val state = if (item.isFinished) null else reported?.state
    val title = names.project
    // Idle rows recede, like the desktop, unless they are the open one.
    val titleColor = if (item.shown == ShownStatus.Idle && !selected) scheme.onSurfaceVariant else scheme.onSurface
    // "· QA", like the desktop; the host (Priority with several hosts) follows.
    val subtitle = listOfNotNull(names.session, hostLabel).joinToString("") { "· $it " }.trimEnd()
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
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
            if (item.pinned) {
                Icon(
                    Icons.Outlined.PushPin,
                    contentDescription = stringResource(R.string.row_pinned),
                    Modifier.size(14.dp),
                    tint = scheme.onSurfaceVariant,
                )
            }
            if (item.children.isNotEmpty()) FoldToggle(item.children.size, folded, onToggleFold)
            if (item.shown == ShownStatus.NeedsYou) NeedsYouPill()
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

/** Compact "Needs you" pill in the status colors; the tile badge alone is easy to miss. */
@Composable
private fun NeedsYouPill() {
    val fixed = MaterialTheme.flocketColors
    Surface(shape = RoundedCornerShape(5.dp), color = fixed.attentionContainer) {
        Text(
            stringResource(CoreUiR.string.group_needs_you),
            Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = fixed.onAttentionContainer,
            maxLines = 1,
        )
    }
}

/** Chevron of a parent with sub-agents; folded, it also says how many are hidden. */
@Composable
private fun FoldToggle(
    count: Int,
    folded: Boolean,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(if (folded) R.string.row_subagents_show else R.string.row_subagents_hide, count)
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onToggle).padding(horizontal = 2.dp).semantics {
            contentDescription = label
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (folded) Text("$count", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Icon(
            if (folded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
            contentDescription = null,
            Modifier.size(16.dp),
            tint = scheme.onSurfaceVariant,
        )
    }
}

/** A row and, unless folded, its sub-agents indented below it. */
@Composable
internal fun TerminalRowTree(
    item: TerminalItem,
    selected: TerminalRoute?,
    nowMs: Long,
    actions: TerminalsActions,
    modifier: Modifier = Modifier,
    /** Extra start padding for the row and its sub-agents. */
    indent: Dp = 0.dp,
    dense: Boolean = false,
    names: RowNames? = null,
    placeInfo: RowPlace? = null,
    hostLabel: String? = null,
    folded: Boolean = false,
    onToggleFold: () -> Unit = {},
) {
    fun isOpen(
        hostId: String,
        address: String,
    ) = selected != null && selected.hostId == hostId && selected.address == address
    Column(modifier) {
        TerminalRow(
            item = item,
            selected = isOpen(item.hostId.value, item.address),
            nowMs = nowMs,
            actions = actions,
            modifier = Modifier.padding(start = indent),
            dense = dense,
            names = names,
            placeInfo = placeInfo,
            hostLabel = hostLabel,
            folded = folded,
            onToggleFold = onToggleFold,
        )
        if (!folded) {
            item.children.forEach { child ->
                ChildRow(child, isOpen(child.hostId.value, child.address), {
                    actions.onOpenChild(child)
                }, Modifier.padding(start = indent))
            }
        }
    }
}

/** A sub-agent: smaller, indented, with the status glyph at the right (desktop `subRow`). */
@Composable
private fun ChildRow(
    child: ChildItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 52.dp, end = 12.dp)
            .heightIn(min = 36.dp)
            .semantics { this.selected = selected },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AgentIcon(child.agentId, size = 18.dp, contentDescription = null)
        Text(
            child.title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        StatusGlyph(child.shown)
    }
}

/** Working spins, Needs you is a solid warning dot, Idle is a ring; Done a filled dot. */
@Composable
private fun StatusGlyph(shown: ShownStatus?) {
    val scheme = MaterialTheme.colorScheme
    val fixed = MaterialTheme.flocketColors
    when (shown) {
        ShownStatus.Working -> {
            CircularProgressIndicator(Modifier.size(12.dp), color = scheme.primary, strokeWidth = 1.5.dp)
        }

        ShownStatus.NeedsYou -> {
            Box(Modifier.size(8.dp).background(fixed.attentionSolid, CircleShape))
        }

        ShownStatus.Done -> {
            Box(Modifier.size(8.dp).background(scheme.primary, CircleShape))
        }

        else -> {
            Box(Modifier.size(8.dp).border(1.5.dp, scheme.outline, CircleShape))
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
        if (item.supportsLabels && item.writable && !item.isFinished) {
            DropdownMenuItem(
                text = { Text(stringResource(if (item.pinned) R.string.action_unpin else R.string.action_pin)) },
                onClick = {
                    onDismiss()
                    actions.onPin(item, !item.pinned)
                },
                leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
            )
            if (item.canMarkUnread) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_mark_unread)) },
                    onClick = run(actions.onMarkUnread),
                    leadingIcon = { Icon(Icons.Outlined.MarkChatUnread, contentDescription = null) },
                )
            }
        }
        if (!item.isFinished) {
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
