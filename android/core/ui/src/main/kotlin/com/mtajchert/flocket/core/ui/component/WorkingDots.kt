package com.mtajchert.flocket.core.ui.component

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.coroutines.coroutineContext

private const val LAP_NANOS = 900_000_000L
private const val FADE_IN = 0.7f
private const val TAIL = 2.8f
private const val DIM = 0.16f

/** The six dots of a 2x3 braille cell in the order the snake visits them: clockwise from the top left. */
private val RING_COL = intArrayOf(0, 1, 1, 1, 0, 0)
private val RING_ROW = intArrayOf(0, 0, 1, 2, 2, 1)

private fun smooth(x: Float): Float = x * x * (3f - 2f * x)

/**
 * The frame clock's time (same base as `System.nanoTime`), ticking every frame while the caller is
 * shown. Read it in the draw phase so a frame only redraws. Fixed at 0 in previews and screenshot tests,
 * and frozen when the system turns animations off. Every indicator reads the same clock, so they all move
 * in step and one that is recreated (a row moved, a list re-sorted) picks up exactly where it was.
 */
@Composable
internal fun rememberAnimationNanos(): LongState {
    val inspection = LocalInspectionMode.current
    val nanos = remember { mutableLongStateOf(if (inspection) 0L else System.nanoTime()) }
    if (!inspection) {
        LaunchedEffect(Unit) {
            if (coroutineContext[MotionDurationScale]?.scaleFactor == 0f) return@LaunchedEffect
            while (true) withInfiniteAnimationFrameNanos { nanos.longValue = it }
        }
    }
    return nanos
}

/**
 * Brightness (0..1) of the six dots at [frameNanos], in [RING_COL]/[RING_ROW] order. The snake's head runs
 * round the ring at a constant speed and every dot's brightness is a continuous function of how far the
 * head is past it: it eases up as the head arrives, fades along the tail, and rests dim. Nothing is
 * keyframed, so there is no seam where a lap starts again.
 */
internal fun workingDotAlphas(frameNanos: Long): FloatArray {
    val head = Math.floorMod(frameNanos, LAP_NANOS).toFloat() / LAP_NANOS * RING_COL.size
    return FloatArray(RING_COL.size) { i ->
        // How far the head is past dot i, shifted by FADE_IN so the dot starts easing up before it arrives.
        val u = (head - i + FADE_IN).mod(RING_COL.size.toFloat())
        val lit =
            when {
                u < FADE_IN -> smooth(u / FADE_IN)
                u < FADE_IN + TAIL -> smooth(1f - (u - FADE_IN) / TAIL)
                else -> 0f
            }
        DIM + (1f - DIM) * lit
    }
}

/**
 * The "agent is working" mark: a 2x3 dot matrix (a braille cell) with a snake running round it, like the
 * spinners agents print, but drawn by Flocket on a continuous clock so it never jumps when it loops.
 * [size] is the dot pitch: dots are [size] * 0.6 wide, so the mark is 2 x 3 pitches.
 */
@Composable
fun WorkingDots(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 5.dp,
) {
    val nanos = rememberAnimationNanos()
    Canvas(
        modifier
            .size(size * 2, size * 3)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
    ) {
        val pitch = size.toPx()
        val radius = pitch * 0.3f
        val alphas = workingDotAlphas(nanos.longValue)
        for (i in alphas.indices) {
            drawCircle(
                color = color,
                radius = radius,
                center = Offset(pitch * (RING_COL[i] + 0.5f), pitch * (RING_ROW[i] + 0.5f)),
                alpha = alphas[i],
            )
        }
    }
}
