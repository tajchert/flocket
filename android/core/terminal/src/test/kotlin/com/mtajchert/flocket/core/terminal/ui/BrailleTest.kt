package com.mtajchert.flocket.core.terminal.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrailleTest {
    private fun dots(
        cp: Int,
        w: Int,
        h: Int,
    ): List<List<Int>> = BoxDrawing.brailleDots(cp, w, h)!!.toList().chunked(4)

    @Test
    fun `braille is drawn as shapes, everything around it is not`() {
        assertTrue(BoxDrawing.handles(0x2800) && BoxDrawing.handles(0x28FF))
        assertFalse(BoxDrawing.handles(0x27FF) || BoxDrawing.handles(0x2900))
        assertNull(BoxDrawing.brailleDots('A'.code, 8, 22))
    }

    @Test
    fun `every pattern uses all eight bits at fixed dot positions`() {
        // Bit order is 1,2,3,7 down the left column and 4,5,6,8 down the right (Unicode braille).
        val positions = listOf(1 to 2, 1 to 7, 1 to 13, 5 to 2, 5 to 7, 5 to 13, 1 to 18, 5 to 18)
        for (pattern in 0 until 256) {
            val expected =
                positions
                    .filterIndexed { bit, _ -> pattern and (1 shl bit) != 0 }
                    .map { (x, y) -> listOf(x, y, x + 2, y + 2) }
            assertEquals("pattern $pattern", expected, dots(0x2800 + pattern, 8, 22))
        }
    }

    @Test
    fun `a spinner's frames keep every dot in place, so it never jumps as it loops`() {
        // The "dots" spinner agents print: ⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏. Each dot sits at one spot in every frame.
        val frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
        for ((w, h) in listOf(8 to 22, 13 to 27, 19 to 41)) {
            val all = dots(0x28FF, w, h).toSet()
            for (frame in frames) {
                assertTrue("$frame at ${w}x$h", all.containsAll(dots(frame.code, w, h)))
            }
        }
    }

    @Test
    fun `dots stay square, inside the cell and evenly spaced at every cell size`() {
        for (w in 0 until 80) {
            for (h in 0 until 160) {
                for ((x0, y0, x1, y1) in dots(0x28FF, w, h)) {
                    assertTrue(x0 in 0..x1 && x1 <= w)
                    assertTrue(y0 in 0..y1 && y1 <= h)
                    assertEquals(x1 - x0, y1 - y0)
                }
                val d = dots(0x2847, w, h) // ⡇: the left column, top to bottom
                val gaps = listOf(d[1][1] - d[0][1], d[2][1] - d[1][1], d[3][1] - d[2][1], h + d[0][1] - d[3][1])
                assertTrue("${w}x$h: $gaps", gaps.max() - gaps.min() <= 1)
            }
        }
    }
}
