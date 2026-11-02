package com.mtajchert.flocket.core.ui

import com.mtajchert.flocket.core.ui.component.workingArc
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingSpinnerTest {
    /** Signed shortest difference b - a on the circle, in (-180, 180]. */
    private fun angleDelta(
        a: Float,
        b: Float,
    ): Float {
        var d = (b - a) % 360f
        if (d <= -180f) d += 360f
        if (d > 180f) d -= 360f
        return d
    }

    @Test
    fun `head and tail move forward in small steps across every loop boundary`() {
        val frameNanos = 16_666_667L
        // Start at an arbitrary large uptime and run for a minute of 60 Hz frames: many wraps of every period.
        var t = 987_654_321_012L
        var previous = workingArc(t)
        repeat(60 * 60) {
            t += frameNanos
            val arc = workingArc(t)
            val head = angleDelta(previous.start + previous.sweep, arc.start + arc.sweep)
            val tail = angleDelta(previous.start, arc.start)
            assertTrue("head jumped by $head at $t", head > 0f && head < 12f)
            assertTrue("tail jumped by $tail at $t", tail > 0f && tail < 12f)
            assertTrue("sweep ${arc.sweep}", arc.sweep in 60f..300f)
            previous = arc
        }
    }

    @Test
    fun `the arc is a pure function of the frame time, so a restarted spinner does not jump`() {
        val t = 123_456_789_000L
        assertTrue(workingArc(t) == workingArc(t))
    }
}
