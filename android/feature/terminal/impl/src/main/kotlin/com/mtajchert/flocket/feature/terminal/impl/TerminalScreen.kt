@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.terminal.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.ui.component.AgentStatusChip
import com.mtajchert.flocket.core.ui.component.ConnectionStateBadge
import com.mtajchert.flocket.core.ui.error.asString

/** Everything the screen can ask for. Defaults are no-ops (previews, screenshot tests). */
@Immutable
data class TerminalActions(
    val onBack: () -> Unit = {},
    val onFit: () -> Unit = {},
    val onPaste: () -> Unit = {},
    val onCopy: () -> Unit = {},
    val onInterrupt: () -> Unit = {},
    val onKill: () -> Unit = {},
    val onConfirmKill: () -> Unit = {},
    val onDismissKill: () -> Unit = {},
    val onToggleKeyboard: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onRemove: () -> Unit = {},
    val onReattach: () -> Unit = {},
    val onDismissFreshShell: () -> Unit = {},
    val onClipboardAnswer: (Boolean) -> Unit = {},
)

/**
 * The terminal screen chrome. [terminal] is the body slot: the live `TerminalSurface` in the app,
 * a `TerminalSnapshot` in screenshot tests. [extraKeys] sits under it, above the IME.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    state: TerminalUiState,
    actions: TerminalActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    showBack: Boolean = true,
    extraKeys: @Composable () -> Unit = {},
    terminal: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TerminalTopBar(state, actions, showBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding(),
        ) {
            if (state.freshShell) {
                StateBanner(
                    icon = Icons.Outlined.Info,
                    text = stringResource(R.string.terminal_fresh_shell),
                    actions = listOf(stringResource(R.string.terminal_dismiss) to actions.onDismissFreshShell),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                terminal(Modifier.fillMaxSize())
                when (state.phase) {
                    TerminalPhase.Connecting -> {
                        ProgressOverlay(
                            stringResource(R.string.terminal_connecting),
                            Modifier.align(Alignment.Center),
                        )
                    }

                    TerminalPhase.Attaching -> {
                        ProgressOverlay(
                            stringResource(R.string.terminal_attaching),
                            Modifier.align(Alignment.Center),
                        )
                    }

                    else -> {
                        Unit
                    }
                }
                if (state.phase == TerminalPhase.Live && !state.streamConnected) {
                    ReconnectingChip(Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
            }
            EndBanner(state, actions)
            if (state.showExtraKeys && state.canInteract) extraKeys()
            if (!state.showExtraKeys || !state.canInteract) Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (state.confirmKill) {
        AlertDialog(
            onDismissRequest = actions.onDismissKill,
            title = { Text(stringResource(R.string.terminal_kill_title)) },
            text = { Text(stringResource(R.string.terminal_kill_message, state.address)) },
            confirmButton = {
                TextButton(onClick = actions.onConfirmKill) {
                    Text(stringResource(R.string.terminal_kill_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = actions.onDismissKill,
                ) { Text(stringResource(R.string.terminal_cancel)) }
            },
        )
    }
    state.clipboardRequest?.let { text ->
        AlertDialog(
            onDismissRequest = { actions.onClipboardAnswer(false) },
            title = { Text(stringResource(R.string.terminal_clipboard_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.terminal_clipboard_message))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = text.take(CLIPBOARD_PREVIEW_CHARS),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .heightIn(
                                        max = 160.dp,
                                    ).verticalScroll(rememberScrollState())
                                    .padding(8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { actions.onClipboardAnswer(true) },
                ) { Text(stringResource(R.string.terminal_clipboard_allow)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { actions.onClipboardAnswer(false) },
                ) { Text(stringResource(R.string.terminal_clipboard_deny)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalTopBar(
    state: TerminalUiState,
    actions: TerminalActions,
    showBack: Boolean,
) {
    TopAppBar(
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = actions.onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.terminal_back))
                }
            }
        },
        title = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = headline(state),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = subtitle(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (state.connection !is ConnectionState.Online) {
                        ConnectionStateBadge(state.connection)
                    } else if (state.status != AgentStatus.Unknown) {
                        AgentStatusChip(state.status)
                    }
                }
            }
        },
        actions = {
            if (state.phase == TerminalPhase.Live && !state.isOwner && state.streamConnected) {
                FitAction(actions.onFit)
            }
            if (state.canInteract) {
                IconButton(onClick = actions.onToggleKeyboard, modifier = Modifier.testTag("terminal_keyboard")) {
                    Icon(Icons.Outlined.Keyboard, stringResource(R.string.terminal_action_keyboard))
                }
            }
            OverflowMenu(state, actions)
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FitAction(onFit: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(stringResource(R.string.terminal_action_fit_tooltip)) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onFit) {
            Icon(Icons.Outlined.FitScreen, stringResource(R.string.terminal_action_fit))
        }
    }
}

@Composable
private fun OverflowMenu(
    state: TerminalUiState,
    actions: TerminalActions,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.terminal_action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val live = state.canInteract
            MenuItem(Icons.Outlined.ContentPaste, R.string.terminal_action_paste, enabled = live) {
                open = false
                actions.onPaste()
            }
            MenuItem(Icons.Outlined.ContentCopy, R.string.terminal_action_copy, enabled = true) {
                open = false
                actions.onCopy()
            }
            MenuItem(Icons.Outlined.StopCircle, R.string.terminal_action_interrupt, enabled = live) {
                open = false
                actions.onInterrupt()
            }
            MenuItem(
                Icons.Outlined.PowerSettingsNew,
                R.string.terminal_action_kill,
                enabled =
                    state.isOnline && state.phase !is TerminalPhase.Exited,
            ) {
                open = false
                actions.onKill()
            }
        }
    }
}

@Composable
private fun MenuItem(
    icon: ImageVector,
    label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, null) },
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun headline(state: TerminalUiState): String =
    listOf(state.agentLabel, state.title)
        .filter {
            it.isNotBlank()
        }.distinct()
        .joinToString(" · ")
        .ifEmpty { state.address }

@Composable
private fun subtitle(state: TerminalUiState): String {
    val host = state.hostLabel.ifBlank { "…" }
    return if (state.machine != null) {
        stringResource(R.string.terminal_subtitle_machine, host, state.machine, state.address)
    } else {
        stringResource(R.string.terminal_subtitle_host, host, state.address)
    }
}

@Composable
private fun EndBanner(
    state: TerminalUiState,
    actions: TerminalActions,
) {
    when (val p = state.phase) {
        is TerminalPhase.Exited -> {
            StateBanner(
                icon = if (p.exitCode == 0) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                text =
                    when {
                        p.exitCode != null -> stringResource(R.string.terminal_exited_code, p.exitCode)
                        p.signal != null -> stringResource(R.string.terminal_exited_signal, p.signal)
                        else -> stringResource(R.string.terminal_exited)
                    },
                actions =
                    listOf(
                        stringResource(R.string.terminal_remove) to actions.onRemove,
                        stringResource(R.string.terminal_close) to actions.onClose,
                    ),
            )
        }

        TerminalPhase.WaitingForShell -> {
            StateBanner(Icons.Outlined.HourglassEmpty, stringResource(R.string.terminal_waiting_shell), progress = true)
        }

        TerminalPhase.StreamEnded -> {
            StateBanner(
                icon = Icons.Outlined.LinkOff,
                text = stringResource(R.string.terminal_stream_ended),
                actions =
                    listOf(
                        stringResource(R.string.terminal_close) to actions.onClose,
                        stringResource(R.string.terminal_reattach) to actions.onReattach,
                    ),
            )
        }

        is TerminalPhase.Failed -> {
            StateBanner(
                icon = Icons.Outlined.ErrorOutline,
                text =
                    stringResource(
                        R.string.terminal_error_format,
                        p.error.title.asString(),
                        p.error.message.asString(),
                    ),
                actions = listOf(stringResource(R.string.terminal_retry) to actions.onReattach),
                error = true,
            )
        }

        TerminalPhase.HostMissing -> {
            StateBanner(
                icon = Icons.Outlined.ErrorOutline,
                text = stringResource(R.string.terminal_host_missing),
                actions = listOf(stringResource(R.string.terminal_close) to actions.onClose),
                error = true,
            )
        }

        else -> {
            Unit
        }
    }
}

@Composable
private fun StateBanner(
    icon: ImageVector,
    text: String,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    progress: Boolean = false,
    error: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (error) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (error) colors.onErrorContainer else colors.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().testTag("terminal_banner"),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (progress) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, null, Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            actions.forEach { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
        }
    }
}

@Composable
private fun ProgressOverlay(
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ReconnectingChip(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shadowElevation = 2.dp,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.terminal_reconnecting), style = MaterialTheme.typography.labelMedium)
        }
    }
}

private const val CLIPBOARD_PREVIEW_CHARS = 2_000
