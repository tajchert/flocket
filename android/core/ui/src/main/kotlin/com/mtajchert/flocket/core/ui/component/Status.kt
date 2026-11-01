package com.mtajchert.flocket.core.ui.component

import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.BlockKind
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

/** A small pill: icon + text. Meaning is never carried by color alone. */
@Composable
private fun StatusPill(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    border: Boolean = false,
    leading: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.animateContentSize(),
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
        border =
            if (border) {
                androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                )
            } else {
                null
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leading()
            Text(text = text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PillIcon(icon: ImageVector) = Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))

/** Connection state of a host: Idle, Connecting, Online, Reconnecting (with countdown), NeedsAttention. */
@Composable
fun ConnectionStateBadge(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    nowMs: Long = rememberNowMs(),
) {
    val scheme = MaterialTheme.colorScheme
    when (state) {
        ConnectionState.Idle -> {
            StatusPill(
                stringResource(R.string.connection_idle),
                scheme.surfaceContainerHighest,
                scheme.onSurfaceVariant,
                modifier,
            ) {
                PillIcon(Icons.Outlined.PauseCircle)
            }
        }

        is ConnectionState.Connecting -> {
            StatusPill(
                stringResource(R.string.connection_connecting),
                scheme.secondaryContainer,
                scheme.onSecondaryContainer,
                modifier,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }

        is ConnectionState.Online -> {
            StatusPill(
                stringResource(R.string.connection_online),
                scheme.primaryContainer,
                scheme.onPrimaryContainer,
                modifier,
            ) {
                PillIcon(Icons.Filled.CheckCircle)
            }
        }

        is ConnectionState.Reconnecting -> {
            val seconds = ((state.nextAttemptAtMs - nowMs) / 1000).coerceAtLeast(0).toInt()
            val text =
                if (seconds > 0) {
                    stringResource(R.string.connection_reconnecting_in, seconds)
                } else {
                    stringResource(R.string.connection_reconnecting)
                }
            StatusPill(
                text,
                scheme.tertiaryContainer,
                scheme.onTertiaryContainer,
                modifier,
            ) { PillIcon(Icons.Outlined.Sync) }
        }

        is ConnectionState.NeedsAttention -> {
            StatusPill(
                stringResource(R.string.connection_needs_attention),
                scheme.errorContainer,
                scheme.onErrorContainer,
                modifier,
            ) {
                PillIcon(Icons.Outlined.WarningAmber)
            }
        }
    }
}

/**
 * Program-reported status. [AgentStatus.Unknown] is neutral "No status" and never reads as Idle;
 * blocked and error are emphasised with container colors AND distinct icons and wording.
 */
@Composable
fun AgentStatusChip(
    status: AgentStatus,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    when (status) {
        AgentStatus.Unknown -> {
            StatusPill(
                stringResource(R.string.status_unknown),
                Color.Transparent,
                scheme.onSurfaceVariant,
                modifier,
                border = true,
            ) {
                PillIcon(Icons.AutoMirrored.Outlined.HelpOutline)
            }
        }

        is AgentStatus.Reported -> {
            val progress = status.progress
            when (status.state) {
                AgentState.Idle -> {
                    StatusPill(
                        stringResource(R.string.status_idle),
                        scheme.surfaceContainerHighest,
                        scheme.onSurfaceVariant,
                        modifier,
                    ) {
                        PillIcon(Icons.Outlined.PauseCircle)
                    }
                }

                AgentState.Working -> {
                    val text =
                        if (progress != null) {
                            stringResource(R.string.status_working_progress, progress.coerceIn(0, 100))
                        } else {
                            stringResource(R.string.status_working)
                        }
                    StatusPill(text, scheme.secondaryContainer, scheme.onSecondaryContainer, modifier) {
                        if (progress != null) {
                            CircularProgressIndicator(
                                progress = { progress.coerceIn(0, 100) / 100f },
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        }
                    }
                }

                AgentState.Done -> {
                    StatusPill(
                        stringResource(R.string.status_done),
                        scheme.primaryContainer,
                        scheme.onPrimaryContainer,
                        modifier,
                    ) {
                        PillIcon(Icons.Outlined.CheckCircle)
                    }
                }

                AgentState.Blocked -> {
                    val text =
                        when (status.kind) {
                            BlockKind.Permission -> stringResource(R.string.status_blocked_permission)
                            BlockKind.Question -> stringResource(R.string.status_blocked_question)
                            BlockKind.Auth -> stringResource(R.string.status_blocked_auth)
                            else -> stringResource(R.string.status_blocked)
                        }
                    StatusPill(text, scheme.tertiary, scheme.onTertiary, modifier) { PillIcon(Icons.Outlined.Block) }
                }

                AgentState.Error -> {
                    StatusPill(stringResource(R.string.status_error), scheme.error, scheme.onError, modifier) {
                        PillIcon(Icons.Outlined.ErrorOutline)
                    }
                }

                AgentState.Other -> {
                    StatusPill(status.rawState, scheme.surfaceContainerHighest, scheme.onSurfaceVariant, modifier) {
                        PillIcon(Icons.Outlined.PlayCircle)
                    }
                }
            }
        }
    }
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
