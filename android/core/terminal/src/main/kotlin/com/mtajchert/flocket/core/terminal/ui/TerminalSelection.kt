package com.mtajchert.flocket.core.terminal.ui

import com.mtajchert.flocket.core.terminal.engine.CellFlags

/** A cell by row id (`historyBase + screen row`, stable while output arrives) and column. */
internal data class CellPos(
    val row: Long,
    val col: Int,
) : Comparable<CellPos> {
    override fun compareTo(other: CellPos): Int {
        val byRow = row.compareTo(other.row)
        return if (byRow != 0) byRow else col.compareTo(other.col)
    }
}

/**
 * Text selection between two cells (inclusive), anchored in row ids so it survives new output and
 * scrolling (pure, JVM-tested; [TerminalView] draws it and owns the gestures).
 */
internal class TerminalSelection {
    private var a: CellPos? = null
    private var b: CellPos? = null
    private var draggingA = false

    val isActive: Boolean get() = a != null && b != null

    /** First selected cell (null when inactive). */
    val start: CellPos? get() = pick(first = true)

    /** Last selected cell, inclusive (null when inactive). */
    val end: CellPos? get() = pick(first = false)

    private fun pick(first: Boolean): CellPos? {
        val x = a ?: return null
        val y = b ?: return null
        return if ((x <= y) == first) x else y
    }

    fun set(
        from: CellPos,
        to: CellPos,
    ) {
        a = from
        b = to
    }

    fun clear() {
        a = null
        b = null
    }

    /** Start dragging the start ([startHandle]) or end handle. */
    fun beginDrag(startHandle: Boolean) {
        val x = a ?: return
        val y = b ?: return
        draggingA = (x <= y) == startHandle
    }

    /**
     * Move the dragged end to [to]. Handles may cross: the dragged end then becomes the other side,
     * like Android text fields.
     */
    fun dragTo(to: CellPos) {
        if (!isActive) return
        if (draggingA) a = to else b = to
    }

    /** True when the dragged end is currently the selection's start. */
    val draggingStart: Boolean get() = (if (draggingA) a else b) == start

    /** The selection starts in a row that has left the scrollback. */
    fun isEvicted(oldestRow: Long): Boolean = (start?.row ?: Long.MAX_VALUE) < oldestRow

    /** Columns of [row] that are selected, packed by [packCols], or -1 when none. Allocation-free. */
    fun colsIn(
        row: Long,
        cols: Int,
    ): Long {
        val s = start ?: return -1
        val e = end ?: return -1
        if (row < s.row || row > e.row) return -1
        val c0 = if (row == s.row) s.col else 0
        val c1 = if (row == e.row) e.col else cols - 1
        if (c1 < c0) return -1
        return packCols(c0, c1)
    }

    companion object {
        fun packCols(
            c0: Int,
            c1: Int,
        ): Long = (c0.toLong() shl 32) or (c1.toLong() and 0xFFFFFFFFL)

        fun firstCol(packed: Long): Int = (packed shr 32).toInt()

        fun lastCol(packed: Long): Int = packed.toInt()

        private const val WORD_CHARS = "-_./~:@%+=#"

        /**
         * Columns of the word around [col] in the row starting at [base] (non-ASCII counts as a word
         * character, so CJK runs and accented words select whole). A blank cell selects itself; a wide
         * character's spacer selects its character. Packed by [packCols].
         */
        fun wordAt(
            codepoints: IntArray,
            flags: IntArray,
            base: Int,
            cols: Int,
            col: Int,
        ): Long {
            var c = col.coerceIn(0, cols - 1)
            if (c > 0 && flags[base + c] and CellFlags.WIDE_SPACER != 0) c--

            fun isWord(i: Int): Boolean {
                if (flags[base + i] and CellFlags.WIDE_SPACER != 0) return true
                val cp = codepoints[base + i]
                return cp != 0 && cp != ' '.code &&
                    (Character.isLetterOrDigit(cp) || (cp < 0x80 && cp.toChar() in WORD_CHARS) || cp >= 0x80)
            }
            var c0 = c
            var c1 = if (flags[base + c] and CellFlags.WIDE != 0) minOf(c + 1, cols - 1) else c
            if (isWord(c)) {
                while (c0 > 0 && isWord(c0 - 1)) c0--
                while (c1 < cols - 1 && isWord(c1 + 1)) c1++
            }
            return packCols(c0, c1)
        }
    }
}
