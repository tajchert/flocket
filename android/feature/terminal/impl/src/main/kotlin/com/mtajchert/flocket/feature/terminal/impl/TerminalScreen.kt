@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.terminal.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtajchert.flocket.core.designsystem.theme.FlocketCorners
import com.mtajchert.flocket.core.designsystem.theme.FlocketMono
import com.mtajchert.flocket.core.designsystem.theme.flocketColors
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.LayoutRef
import com.mtajchert.flocket.core.ui.component.AgentStatusChip
import com.mtajchert.flocket.core.ui.component.ConnectionStateBadge
import com.mtajchert.flocket.core.ui.component.NeedYouPill
import com.mtajchert.flocket.core.ui.component.RenameDialog
import com.mtajchert.flocket.core.ui.component.WorkingDots
import com.mtajchert.flocket.core.ui.component.agentStatusStyle
import com.mtajchert.flocket.core.ui.error.asString
import com.mtajchert.flocket.core.ui.nav.LocalPaneLayout
import com.mtajchert.flocket.core.ui.nav.LocalSidebarToggle
import com.mtajchert.flocket.core.ui.nav.PaneLayout
import com.mtajchert.flocket.feature.terminal.impl.share.ShareActions
import com.mtajchert.flocket.feature.terminal.impl.share.ShareCard
import com.mtajchert.flocket.feature.terminal.impl.share.SharesUi

/** Everything the screen can ask for. Defaults are no-ops (previews, screenshot tests). */
@Immutable
data class TerminalActions(
    val onBack: () -> Unit = {},
    /** Open another terminal of the same host (tab strip). */
    val onOpenTerminal: (address: String) -> Unit = {},
    /** Rename a tab of the strip: its desktop tab, current given name, automatic name. */
    val onRenameTab: (LayoutRef, String, String) -> Unit = { _, _, _ -> },
    val onRenameChange: (String) -> Unit = {},
    val onRenameSave: () -> Unit = {},
    val onRenameDismiss: () -> Unit = {},
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
    val onClipboardAlways: () -> Unit = {},
    /** A reply chip (same input path as the extra keys). */
    val onReply: (ReplyKey) -> Unit = {},
    /** Full-screen mode: hide (true) or bring back (false) the top bar and tab strip. */
    val onTopBarHidden: (Boolean) -> Unit = {},
)

/**
 * The terminal screen chrome. [terminal] is the body slot: the live `TerminalSurface` in the app,
 * a `TerminalSnapshot` in screenshot tests. [extraKeys] sits under it, above the IME.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    state: TerminalUiState,
    actions: TerminalActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    showBack: Boolean = true,
    tabStrip: TabStripUi? = null,
    header: TerminalHeaderUi = TerminalHeaderUi(),
    extraKeys: @Composable () -> Unit = {},
    // --- agent shares (T5) ---
    shares: SharesUi = SharesUi(),
    shareActions: ShareActions = ShareActions(),
    // --- end agent shares ---
    terminal: @Composable (Modifier) -> Unit,
) {
    val layout = LocalPaneLayout.current
    val fullScreen = state.topBarHidden
    // A landscape phone has no room for the strip above the IME.
    val stripVisible =
        tabStrip != null && tabStrip.isVisible && !fullScreen && !(layout.compactHeight && WindowInsets.isImeVisible)
    Scaffold(
        modifier = modifier,
        topBar = { if (!fullScreen) TerminalTopBar(state, header, actions, showBack, shares, shareActions) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(paneInsetSides(layout)),
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding(),
        ) {
            if (stripVisible &&
                tabStrip != null
            ) {
                TerminalTabStrip(tabStrip, actions.onOpenTerminal, onRename = actions.onRenameTab)
            }
            WorkProgressLine(state.status)
            if (state.freshShell) {
                StateBanner(
                    icon = Icons.Outlined.Info,
                    text = stringResource(R.string.terminal_fresh_shell),
                    actions = listOf(stringResource(R.string.terminal_dismiss) to actions.onDismissFreshShell),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                terminal(Modifier.fillMaxSize())
                // Over the terminal, never above it: a layout change would resize the remote terminal.
                shares.card?.let { ShareCard(it, shareActions, Modifier.align(Alignment.TopCenter).padding(8.dp)) }
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
                Column(
                    Modifier.align(Alignment.TopEnd).padding(8.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (fullScreen) FullScreenCorner(state, header, actions)
                    if (state.phase == TerminalPhase.Live && !state.streamConnected) ReconnectingChip()
                    if (state.sizeTakenByOther) SizeTakenChip(actions.onFit)
                }
            }
            EndBanner(state, actions)
            if (state.showExtraKeys && state.canInteract) {
                ReplyBar(state.status, actions.onReply)
                extraKeys()
            } else {
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }

    state.rename?.let { r ->
        RenameDialog(
            isTab = true,
            text = r.text,
            placeholder = r.placeholder,
            onTextChange = actions.onRenameChange,
            onSave = actions.onRenameSave,
            onDismiss = actions.onRenameDismiss,
        )
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
                    TextButton(
                        onClick = actions.onClipboardAlways,
                        modifier = Modifier.testTag("clipboard_always"),
                    ) { Text(stringResource(R.string.terminal_clipboard_always)) }
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
    header: TerminalHeaderUi,
    actions: TerminalActions,
    showBack: Boolean,
    shares: SharesUi,
    shareActions: ShareActions,
) {
    val layout = LocalPaneLayout.current
    val sidebar = LocalSidebarToggle.current
    BoxWithConstraints {
        // The status pill fits beside the title only in a wide pane (tablet, unfolded).
        val wide = maxWidth >= WIDE_BAR
        TopAppBar(
            navigationIcon = {
                if (layout.multiPane) {
                    // The list sits beside the terminal: no back arrow. The list hides itself from its own
                    // header; once hidden (focus mode), this button brings it back.
                    if (!layout.sidebarVisible) {
                        IconButton(
                            onClick = { sidebar.setSidebarVisible(true) },
                            modifier = Modifier.testTag("terminal_sidebar_toggle"),
                        ) {
                            Icon(Icons.Outlined.Menu, stringResource(R.string.terminal_sidebar_show))
                        }
                    }
                } else if (showBack) {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.terminal_back))
                    }
                }
            },
            title = { TitleBlock(state, header) },
            actions = {
                if (wide && state.isOnline && state.status != AgentStatus.Unknown) {
                    AgentStatusChip(state.status, Modifier.padding(end = 8.dp))
                }
                NextNeedsYou(header, actions)
                // With the extra keys on, the keyboard toggle sits in their bar.
                if (state.canInteract && !state.showExtraKeys) {
                    IconButton(onClick = actions.onToggleKeyboard, modifier = Modifier.testTag("terminal_keyboard")) {
                        Icon(Icons.Outlined.Keyboard, stringResource(R.string.terminal_action_keyboard))
                    }
                }
                OverflowMenu(state, actions, shares, shareActions)
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            windowInsets = TopAppBarDefaults.windowInsets.only(paneInsetSides(layout)),
        )
    }
}

/** Name and agent, then a status dot and "space · host" (or the connection state while not online). */
@Composable
private fun TitleBlock(
    state: TerminalUiState,
    header: TerminalHeaderUi,
) {
    val name = state.title.ifBlank { state.agentLabel }.ifBlank { state.address }
    val agent = state.agentLabel.takeIf { it.isNotBlank() && it != name }
    val working = state.isOnline && (state.status as? AgentStatus.Reported)?.state == AgentState.Working
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Flocket's own mark where the agent's title spinner was (shownTitle drops that one).
            if (working) WorkingDots(color = MaterialTheme.colorScheme.primary, size = 4.5.dp)
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (agent != null) {
                Text(
                    text = agent,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (state.isOnline && state.status != AgentStatus.Unknown) {
                val style = agentStatusStyle(state.status)
                Box(
                    Modifier
                        .size(7.dp)
                        .background(style.foreground, CircleShape)
                        .semantics { contentDescription = style.label },
                )
            }
            Text(
                text = subtitle(state, header),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (state.connection !is ConnectionState.Online) ConnectionStateBadge(state.connection)
        }
    }
}

@Composable
private fun OverflowMenu(
    state: TerminalUiState,
    actions: TerminalActions,
    shares: SharesUi,
    shareActions: ShareActions,
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
            if (shares.all.isNotEmpty()) {
                MenuItem(Icons.Outlined.FolderOpen, R.string.share_action_shared_files, enabled = true) {
                    open = false
                    shareActions.onShowSheet()
                }
            }
            MenuItem(Icons.Outlined.StopCircle, R.string.terminal_action_interrupt, enabled = live) {
                open = false
                actions.onInterrupt()
            }
            MenuItem(Icons.Outlined.Fullscreen, R.string.terminal_action_hide_top_bar, enabled = true) {
                open = false
                actions.onTopBarHidden(true)
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

/**
 * What stays of the top bar in full-screen mode, in the terminal's top-end corner: the host's connection
 * state while it is not online (stale data stays labelled), the "next needs you" pill, and the button
 * that brings the bar back.
 */
@Composable
private fun FullScreenCorner(
    state: TerminalUiState,
    header: TerminalHeaderUi,
    actions: TerminalActions,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.connection !is ConnectionState.Online) ConnectionStateBadge(state.connection)
        NextNeedsYou(header, actions)
        // Translucent so the terminal text under the corner stays readable.
        Surface(
            onClick = { actions.onTopBarHidden(false) },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shadowElevation = 1.dp,
            modifier = Modifier.testTag("terminal_show_top_bar"),
        ) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.FullscreenExit,
                    stringResource(R.string.terminal_action_show_top_bar),
                    Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Jumps to the next other terminal that needs the user; nothing when none does. */
@Composable
private fun NextNeedsYou(
    header: TerminalHeaderUi,
    actions: TerminalActions,
) {
    val next = header.nextNeedsYou ?: return
    if (header.otherNeedsYou <= 0) return
    val description =
        pluralStringResource(R.plurals.terminal_next_needs_you, header.otherNeedsYou, header.otherNeedsYou)
    NeedYouPill(
        count = header.otherNeedsYou,
        label = header.otherNeedsYou.toString(),
        trailingArrow = true,
        onClick = { actions.onOpenTerminal(next) },
        modifier = Modifier.semantics { contentDescription = description }.testTag("terminal_next_needs_you"),
    )
}

/** "space · host" (plus the federated machine); without a known space, "host · address" as before. */
@Composable
private fun subtitle(
    state: TerminalUiState,
    header: TerminalHeaderUi,
): String {
    val host = state.hostLabel.ifBlank { "…" }
    val space =
        header.spaceName.ifBlank {
            if (header.spaceNumber > 0) stringResource(R.string.terminal_space_fallback, header.spaceNumber) else ""
        }
    return when {
        space.isNotEmpty() -> listOfNotNull(space, host, state.machine).joinToString(" · ")
        state.machine != null -> stringResource(R.string.terminal_subtitle_machine, host, state.machine, state.address)
        else -> stringResource(R.string.terminal_subtitle_host, host, state.address)
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
                        p.reason == REASON_KILLED -> stringResource(R.string.terminal_killed)
                        p.signal != null -> stringResource(R.string.terminal_exited_signal, p.signal)
                        p.exitCode != null -> stringResource(R.string.terminal_exited_code, p.exitCode)
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
                tone = BannerTone.Attention,
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
                tone = BannerTone.Error,
            )
        }

        TerminalPhase.HostMissing -> {
            StateBanner(
                icon = Icons.Outlined.ErrorOutline,
                text = stringResource(R.string.terminal_host_missing),
                actions = listOf(stringResource(R.string.terminal_close) to actions.onClose),
                tone = BannerTone.Error,
            )
        }

        else -> {
            Unit
        }
    }
}

/** Neutral for information, amber when the user has to act, red only for errors. */
private enum class BannerTone { Neutral, Attention, Error }

@Composable
private fun StateBanner(
    icon: ImageVector,
    text: String,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    progress: Boolean = false,
    tone: BannerTone = BannerTone.Neutral,
) {
    val colors = MaterialTheme.colorScheme
    val fixed = MaterialTheme.flocketColors
    val (container, content) =
        when (tone) {
            BannerTone.Neutral -> colors.surfaceContainerHigh to colors.onSurface
            BannerTone.Attention -> fixed.attentionContainer to fixed.onAttentionContainer
            BannerTone.Error -> colors.errorContainer to colors.onErrorContainer
        }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("terminal_banner"),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (progress) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = content)
            } else {
                Icon(icon, null, Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            actions.forEach { (label, onClick) ->
                TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = content)) {
                    Text(label)
                }
            }
        }
    }
}

/** 3dp bar under the top chrome while the agent reports a Working percentage (never invented). */
@Composable
private fun WorkProgressLine(status: AgentStatus) {
    val reported = status as? AgentStatus.Reported ?: return
    val percent = reported.progress?.takeIf { reported.state == AgentState.Working } ?: return
    val fraction = percent.coerceIn(0, 100) / 100f
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }
            .testTag("terminal_progress"),
    ) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
    }
}

/**
 * One-tap replies above the extra keys: menu numbers while the agent is blocked, Esc while it works.
 * Shown only with the extra keys (live, writable stream), so a reply is never queued or replayed.
 */
@Composable
private fun ReplyBar(
    status: AgentStatus,
    onReply: (ReplyKey) -> Unit,
) {
    val replies = ReplyKey.forStatus(status)
    if (replies.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val working = replies == listOf(ReplyKey.Escape)
    Column(Modifier.fillMaxWidth().background(colors.surfaceContainerLow)) {
        HorizontalDivider(color = colors.outlineVariant)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .testTag("terminal_replies"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text =
                    stringResource(
                        if (working) R.string.terminal_reply_send else R.string.terminal_reply_hint,
                    ).uppercase(),
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.3.sp,
                    ),
                color = colors.onSurfaceVariant,
            )
            replies.forEach { reply -> ReplyChip(reply, onReply) }
        }
    }
}

@Composable
private fun ReplyChip(
    reply: ReplyKey,
    onReply: (ReplyKey) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interrupt = reply == ReplyKey.Escape
    val description =
        if (interrupt) {
            stringResource(R.string.terminal_reply_interrupt_description)
        } else {
            stringResource(R.string.terminal_reply_option, reply.badge)
        }
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onReply(reply)
        },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { contentDescription = description }.testTag("terminal_reply"),
    ) {
        if (interrupt) {
            Row(
                Modifier.height(34.dp).padding(start = 5.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    Modifier
                        .heightIn(min = 24.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), CircleShape)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(reply.badge, style = FlocketMono.label) }
                Text(stringResource(R.string.terminal_reply_interrupt), style = MaterialTheme.typography.labelLarge)
            }
        } else {
            // A menu number: the agent's own menu on screen says what it means.
            Box(Modifier.height(34.dp).widthIn(min = 48.dp), contentAlignment = Alignment.Center) {
                Text(reply.badge, style = FlocketMono.body.copy(fontWeight = FontWeight.SemiBold))
            }
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

/**
 * Another viewer (usually the desktop) took the terminal's size, so it is drawn scaled down. A tap
 * on the terminal also takes the size back; this says why it looks small and offers the same.
 */
@Composable
private fun SizeTakenChip(onFit: () -> Unit) {
    Surface(
        onClick = onFit,
        modifier = Modifier.testTag("terminal_size_taken"),
        shape = FlocketCorners.HostPill,
        color = MaterialTheme.flocketColors.attentionContainer,
        contentColor = MaterialTheme.flocketColors.onAttentionContainer,
        shadowElevation = 2.dp,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.terminal_size_taken), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.FitScreen, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.terminal_action_fit),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/** A neutral pill (dot + word, like the host pill): the link is coming back on its own. */
@Composable
private fun ReconnectingChip() {
    Surface(
        shape = FlocketCorners.HostPill,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.terminal_reconnecting), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** From this bar width the agent's status pill shows beside the title. */
private val WIDE_BAR = 560.dp

private const val CLIPBOARD_PREVIEW_CHARS = 2_000

/** herdr-core's `reason` for a terminal ended by `terminal.kill`. */
private const val REASON_KILLED = "killed"

/**
 * Window-inset sides this pane touches. Beside a visible sidebar the pane's start edge is not the
 * window's, so it must not pad for the start cutout or nav bar (the sidebar already does).
 */
private fun paneInsetSides(layout: PaneLayout): WindowInsetsSides =
    if (layout.multiPane && layout.sidebarVisible) {
        WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End
    } else {
        WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical
    }
