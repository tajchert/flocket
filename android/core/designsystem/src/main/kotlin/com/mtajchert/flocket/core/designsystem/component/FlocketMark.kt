package com.mtajchert.flocket.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.designsystem.theme.flocketColors

/**
 * The Flocket app mark: two trailing chevrons, three leading chevrons and the brand orange cursor,
 * on a tile in the app background with a 1dp outline. Colors are fixed brand colors (see
 * [com.mtajchert.flocket.core.designsystem.theme.FlocketColors]); they follow light/dark but never
 * dynamic color. Decorative: it has no content description.
 */
@Composable
fun FlocketMark(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.flocketColors
    val shape = RoundedCornerShape(size * 0.22f)
    Canvas(
        modifier
            .size(size)
            .clip(shape)
            .background(colors.brandTile)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        val u = this.size.minDimension / 100f
        val stroke = Stroke(width = 5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

        fun chevron(
            color: Color,
            vararg p: Float,
        ) {
            val path =
                Path().apply {
                    moveTo(p[0] * u, p[1] * u)
                    lineTo(p[2] * u, p[3] * u)
                    lineTo(p[4] * u, p[5] * u)
                }
            drawPath(path, color, style = stroke)
        }
        chevron(colors.flockTrail, 29f, 17f, 37f, 24f, 29f, 31f)
        chevron(colors.flockTrail, 29f, 69f, 37f, 76f, 29f, 83f)
        chevron(colors.brandTeal, 45f, 30f, 53f, 37f, 45f, 44f)
        chevron(colors.brandTeal, 45f, 56f, 53f, 63f, 45f, 70f)
        chevron(colors.brandTeal, 61f, 43f, 69f, 50f, 61f, 57f)
        drawCursor(colors.brand, u)
    }
}

private fun DrawScope.drawCursor(
    color: Color,
    u: Float,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(73f * u, 53.5f * u),
        size = Size(11f * u, 5f * u),
        cornerRadius = CornerRadius(1.6f * u),
    )
}

@Preview(name = "FlocketMark", showBackground = true)
@Composable
private fun FlocketMarkPreview() {
    FlocketTheme {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FlocketMark(24.dp)
            FlocketMark(72.dp)
        }
    }
}
