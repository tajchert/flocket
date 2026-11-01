package com.mtajchert.flocket.core.terminal.fake

import com.mtajchert.flocket.core.terminal.engine.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeScrollbackTest {
    private fun engine(max: Int = 100) = FakeTerminalEngine(cols = 10, rows = 3, scrollbackLines = max)

    private fun FakeTerminalEngine.lines(n: Int) = feed((1..n).joinToString("\r\n") { "line$it" }.encodeToByteArray())

    private fun ScreenSnapshot.line(row: Int) =
        buildString {
            for (c in 0 until cols) codepoints[row * cols + c].let { append(if (it == 0) ' ' else it.toChar()) }
        }.trimEnd()

    @Test
    fun `rows scrolled off the top become scrollback`() {
        val e = engine()
        e.lines(5)
        val s = e.snapshot()
        assertEquals(2, s.scrollbackRows)
        assertEquals(0, s.viewportOffset)
        assertEquals("line3", s.line(0))
    }

    @Test
    fun `readRow reads scrollback and screen rows without moving the viewport`() {
        val e = engine()
        e.lines(5)
        val row = ScreenSnapshot(1, 1)
        assertTrue(e.readRow(0, row))
        assertEquals("line1", row.line(0))
        assertTrue(e.readRow(4, row))
        assertEquals("line5", row.line(0))
        assertFalse(e.readRow(5, row))
        assertEquals(0, e.snapshot().viewportOffset)
    }

    @Test
    fun `a scrolled viewport stays on its content while output arrives`() {
        val e = engine()
        e.lines(5)
        e.scrollViewport(-2)
        assertEquals("line1", e.snapshot().line(0))
        e.feed("\r\nline6\r\nline7".encodeToByteArray())
        val s = e.snapshot()
        assertEquals("line1", s.line(0))
        assertEquals(4, s.viewportOffset)
    }

    @Test
    fun `evicted rows advance historyBase so row ids stay stable`() {
        val e = engine(max = 2)
        e.lines(5)
        val before = e.snapshot().let { it.historyBase + it.scrollbackRows } // id of the screen's top row
        e.feed("\r\nline6".encodeToByteArray())
        val s = e.snapshot()
        assertEquals(1L, s.historyBase)
        assertEquals(2, s.scrollbackRows)
        // line3 kept its id: it is now one row higher in the buffer.
        val row = ScreenSnapshot(1, 1)
        assertTrue(e.readRow((before - s.historyBase).toInt(), row))
        assertEquals("line3", row.line(0))
    }

    @Test
    fun `textRange joins soft-wrapped rows and trims line ends`() {
        val e = engine()
        e.feed("abcdefghijklm\r\nx  \r\nlast".encodeToByteArray()) // first line wraps after 10 cells
        val s = e.snapshot()
        val first = 0
        val lastRow = s.scrollbackRows + s.rows - 1
        assertEquals("abcdefghijklm\nx\nlast", e.textRange(first, 0, lastRow, 9))
        assertEquals("cdefghijk", e.textRange(first, 2, first + 1, 0).take(9))
    }

    @Test
    fun `textRange keeps wide characters and graphemes whole`() {
        val e = engine()
        e.feed("a你b é x".encodeToByteArray())
        assertEquals("a你b e\u0301 x", e.textRange(0, 0, 0, 9))
    }
}
