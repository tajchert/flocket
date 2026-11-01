package com.mtajchert.flocket.core.terminal.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import com.mtajchert.flocket.core.terminal.controller.StaticFrameSource
import com.mtajchert.flocket.core.terminal.controller.TerminalController
import com.mtajchert.flocket.core.terminal.engine.Key
import com.mtajchert.flocket.core.terminal.engine.KeyInput
import com.mtajchert.flocket.core.terminal.engine.ScreenSnapshot
import com.mtajchert.flocket.core.terminal.engine.TerminalEvent
import com.mtajchert.flocket.core.terminal.engine.TerminalTheme

/**
 * The interactive terminal. [controller] is owned by the caller (ViewModel / holder), so this
 * composable can be disposed and recreated (rotation, fold, navigation) without touching the
 * engine or the stream. Place it with `Modifier.imePadding()` (or inside a parent that applies
 * it) so the soft keyboard shrinks it; Owner mode resizes after the IME animation settles.
 *
 * [onClipboardWriteRequest]: a program asked to set the clipboard (OSC 52). Ask the user before
 * honoring it. [bellHaptics]: vibrate on the terminal bell. [onFontSizeChange]: pinch-zoom result, persist it and pass it back as [fontSizeSp].
 */
@Composable
fun TerminalSurface(
    controller: TerminalController,
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = TerminalView.DEFAULT_FONT_SP,
    onFontSizeChange: (Float) -> Unit = {},
    onClipboardWriteRequest: (String) -> Unit = {},
    bellHaptics: Boolean = true,
    reconnectingLabel: String? = "Reconnecting…",
    state: TerminalSurfaceState = remember { TerminalSurfaceState() },
) {
    val connected by controller.connected.collectAsState()
    val haptics = LocalHapticFeedback.current
    val fontChange by rememberUpdatedState(onFontSizeChange)
    val clipboardRequest by rememberUpdatedState(onClipboardWriteRequest)
    val bellEnabled by rememberUpdatedState(bellHaptics)
    val activity = LocalContext.current.findActivity()
    LifecycleStartEffect(controller) {
        controller.setVisible(true)
        // A rotation or fold stops and recreates the activity; that is not leaving the screen, so
        // the next start must not take the size back from another viewer (ADR-0007).
        onStopOrDispose {
            controller.setVisible(
                false,
                configurationChange = activity?.isChangingConfigurations == true,
            )
        }
    }
    LaunchedEffect(controller) {
        controller.events.collect { event ->
            when (event) {
                TerminalEvent.Bell -> if (bellEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                is TerminalEvent.ClipboardWrite -> clipboardRequest(event.text)
                else -> Unit
            }
        }
    }
    LaunchedEffect(controller, theme) { controller.setTheme(theme) }
    Box(modifier) {
        TerminalAndroidView(controller, theme, fontSizeSp, state) { fontChange(it) }
        if (!connected && reconnectingLabel != null) {
            // The retained screen keeps rendering underneath; the core sends a fresh paint on reattach.
            Text(
                text = reconnectingLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun TerminalAndroidView(
    controller: TerminalController,
    theme: TerminalTheme,
    fontSizeSp: Float,
    state: TerminalSurfaceState,
    onFontSizeChange: (Float) -> Unit,
) {
    val fontChange by rememberUpdatedState(onFontSizeChange)
    val selectionColor = MaterialTheme.colorScheme.primary.toArgb()
    DisposableEffect(state) { onDispose { state.view = null } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            TerminalView(context).apply {
                this.theme = theme
                this.fontSizeSp = fontSizeSp
                bind(controller)
                state.view = this
            }
        },
        update = { view ->
            view.theme = theme
            view.fontSizeSp = fontSizeSp
            view.selectionColor = selectionColor
            view.onFontSizeChange = { fontChange(it) }
        },
        onReset = { view ->
            view.bind(controller)
            state.view = view
        },
    )
}

/**
 * Imperative handle on the [TerminalSurface]'s view for screen actions (keyboard toggle, focusing
 * the pane for a hardware keyboard). Calls are no-ops while the surface is not composed.
 */
@Stable
class TerminalSurfaceState {
    internal var view: TerminalView? = null

    /** Focus the terminal (hardware keyboard input goes to it) without showing the soft keyboard. */
    fun requestFocus(): Boolean = view?.requestFocus() ?: false

    fun showKeyboard() {
        view?.showKeyboard()
    }

    fun hideKeyboard() {
        view?.hideKeyboard()
    }
}

/** Renders a fixed [snapshot] with the real renderer (previews and screenshot tests). */
@Composable
fun TerminalSnapshot(
    snapshot: ScreenSnapshot,
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = TerminalView.DEFAULT_FONT_SP,
    focused: Boolean = true,
    selection: IntRange? = null,
) {
    val source = remember(snapshot) { StaticFrameSource(snapshot) }
    val selectionColor = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context -> TerminalView(context) },
        update = { view ->
            view.theme = theme
            view.fontSizeSp = fontSizeSp
            view.forceFocusedLook = focused
            view.selectionColor = selectionColor
            view.bindSource(source)
            if (selection != null) view.select(selection.first, selection.last) else view.clearSelection()
        },
    )
}

/** One key of the extra-keys row. */
enum class ExtraKey(
    val label: String,
    val description: String,
) {
    Esc("Esc", "Escape"),
    Tab("Tab", "Tab"),
    Ctrl("Ctrl", "Control (sticky)"),
    Alt("Alt", "Alt (sticky)"),
    Left("←", "Left"),
    Up("↑", "Up"),
    Down("↓", "Down"),
    Right("→", "Right"),
    Home("Home", "Home"),
    End("End", "End"),
    PgUp("PgUp", "Page up"),
    PgDn("PgDn", "Page down"),
    Pipe("|", "Pipe"),
    Tilde("~", "Tilde"),
    Slash("/", "Slash"),
    Dash("-", "Dash"),
    ;

    /** The key press this sends (null for the sticky modifiers). */
    fun toKeyInput(): KeyInput? =
        when (this) {
            Esc -> KeyInput(Key.Escape)
            Tab -> KeyInput(Key.Tab)
            Ctrl, Alt -> null
            Left -> KeyInput(Key.Left)
            Up -> KeyInput(Key.Up)
            Down -> KeyInput(Key.Down)
            Right -> KeyInput(Key.Right)
            Home -> KeyInput(Key.Home)
            End -> KeyInput(Key.End)
            PgUp -> KeyInput(Key.PageUp)
            PgDn -> KeyInput(Key.PageDown)
            Pipe -> KeyInput(Key.Character, "|")
            Tilde -> KeyInput(Key.Character, "~")
            Slash -> KeyInput(Key.Character, "/")
            Dash -> KeyInput(Key.Character, "-")
        }
}

/**
 * The extra-keys row shown above the IME: Esc, Tab, sticky Ctrl/Alt (apply to the next key from
 * any source, including the soft keyboard), arrows, Home/End/PgUp/PgDn and hard-to-type symbols.
 */
@Composable
fun TerminalExtraKeys(
    controller: TerminalController,
    modifier: Modifier = Modifier,
) {
    val ctrl by controller.stickyCtrl.collectAsState()
    val alt by controller.stickyAlt.collectAsState()
    ExtraKeysRow(
        ctrlActive = ctrl,
        altActive = alt,
        onKey = { key ->
            when (key) {
                ExtraKey.Ctrl -> controller.toggleStickyCtrl()
                ExtraKey.Alt -> controller.toggleStickyAlt()
                else -> key.toKeyInput()?.let(controller::sendKey)
            }
        },
        modifier = modifier,
    )
}

/** Stateless extra-keys row. */
@Composable
fun ExtraKeysRow(
    ctrlActive: Boolean,
    altActive: Boolean,
    onKey: (ExtraKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ExtraKey.entries.forEach { key ->
            val active = (key == ExtraKey.Ctrl && ctrlActive) || (key == ExtraKey.Alt && altActive)
            val colors = MaterialTheme.colorScheme
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .heightIn(min = 40.dp)
                        .widthIn(min = 44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) colors.primary else colors.surfaceContainerHighest)
                        .clickable(role = Role.Button) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onKey(key)
                        }.semantics {
                            contentDescription = key.description
                            if (key == ExtraKey.Ctrl || key == ExtraKey.Alt) selected = active
                        }.padding(horizontal = 10.dp),
            ) {
                Text(
                    text = key.label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = if (active) colors.onPrimary else colors.onSurface,
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
