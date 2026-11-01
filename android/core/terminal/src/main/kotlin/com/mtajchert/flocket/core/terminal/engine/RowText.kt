package com.mtajchert.flocket.core.terminal.engine

/**
 * Plain-text extraction from cell rows (selection copy): one rule set for every engine that does not
 * have its own formatter, and for frame sources without an engine.
 *
 * - A wide character's spacer cell adds nothing; a grapheme cluster appears once; an empty cell is a space.
 * - Trailing blanks are trimmed at the end of each line; a soft-wrapped row joins the next one without a
 *   newline (and keeps its trailing blanks, which are real content up to the wrap).
 */
internal class RowTextBuilder {
    private val out = StringBuilder()
    private var pending = false

    /**
     * Append cells [c0]..[c1] of the row starting at [base] in the given arrays. [grapheme] returns the full
     * text of a multi-code-point cell (by array index) or null. [wrapped]: the row continues on the next one.
     */
    fun appendRow(
        codepoints: IntArray,
        flags: IntArray,
        base: Int,
        c0: Int,
        c1: Int,
        wrapped: Boolean,
        grapheme: (Int) -> String?,
    ) {
        if (pending) out.append('\n')
        pending = false
        val lineStart = lastLineStart()
        for (c in c0..c1) {
            val i = base + c
            if (i !in codepoints.indices) break
            if (flags[i] and CellFlags.WIDE_SPACER != 0) continue
            val g = grapheme(i)
            val cp = codepoints[i]
            when {
                g != null -> out.append(g)
                cp == 0 -> out.append(' ')
                else -> out.appendCodePoint(cp)
            }
        }
        if (!wrapped) {
            trimEndFrom(lineStart)
            pending = true
        }
    }

    fun build(): String {
        trimEndFrom(lastLineStart())
        return out.toString()
    }

    private fun lastLineStart(): Int = out.lastIndexOf("\n") + 1

    private fun trimEndFrom(start: Int) {
        var end = out.length
        while (end > start && out[end - 1] == ' ') end--
        out.setLength(end)
    }
}

/** Text of cells ([startRow], [startCol])..([endRow], [endCol]) of [snapshot]'s visible rows (no wrap info). */
internal fun ScreenSnapshot.textRange(
    startRow: Int,
    startCol: Int,
    endRow: Int,
    endCol: Int,
): String {
    val b = RowTextBuilder()
    for (r in startRow.coerceAtLeast(0)..endRow.coerceAtMost(rows - 1)) {
        val c0 = if (r == startRow) startCol else 0
        val c1 = if (r == endRow) endCol else cols - 1
        b.appendRow(codepoints, flags, r * cols, c0.coerceIn(0, cols - 1), c1.coerceIn(0, cols - 1), false) {
            graphemes[it]
        }
    }
    return b.build()
}
