package com.mtajchert.flocket.core.terminal.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothScrollTest {
    // 100 rows of scrollback, nothing evicted: the bottom viewport's top row has id 100.
    private fun live() = SmoothScroll().apply { onFrame(0, 100, 0) }

    @Test
    fun `a fraction of a row shifts the view without asking the engine`() {
        val s = live()
        s.interacting = true
        assertNull(s.move(-0.25))
        assertFalse(s.live)
        assertEquals(0.25f, s.shiftRows(), 1e-6f)
    }

    @Test
    fun `crossing a row boundary requests the whole row above`() {
        val s = live()
        s.interacting = true
        assertEquals(99L, s.move(-1.5))
        // Engine has not moved yet: never shift by more than the one row we have above.
        assertEquals(1f, s.shiftRows(), 1e-6f)
        s.onFrame(0, 100, 1)
        assertEquals(0.5f, s.shiftRows(), 1e-6f)
        assertNull(s.move(-0.25)) // same row, already requested
    }

    @Test
    fun `scrolling back to the bottom goes live`() {
        val s = live()
        s.interacting = true
        s.move(-3.0)
        s.onFrame(0, 100, 3)
        assertEquals(100L, s.move(10.0))
        assertTrue(s.live)
        assertEquals(0f, s.shiftRows())
    }

    @Test
    fun `scrolled up the position stays on its content while output arrives`() {
        val s = live()
        s.interacting = true
        s.move(-10.5)
        s.onFrame(0, 100, 10)
        s.interacting = false
        // 5 new lines: the engine keeps the viewport on the same rows, so the offset grows.
        s.onFrame(0, 105, 15)
        assertFalse(s.live)
        assertEquals(89.5, s.top, 1e-9)
        // At the scrollback cap rows are evicted: ids stay, historyBase moves.
        s.onFrame(3, 102, 15)
        assertEquals(89.5, s.top, 1e-9)
    }

    @Test
    fun `input snapping the engine to the bottom makes the view live again`() {
        val s = live()
        s.interacting = true
        s.move(-10.0)
        s.onFrame(0, 100, 10)
        s.interacting = false
        s.onFrame(0, 100, 0)
        assertTrue(s.live)
        assertEquals(100.0, s.top, 1e-9)
    }

    @Test
    fun `a fraction near the bottom survives idle frames but not new output`() {
        val s = live()
        s.interacting = true
        s.move(-0.4)
        s.interacting = false
        s.onFrame(0, 100, 0)
        assertFalse(s.live)
        s.onFrame(0, 101, 0)
        assertTrue(s.live)
    }

    @Test
    fun `nothing scrolls without scrollback (alternate screen)`() {
        val s = SmoothScroll().apply { onFrame(0, 0, 0) }
        assertFalse(s.canScroll)
        assertNull(s.move(-5.0))
        assertTrue(s.live)
    }

    @Test
    fun `scrolling stops at the oldest row`() {
        val s = live()
        s.interacting = true
        assertEquals(0L, s.move(-500.0))
        assertTrue(s.atEdge)
        assertEquals(0.0, s.top, 1e-9)
    }
}
