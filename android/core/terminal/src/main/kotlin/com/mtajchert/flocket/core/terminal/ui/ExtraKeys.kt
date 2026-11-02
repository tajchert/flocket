package com.mtajchert.flocket.core.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtajchert.flocket.core.designsystem.theme.FlocketMono
import com.mtajchert.flocket.core.designsystem.theme.flocketColors
import com.mtajchert.flocket.core.terminal.R
import com.mtajchert.flocket.core.terminal.controller.TerminalController
import com.mtajchert.flocket.core.terminal.engine.Key
import com.mtajchert.flocket.core.terminal.engine.KeyInput

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

    /** Drawn apart, as the filled key at the end of the row. */
    Enter("⏎", "Enter"),
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
            Enter -> KeyInput(Key.Enter)
        }

    /** Arrow icon and its rotation; the arrows never mirror in RTL (they send the same key). */
    internal val arrow: Pair<ImageVector, Float>?
        get() =
            when (this) {
                Left -> Icons.Filled.KeyboardArrowUp to -90f
                Up -> Icons.Filled.KeyboardArrowUp to 0f
                Down -> Icons.Filled.KeyboardArrowDown to 0f
                Right -> Icons.Filled.KeyboardArrowUp to 90f
                else -> null
            }

    internal companion object {
        /** The scrolling part of the row; [Enter] is pinned at the end. */
        val scrolling: List<ExtraKey> = entries.filter { it != Enter }

        /** A small gap before these groups (arrows, symbols), as in the design. */
        val groupStarts: Set<ExtraKey> = setOf(Left, Home, Pipe)
    }
}

/**
 * The extra-keys row shown above the IME: Esc, Tab, sticky Ctrl/Alt (apply to the next key from
 * any source, including the soft keyboard), arrows, Home/End/PgUp/PgDn and hard-to-type symbols,
 * then the soft-keyboard toggle (when [onToggleKeyboard] is set) and Enter.
 */
@Composable
fun TerminalExtraKeys(
    controller: TerminalController,
    modifier: Modifier = Modifier,
    keyboardVisible: Boolean = false,
    onToggleKeyboard: (() -> Unit)? = null,
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
        keyboardVisible = keyboardVisible,
        onToggleKeyboard = onToggleKeyboard,
    )
}

/** Key caps are 36dp tall; the touch target is the whole 48dp bar. */
private val CapHeight = 36.dp
private val BarPadding = 6.dp
private val CapShape = RoundedCornerShape(8.dp)

/** Stateless extra-keys row. */
@Composable
fun ExtraKeysRow(
    ctrlActive: Boolean,
    altActive: Boolean,
    onKey: (ExtraKey) -> Unit,
    modifier: Modifier = Modifier,
    keyboardVisible: Boolean = false,
    onToggleKeyboard: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val press: (ExtraKey) -> Unit = { key ->
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onKey(key)
    }
    val colors = MaterialTheme.colorScheme
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.surfaceContainer)
                .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExtraKey.scrolling.forEach { key ->
                val sticky = key == ExtraKey.Ctrl || key == ExtraKey.Alt
                val active = (key == ExtraKey.Ctrl && ctrlActive) || (key == ExtraKey.Alt && altActive)
                if (key in ExtraKey.groupStarts) Box(Modifier.width(4.dp))
                KeyCap(
                    description = key.description,
                    selected = if (sticky) active else null,
                    container = if (active) colors.primary else MaterialTheme.flocketColors.key,
                    content = if (active) colors.onPrimary else colors.onSurface,
                    onClick = { press(key) },
                ) {
                    val arrow = key.arrow
                    if (arrow != null) {
                        Icon(arrow.first, null, Modifier.size(22.dp).rotate(arrow.second))
                    } else {
                        Text(text = key.label, style = KeyCapText)
                    }
                }
            }
        }
        if (onToggleKeyboard != null) {
            KeyCap(
                description =
                    stringResource(
                        if (keyboardVisible) R.string.terminal_keyboard_hide else R.string.terminal_keyboard_show,
                    ),
                selected = null,
                container = colors.surfaceContainerHigh,
                content = colors.onSurface,
                minWidth = 40.dp,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggleKeyboard()
                },
            ) {
                Icon(
                    if (keyboardVisible) Icons.Outlined.KeyboardHide else Icons.Outlined.Keyboard,
                    null,
                    Modifier.size(22.dp),
                )
            }
        }
        KeyCap(
            description = ExtraKey.Enter.description,
            selected = null,
            container = colors.primary,
            content = colors.onPrimary,
            minWidth = 44.dp,
            onClick = { press(ExtraKey.Enter) },
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardReturn, null, Modifier.size(20.dp))
        }
    }
}

private val KeyCapText = FlocketMono.small.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium)

/** One key: a 36dp cap inside a 48dp-tall touch target that also covers the gaps around it. */
@Composable
private fun KeyCap(
    description: String,
    selected: Boolean?,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    minWidth: Dp = 38.dp,
    label: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = description
                    if (selected != null) this.selected = selected
                }.padding(horizontal = 2.5.dp, vertical = BarPadding),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .height(CapHeight)
                    .widthIn(min = minWidth)
                    .clip(CapShape)
                    .background(container)
                    .padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides content) { label() }
        }
    }
}
