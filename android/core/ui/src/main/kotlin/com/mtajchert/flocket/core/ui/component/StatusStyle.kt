package com.mtajchert.flocket.core.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FrontHand
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.mtajchert.flocket.core.designsystem.theme.flocketColors
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.BlockKind
import com.mtajchert.flocket.core.ui.R

/**
 * Inbox group of a status. Declaration order is the display order: Needs you, Working, Done, Idle,
 * then [NoStatus] (unknown and unrecognised words).
 */
enum class StatusGroup {
    NeedsYou,
    Working,
    Done,
    Idle,
    NoStatus,
}

/** Group title, e.g. "Needs you". */
@Composable
fun StatusGroup.title(): String =
    stringResource(
        when (this) {
            StatusGroup.NeedsYou -> R.string.group_needs_you
            StatusGroup.Working -> R.string.group_working
            StatusGroup.Done -> R.string.group_done
            StatusGroup.Idle -> R.string.group_idle
            StatusGroup.NoStatus -> R.string.group_no_status
        },
    )

/** Color of the group header title: amber, primary, green, then neutral. */
@Composable
fun StatusGroup.color(): Color =
    when (this) {
        StatusGroup.NeedsYou -> MaterialTheme.flocketColors.attention
        StatusGroup.Working -> MaterialTheme.colorScheme.primary
        StatusGroup.Done -> MaterialTheme.flocketColors.done
        StatusGroup.Idle, StatusGroup.NoStatus -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/**
 * How an [AgentStatus] looks. Color always pairs with [icon] and [label].
 *
 * @property label the word, e.g. "Needs permission" or "Working 62%".
 * @property icon outlined icon for pills; [filledIcon] is the filled one for badges.
 * @property foreground text/icon color on the normal background (e.g. preview text, group title).
 * @property container pill background; [Color.Transparent] with [outlined] for "No status".
 * @property onContainer text/icon color on [container].
 * @property badge solid color of the tile badge; the icon on it is drawn in the background color.
 * @property group inbox group.
 * @property outlined draw a 1dp outline instead of a fill (only "No status").
 */
@Immutable
data class AgentStatusStyle(
    val label: String,
    val icon: ImageVector,
    val filledIcon: ImageVector,
    val foreground: Color,
    val container: Color,
    val onContainer: Color,
    val badge: Color,
    val group: StatusGroup,
    val outlined: Boolean = false,
)

/** Maps an [AgentStatus] to its [AgentStatusStyle] (state to design `ST`, see BRIEF decision 3). */
@Composable
fun agentStatusStyle(status: AgentStatus): AgentStatusStyle {
    val scheme = MaterialTheme.colorScheme
    val fixed = MaterialTheme.flocketColors
    if (status !is AgentStatus.Reported) {
        return AgentStatusStyle(
            label = stringResource(R.string.status_unknown),
            icon = Icons.AutoMirrored.Outlined.HelpOutline,
            filledIcon = Icons.AutoMirrored.Filled.HelpOutline,
            foreground = scheme.onSurfaceVariant,
            container = Color.Transparent,
            onContainer = scheme.onSurfaceVariant,
            badge = scheme.outline,
            group = StatusGroup.NoStatus,
            outlined = true,
        )
    }
    return when (status.state) {
        AgentState.Blocked -> {
            val question = status.kind == BlockKind.Question
            AgentStatusStyle(
                label =
                    when (status.kind) {
                        BlockKind.Permission -> stringResource(R.string.status_blocked_permission)
                        BlockKind.Question -> stringResource(R.string.status_blocked_question)
                        BlockKind.Auth -> stringResource(R.string.status_blocked_auth)
                        else -> stringResource(R.string.status_blocked)
                    },
                icon = if (question) Icons.Outlined.ChatBubbleOutline else Icons.Outlined.FrontHand,
                filledIcon = if (question) Icons.Filled.ChatBubble else Icons.Filled.FrontHand,
                foreground = fixed.attention,
                container = fixed.attentionContainer,
                onContainer = fixed.onAttentionContainer,
                badge = fixed.attentionSolid,
                group = StatusGroup.NeedsYou,
            )
        }

        AgentState.Error -> {
            AgentStatusStyle(
                label = stringResource(R.string.status_error),
                icon = Icons.Outlined.ErrorOutline,
                filledIcon = Icons.Filled.Error,
                foreground = scheme.error,
                container = scheme.errorContainer,
                onContainer = scheme.onErrorContainer,
                badge = fixed.errorSolid,
                group = StatusGroup.NeedsYou,
            )
        }

        AgentState.Working -> {
            val progress = status.progress
            AgentStatusStyle(
                label =
                    if (progress != null) {
                        stringResource(R.string.status_working_progress, progress.coerceIn(0, 100))
                    } else {
                        stringResource(R.string.status_working)
                    },
                icon = Icons.Outlined.Autorenew,
                filledIcon = Icons.Filled.Autorenew,
                foreground = scheme.primary,
                container = scheme.primaryContainer,
                onContainer = scheme.onPrimaryContainer,
                badge = scheme.primary,
                group = StatusGroup.Working,
            )
        }

        AgentState.Done -> {
            AgentStatusStyle(
                label = stringResource(R.string.status_done),
                icon = Icons.Outlined.CheckCircle,
                filledIcon = Icons.Filled.CheckCircle,
                foreground = fixed.done,
                container = fixed.doneContainer,
                onContainer = fixed.onDoneContainer,
                badge = fixed.done,
                group = StatusGroup.Done,
            )
        }

        AgentState.Idle -> {
            AgentStatusStyle(
                label = stringResource(R.string.status_idle),
                icon = Icons.Outlined.PauseCircle,
                filledIcon = Icons.Filled.PauseCircle,
                foreground = scheme.outline,
                container = scheme.surfaceContainerHigh,
                onContainer = scheme.onSurfaceVariant,
                badge = scheme.outline,
                group = StatusGroup.Idle,
            )
        }

        AgentState.Other -> {
            AgentStatusStyle(
                label = status.rawState,
                icon = Icons.Outlined.PlayCircle,
                filledIcon = Icons.Filled.PlayCircle,
                foreground = scheme.outline,
                container = scheme.surfaceContainerHigh,
                onContainer = scheme.onSurfaceVariant,
                badge = scheme.outline,
                group = StatusGroup.NoStatus,
            )
        }
    }
}
