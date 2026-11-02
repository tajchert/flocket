@file:Suppress("ktlint:standard:max-line-length") // Escape-sequence fixtures read best on one line.

package com.mtajchert.flocket.core.terminal

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.terminal.engine.ScreenSnapshot
import com.mtajchert.flocket.core.terminal.engine.TerminalTheme
import com.mtajchert.flocket.core.terminal.fake.FakeTerminalEngine
import com.mtajchert.flocket.core.terminal.ui.ExtraKeysRow
import com.mtajchert.flocket.core.terminal.ui.TerminalSnapshot
import com.mtajchert.flocket.core.terminal.ui.TerminalThemes

private const val E = "\u001b"

private fun demo(
    cols: Int,
    rows: Int,
    theme: TerminalTheme,
    cursor: String = "",
): ScreenSnapshot {
    val engine = FakeTerminalEngine(cols, rows, theme)
    val s = StringBuilder()
    s.append("$E[1;38;5;75m~/src/flocket$E[0m on $E[35m main$E[0m $E[2m(3 changed)$E[0m\r\n")
    s.append("$E[32m❯$E[0m ls --color\r\n")
    s.append("$E[1;34mapp$E[0m  $E[1;34mcore$E[0m  $E[32mgradlew$E[0m  README.md  $E[4;36mdocs$E[0m\r\n")
    s.append(
        "$E[1mbold$E[0m $E[3mitalic$E[0m $E[4munderline$E[0m $E[9mstrike$E[0m $E[2mfaint$E[0m $E[7minverse$E[0m $E[1;3mboth$E[0m\r\n",
    )
    for (i in 0 until 8) s.append("$E[4${i}m   ")
    s.append("$E[0m\r\n")
    for (i in 0 until 8) s.append("$E[10${i}m   ")
    s.append("$E[0m\r\n")
    for (i in 0 until 16) s.append("$E[48;5;${16 + i * 13}m  ")
    s.append("$E[0m\r\n")
    val w = minOf(cols, 48)
    for (i in 0 until w) {
        val r = 255 * i / w
        s.append("$E[48;2;$r;${120 + i};${255 - r}m ")
    }
    s.append("$E[0m\r\n")
    s.append("wide: 你好世界 한글 | emoji 🎉🚀 | café\r\n")
    s.append("╭─ $E[1mhtop$E[0m ──────┬─────╮ $E[31m●$E[0m $E[33m●$E[0m $E[32m●$E[0m\r\n")
    s.append("│ cpu $E[32m▁▂▃▅▇█$E[0m │ 42% │ $E[90mbright black$E[0m\r\n")
    s.append("┗━━━━━━━━━━━━┷━━━━━┛ ░▒▓█ ▚▞▙▟\r\n")
    s.append("$E[32m❯$E[0m git commit -m \"feat: terminal\"")
    s.append(cursor)
    engine.feed(s.toString().encodeToByteArray())
    return engine.snapshot()
}

@Composable
private fun TerminalScreen(
    dark: Boolean,
    cols: Int,
    rows: Int,
    selection: IntRange? = null,
) {
    val theme = TerminalThemes.forDarkMode(dark)
    FlocketTheme(darkTheme = dark, dynamicColor = false) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            TerminalSnapshot(
                snapshot = demo(cols, rows, theme),
                theme = theme,
                selection = selection,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            ExtraKeysRow(ctrlActive = true, altActive = false, onKey = {})
        }
    }
}

@PreviewTest
@Preview(name = "PhoneLight", widthDp = 412, heightDp = 420)
@Composable
private fun TerminalPhoneLight() = TerminalScreen(dark = false, cols = 50, rows = 16)

@PreviewTest
@Preview(name = "PhoneDark", widthDp = 412, heightDp = 420, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TerminalPhoneDark() = TerminalScreen(dark = true, cols = 50, rows = 16, selection = 2 * 50 + 5..2 * 50 + 8)

// Selection across rows: highlight plus Material teardrop handles in the theme's primary color.
@PreviewTest
@Preview(name = "SelectionLight", widthDp = 412, heightDp = 420)
@Composable
private fun TerminalSelectionLight() =
    TerminalScreen(
        dark = false,
        cols = 50,
        rows = 16,
        selection =
            1 * 50 + 2..3 * 50 + 9,
    )

@PreviewTest
@Preview(name = "TabletLight", widthDp = 1280, heightDp = 480)
@Composable
private fun TerminalTabletLight() = TerminalScreen(dark = false, cols = 160, rows = 20)

@PreviewTest
@Preview(name = "TabletDark", widthDp = 1280, heightDp = 480, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TerminalTabletDark() = TerminalScreen(dark = true, cols = 160, rows = 20)

@Composable
private fun CursorPreview(
    seq: String,
    focused: Boolean,
) {
    val theme = TerminalThemes.Dark
    val engine = FakeTerminalEngine(30, 2, theme)
    engine.feed("$E[32m❯$E[0m vim notes.md$seq".encodeToByteArray())
    TerminalSnapshot(engine.snapshot(), theme, Modifier.fillMaxSize(), focused = focused)
}

@PreviewTest
@Preview(name = "CursorBar", widthDp = 240, heightDp = 48)
@Composable
private fun TerminalCursorBar() = CursorPreview("$E[6 q", focused = true)

@PreviewTest
@Preview(name = "CursorUnderline", widthDp = 240, heightDp = 48)
@Composable
private fun TerminalCursorUnderline() = CursorPreview("$E[4 q", focused = true)

@PreviewTest
@Preview(name = "CursorUnfocused", widthDp = 240, heightDp = 48)
@Composable
private fun TerminalCursorUnfocused() = CursorPreview("$E[2 q", focused = false)

/** An agent's dot spinner, frame by frame: every dot must sit on the same pixels in each frame. */
@PreviewTest
@Preview(name = "BrailleSpinner", widthDp = 240, heightDp = 48)
@Composable
private fun TerminalBrailleSpinner() {
    val theme = TerminalThemes.Dark
    val engine = FakeTerminalEngine(30, 2, theme)
    engine.feed("⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏\r\n$E[36m⣿ ⡇ ⢸ ⠿ ⣀$E[0m Working…$E[?25l".encodeToByteArray())
    TerminalSnapshot(engine.snapshot(), theme, Modifier.fillMaxSize(), focused = false)
}
