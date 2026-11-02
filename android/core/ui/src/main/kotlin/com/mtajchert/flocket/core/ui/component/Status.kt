package com.mtajchert.flocket.core.ui.component

import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.designsystem.theme.FlocketCorners
import com.mtajchert.flocket.core.designsystem.theme.flocketColors
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.ui.R
import kotlinx.coroutines.delay

/** Wall clock that ticks every [intervalMs]; pass an explicit `nowMs` in previews for determinism. */
@Composable
fun rememberNowMs(intervalMs: Long = 1_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMs) {
        while (true) {
            delay(intervalMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** Agent status pill: 24dp tall (grows with font scale), 6dp radius, 14dp icon, 11sp semibold label. */
@Composable
private fun StatusPill(
    text: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
) {
    Surface(
        modifier = modifier.animateContentSize(),
        shape = FlocketCorners.StatusPill,
        color = container,
        contentColor = content,
        border = if (outlined) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 24.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(text = text, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

/** Host status pill: 24dp tall, 12dp radius, a 7dp dot and the word. */
@Composable
private fun HostPill(
    text: String,
    container: Color,
    content: Color,
    dot: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.animateContentSize(),
        shape = FlocketCorners.HostPill,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 24.dp).padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(Modifier.size(7.dp).background(dot, CircleShape))
            Text(text = text, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

/**
 * Connection state of a host as the design's host pill: Online (green), Needs attention (amber),
 * Idle, Connecting and Reconnecting (neutral, with the countdown).
 */
@Composable
fun ConnectionStateBadge(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    nowMs: Long = rememberNowMs(),
) {
    val scheme = MaterialTheme.colorScheme
    val fixed = MaterialTheme.flocketColors
    val neutral = scheme.surfaceContainerHigh
    when (state) {
        ConnectionState.Idle -> {
            HostPill(
                stringResource(R.string.connection_idle),
                neutral,
                scheme.onSurfaceVariant,
                scheme.outline,
                modifier,
            )
        }

        is ConnectionState.Connecting -> {
            HostPill(
                stringResource(R.string.connection_connecting),
                neutral,
                scheme.onSurfaceVariant,
                scheme.primary,
                modifier,
            )
        }

        is ConnectionState.Online -> {
            HostPill(
                stringResource(R.string.connection_online),
                fixed.doneContainer,
                fixed.onDoneContainer,
                fixed.done,
                modifier,
            )
        }

        is ConnectionState.Reconnecting -> {
            val seconds = ((state.nextAttemptAtMs - nowMs) / 1_000L).coerceAtLeast(0L).toInt()
            val text =
                if (seconds > 0) {
                    stringResource(R.string.connection_reconnecting_in, seconds)
                } else {
                    stringResource(R.string.connection_reconnecting)
                }
            HostPill(text, neutral, scheme.onSurfaceVariant, fixed.attention, modifier)
        }

        is ConnectionState.NeedsAttention -> {
            HostPill(
                stringResource(R.string.connection_needs_attention),
                fixed.attentionContainer,
                fixed.onAttentionContainer,
                fixed.attention,
                modifier,
            )
        }
    }
}

/**
 * Program-reported status as the design pill (see [agentStatusStyle]). [AgentStatus.Unknown] is
 * neutral "No status" and never reads as Idle; blocked and error use amber/red containers AND
 * distinct icons and wording.
 */
@Composable
fun AgentStatusChip(
    status: AgentStatus,
    modifier: Modifier = Modifier,
) {
    val style = agentStatusStyle(status)
    StatusPill(style.label, style.icon, style.container, style.onContainer, modifier, style.outlined)
}

/** Shown above cached data from a host that is not live: the data is read-only until it reconnects. */
@Composable
fun StaleDataBanner(
    confirmedAtMs: Long?,
    modifier: Modifier = Modifier,
    nowMs: Long = rememberNowMs(intervalMs = 30_000L),
) {
    val text =
        if (confirmedAtMs == null) {
            stringResource(R.string.stale_banner_never)
        } else {
            val ago = DateUtils.getRelativeTimeSpanString(confirmedAtMs, nowMs, DateUtils.MINUTE_IN_MILLIS).toString()
            stringResource(R.string.stale_banner, ago)
        }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
