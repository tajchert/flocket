package com.mtajchert.flocket.core.ui.terminalsettings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.designsystem.theme.FlocketCorners
import com.mtajchert.flocket.core.model.TerminalColorScheme
import com.mtajchert.flocket.core.terminal.engine.ScreenSnapshot
import com.mtajchert.flocket.core.terminal.fake.FakeTerminalEngine
import com.mtajchert.flocket.core.terminal.ui.TerminalSnapshot
import com.mtajchert.flocket.core.terminal.ui.TerminalThemes
import com.mtajchert.flocket.core.ui.R

private const val E = "\u001b"
private const val PREVIEW_ROWS = 6
private const val CELL_WIDTH_EM = 0.6f
private const val LINE_HEIGHT_EM = 1.45f

private val SAMPLE =
    "$E[1;34m~/code/flocket$E[0m on $E[35mmain$E[0m\r\n" +
        "$E[32m❯$E[0m ls\r\n" +
        "$E[34mapp$E[0m  $E[34mcore$E[0m  $E[32mgradlew$E[0m  README.md\r\n" +
        "$E[31m✘ 2 failed$E[0m  $E[33m⚠ 1 skipped$E[0m  $E[36m✔ 40 passed$E[0m\r\n" +
        "$E[2mbright:$E[0m $E[91m■$E[92m■$E[93m■$E[94m■$E[95m■$E[96m■$E[0m\r\n" +
        "$E[32m❯$E[0m "

/** A short sample session drawn with the real renderer at [fontSizeSp] in the chosen [scheme]. */
@Composable
fun TerminalPreview(
    fontSizeSp: Float,
    scheme: TerminalColorScheme,
    modifier: Modifier = Modifier,
) {
    val appDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val theme = remember(scheme, appDark) { TerminalThemes.forScheme(scheme, appDark) }
    val description = stringResource(R.string.ts_preview_description)
    val density = LocalDensity.current
    val emDp = fontSizeSp * density.fontScale
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height((PREVIEW_ROWS * LINE_HEIGHT_EM * emDp).dp + 16.dp)
            .clip(FlocketCorners.Card)
            .background(Color(theme.background))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, FlocketCorners.Card)
            .semantics { contentDescription = description },
    ) {
        val cols = ((maxWidth.value - 16f) / (CELL_WIDTH_EM * emDp)).toInt().coerceIn(16, 160)
        val snapshot: ScreenSnapshot =
            remember(cols, theme) {
                FakeTerminalEngine(cols, PREVIEW_ROWS, theme).run {
                    feed(SAMPLE.encodeToByteArray())
                    snapshot()
                }
            }
        TerminalSnapshot(
            snapshot = snapshot,
            theme = theme,
            fontSizeSp = fontSizeSp,
            focused = false,
            modifier = Modifier.fillMaxSize().padding(8.dp),
        )
    }
}
