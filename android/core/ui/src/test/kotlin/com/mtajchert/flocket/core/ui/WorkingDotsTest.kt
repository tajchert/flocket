package com.mtajchert.flocket.core.ui

import com.mtajchert.flocket.core.ui.component.workingDotAlphas
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WorkingDotsTest {
    @Test
    fun `no dot ever jumps between two frames, across every loop`() {
        val frame = 16_666_667L
        var t = 987_654_321_012L
        var previous = workingDotAlphas(t)
        repeat(60 * 60) {
            t += frame
            val now = workingDotAlphas(t)
            for (i in now.indices) {
                val step = abs(now[i] - previous[i])
                assertTrue("dot $i stepped $step at $t", step < 0.25f)
            }
            previous = now
        }
    }

    @Test
    fun `a snake runs round the ring with one bright head, a fading tail and the rest dim`() {
        // Sample a lap in fine steps: at every moment at least one dot is lit and at least two are dim.
        for (k in 0 until 600) {
            val a = workingDotAlphas(123_000_000_000L + k * 1_500_000L)
            assertTrue(a.max() > 0.7f)
            assertTrue(a.count { it < 0.3f } >= 2)
            assertTrue(a.all { it in 0f..1f })
        }
    }

    @Test
    fun `the dots are a pure function of the frame time, so a restarted indicator does not jump`() {
        assertArrayEquals(workingDotAlphas(42_000_000_000L), workingDotAlphas(42_000_000_000L), 0f)
    }
}
