package com.mtajchert.flocket.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtajchert.flocket.core.designsystem.theme.FlocketCorners
import com.mtajchert.flocket.core.designsystem.theme.FlocketMono
import com.mtajchert.flocket.core.designsystem.theme.flocketColors

/** A command in a terminal-style block: terminal background, hairline border, green `❯ ` prompt. */
@Composable
fun TerminalCommandBlock(
    command: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.flocketColors
    Surface(
        modifier = modifier,
        shape = FlocketCorners.Card,
        color = colors.terminalBackground,
        contentColor = colors.terminalForeground,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        SelectionContainer {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.done)) { append("❯ ") }
                    append(command)
                },
                style = FlocketMono.small.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified),
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}
