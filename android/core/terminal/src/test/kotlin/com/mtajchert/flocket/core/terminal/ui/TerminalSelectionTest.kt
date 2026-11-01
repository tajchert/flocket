package com.mtajchert.flocket.core.terminal.ui

import com.mtajchert.flocket.core.terminal.engine.CellFlags
import com.mtajchert.flocket.core.terminal.fake.FakeTerminalEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSelectionTest {
    private fun cols(packed: Long) = TerminalSelection.firstCol(packed)..TerminalSelection.lastCol(packed)

    @Test
    fun `start and end are ordered whatever the drag direction`() {
        val s = TerminalSelection()
        s.set(CellPos(10, 5), CellPos(8, 2))
        assertEquals(CellPos(8, 2), s.start)
        assertEquals(CellPos(10, 5), s.end)
    }

    @Test
    fun `dragging a handle past the other one swaps the ends`() {
        val s = TerminalSelection()
        s.set(CellPos(5, 3), CellPos(5, 6))
        s.beginDrag(startHandle = true)
        s.dragTo(CellPos(7, 0))
        assertEquals(CellPos(5, 6), s.start)
        assertEquals(CellPos(7, 0), s.end)
        assertFalse(s.draggingStart)
    }

    @Test
    fun `columns per row cover full middle rows`() {
        val s = TerminalSelection()
        s.set(CellPos(3, 4), CellPos(5, 1))
        assertEquals(-1L, s.colsIn(2, 10))
        assertEquals(4..9, cols(s.colsIn(3, 10)))
        assertEquals(0..9, cols(s.colsIn(4, 10)))
        assertEquals(0..1, cols(s.colsIn(5, 10)))
    }

    @Test
    fun `a selection whose start row was evicted is reported`() {
        val s = TerminalSelection()
        s.set(CellPos(3, 0), CellPos(9, 0))
        assertFalse(s.isEvicted(3))
        assertTrue(s.isEvicted(4))
    }

    @Test
    fun `word selection stops at blanks and keeps path characters`() {
        val e = FakeTerminalEngine(30, 1)
        e.feed("ls ~/src/flocket-app done".encodeToByteArray())
        val snap = e.snapshot()
        assertEquals(3..19, cols(TerminalSelection.wordAt(snap.codepoints, snap.flags, 0, 30, 10)))
        assertEquals(2..2, cols(TerminalSelection.wordAt(snap.codepoints, snap.flags, 0, 30, 2)))
    }

    @Test
    fun `a blank cell past the last non-blank character selects nothing`() {
        val e = FakeTerminalEngine(30, 2)
        e.feed("ls  done".encodeToByteArray())
        val snap = e.snapshot()
        assertEquals(TerminalSelection.NONE, TerminalSelection.wordAt(snap.codepoints, snap.flags, 0, 30, 20))
        assertEquals(TerminalSelection.NONE, TerminalSelection.wordAt(snap.codepoints, snap.flags, 0, 30, 8))
        // A blank between words still selects itself; an empty row selects nothing.
        assertEquals(2..2, cols(TerminalSelection.wordAt(snap.codepoints, snap.flags, 0, 30, 2)))
        assertEquals(TerminalSelection.NONE, TerminalSelection.wordAt(snap.codepoints, snap.flags, 30, 30, 3))
    }

    @Test
    fun `word selection on a wide spacer includes the whole character`() {
        val e = FakeTerminalEngine(10, 1)
        e.feed("a 你好 b".encodeToByteArray())
        val snap = e.snapshot()
        assertTrue(snap.flags[3] and CellFlags.WIDE_SPACER != 0)
        assertEquals(2..5, cols(TerminalSelection.wordAt(snap.codepoints, snap.flags, 0, 10, 3)))
    }

    @Test
    fun `moving a handle by a word lands on word edges in the row`() {
        val e = FakeTerminalEngine(20, 1)
        e.feed("one two  three".encodeToByteArray())
        val cp = e.snapshot().codepoints
        assertEquals(4, TerminalSelection.nextWordEdge(cp, 0, 20, 0, forward = true, startHandle = true))
        assertEquals(6, TerminalSelection.nextWordEdge(cp, 0, 20, 2, forward = true, startHandle = false))
        assertEquals(2, TerminalSelection.nextWordEdge(cp, 0, 20, 6, forward = false, startHandle = false))
        assertEquals(13, TerminalSelection.nextWordEdge(cp, 0, 20, 13, forward = true, startHandle = false))
    }
}
