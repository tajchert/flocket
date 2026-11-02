package com.mtajchert.flocket.core.ui.component

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos

private const val ROTATION_PERIOD_NANOS = 1_200_000_000L
private const val BREATHE_PERIOD_NANOS = 2_400_000_000L
private const val MIN_SWEEP = 90f
private const val MAX_SWEEP = 270f

/** The spinner arc in `drawArc` terms: [start] in degrees clockwise from 3 o'clock, and its [sweep]. */
@Immutable
internal data class WorkingArc(
    val start: Float,
    val sweep: Float,
)

private fun phase(
    frameNanos: Long,
    periodNanos: Long,
): Float = Math.floorMod(frameNanos, periodNanos).toFloat() / periodNanos

/**
 * The arc at [frameNanos] (the frame clock's time base). It is a continuous function of absolute time with
 * no keyframes to restart: the rotation wraps at exactly 360 degrees and the sweep breathes on a cosine, so
 * there is no seam at any loop boundary, and a spinner that is recreated (row moved, list re-sorted) lands
 * on the same angle as before. Every spinner on screen also turns in sync.
 */
internal fun workingArc(frameNanos: Long): WorkingArc {
    val rotation = phase(frameNanos, ROTATION_PERIOD_NANOS) * 360f
    val breathe = 0.5f - 0.5f * cos(2f * PI.toFloat() * phase(frameNanos, BREATHE_PERIOD_NANOS))
    val sweep = MIN_SWEEP + (MAX_SWEEP - MIN_SWEEP) * breathe
    return WorkingArc(start = rotation - sweep / 2f - 90f, sweep = sweep)
}

/**
 * The "agent is working" spinner: a round-capped arc that turns at a constant speed while its length
 * breathes. Unlike the indeterminate M3 indicator it has no keyframed cycle, so it never jumps when it
 * loops or when it is recomposed into a new place. Static (and deterministic) in previews and screenshot
 * tests, and when the system turns animations off.
 */
@Composable
fun WorkingSpinner(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    strokeWidth: Dp = 2.dp,
) {
    val inspection = LocalInspectionMode.current
    val frameNanos = remember { mutableLongStateOf(if (inspection) 0L else System.nanoTime()) }
    if (!inspection) {
        LaunchedEffect(Unit) {
            if (coroutineContext[MotionDurationScale]?.scaleFactor == 0f) return@LaunchedEffect
            while (true) withInfiniteAnimationFrameNanos { frameNanos.longValue = it }
        }
    }
    Canvas(
        modifier
            .size(size)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
    ) {
        // Read in the draw phase only: each frame redraws the arc without recomposing the row.
        val arc = workingArc(frameNanos.longValue)
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        drawArc(
            color = color,
            startAngle = arc.start,
            sweepAngle = arc.sweep,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}
