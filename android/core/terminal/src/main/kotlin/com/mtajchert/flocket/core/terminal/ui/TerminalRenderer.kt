package com.mtajchert.flocket.core.terminal.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.mtajchert.flocket.core.terminal.R
import com.mtajchert.flocket.core.terminal.engine.CellFlags
import com.mtajchert.flocket.core.terminal.engine.ScreenSnapshot
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** The four JetBrains Mono faces bundled with `core:terminal` (SIL OFL-1.1). */
class TerminalTypefaces(
    val regular: Typeface,
    val bold: Typeface,
    val italic: Typeface,
    val boldItalic: Typeface,
) {
    companion object {
        @Volatile private var cached: TerminalTypefaces? = null

        fun get(context: Context): TerminalTypefaces =
            cached ?: run {
                val res = context.applicationContext?.resources ?: context.resources

                fun load(
                    id: Int,
                    fallbackStyle: Int,
                ): Typeface =
                    runCatching { res.getFont(id) }.getOrNull() ?: Typeface.create(Typeface.MONOSPACE, fallbackStyle)
                TerminalTypefaces(
                    regular = load(R.font.jetbrains_mono_regular, Typeface.NORMAL),
                    bold = load(R.font.jetbrains_mono_bold, Typeface.BOLD),
                    italic = load(R.font.jetbrains_mono_italic, Typeface.ITALIC),
                    boldItalic = load(R.font.jetbrains_mono_bold_italic, Typeface.BOLD_ITALIC),
                ).also { cached = it }
            }
    }
}

/**
 * Draws [ScreenSnapshot] rows onto a [Canvas] in a monospace grid.
 *
 * Text is drawn in runs (one `drawText` per span of cells sharing color and style) whenever every
 * glyph in the span has exactly the cell advance in JetBrains Mono, so runs stay grid-aligned.
 * Anything else (wide CJK, emoji, graphemes, glyphs from a system fallback font with another
 * advance) is drawn per cell, centered in its 1 or 2 cells and squeezed if it is wider.
 * Background colors are merged into one rect per span. Allocation-free per row in steady state
 * (the per-codepoint "fits the grid" cache fills once per new BMP code point).
 */
class TerminalRenderer(
    private val typefaces: TerminalTypefaces,
) {
    private val textPaints =
        Array(4) { i ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface =
                    when (i) {
                        STYLE_BOLD -> typefaces.bold
                        STYLE_ITALIC -> typefaces.italic
                        STYLE_BOLD_ITALIC -> typefaces.boldItalic
                        else -> typefaces.regular
                    }
            }
        }
    private val bgPaint = Paint().apply { isAntiAlias = false }
    private val linePaint = Paint().apply { isAntiAlias = false }
    private var chars = CharArray(256)
    private val one = CharArray(2)

    /** 0 = unknown, 1 = advance equals the cell width (run-safe), 2 = draw per cell. */
    private val gridSafe = ByteArray(0x10000)

    var textSizePx: Float = 0f
        private set
    var cellWidth: Float = 1f
        private set
    var cellHeight: Int = 1
        private set
    var baseline: Float = 0f
        private set
    private var underlineY = 0f
    private var strikeY = 0f
    private var lineThickness = 1f

    fun setTextSize(px: Float) {
        if (px == textSizePx) return
        textSizePx = px
        for (p in textPaints) p.textSize = px
        val p = textPaints[0]
        cellWidth = p.measureText("M")
        val fm = p.fontMetrics
        cellHeight = ceil(fm.descent - fm.ascent).toInt().coerceAtLeast(1)
        // Center the font's ascent+descent box in the (integer) cell.
        val extra = cellHeight - (fm.descent - fm.ascent)
        baseline = -fm.ascent + extra / 2f
        lineThickness = max(1f, px / 14f)
        underlineY = baseline + max(1f, fm.descent / 3f)
        strikeY = baseline + fm.ascent * 0.3f
        gridSafe.fill(0)
    }

    /**
     * Draw row [row] of [snap] with its top-left at (0, 0) of [canvas]. Cells whose background is
     * [defaultBg] are not filled (the caller paints the default background once).
     * [hasGrapheme] marks cells with an entry in `snap.graphemes` (avoids boxing lookups).
     */
    fun drawRow(
        canvas: Canvas,
        snap: ScreenSnapshot,
        row: Int,
        defaultBg: Int,
        hasGrapheme: BooleanArray?,
    ) {
        val cols = snap.cols
        val base = row * cols
        val bg = snap.background
        val fg = snap.foreground
        val flags = snap.flags
        val cps = snap.codepoints
        val cw = cellWidth
        val ch = cellHeight.toFloat()

        // 1. Backgrounds, merged per span of equal color.
        var c = 0
        while (c < cols) {
            val color = bg[base + c]
            if (color == defaultBg) {
                c++
                continue
            }
            val start = c
            while (c < cols && bg[base + c] == color) c++
            bgPaint.color = color
            canvas.drawRect(start * cw, 0f, c * cw, ch, bgPaint)
        }

        // 2. Text: runs of grid-safe glyphs, single cells otherwise.
        if (chars.size < cols) chars = CharArray(cols)
        c = 0
        while (c < cols) {
            val i = base + c
            val f = flags[i]
            val cp = cps[i]
            if (cp == 0 || cp == ' '.code || f and (CellFlags.WIDE_SPACER or CellFlags.INVISIBLE) != 0) {
                c++
                continue
            }
            val g = hasGrapheme != null && hasGrapheme[i]
            if (!g && f and CellFlags.WIDE == 0 && isGridSafe(cp)) {
                val color = fg[i]
                val style = f and STYLE_MASK
                var n = 0
                var lastNonSpace = 0
                val start = c
                while (c < cols) {
                    val j = base + c
                    val cj = cps[j]
                    val fj = flags[j]
                    val blank = cj == 0 || cj == ' '.code
                    if (!blank) {
                        if (fg[j] != color || fj and STYLE_MASK != style ||
                            fj and (CellFlags.WIDE or CellFlags.WIDE_SPACER or CellFlags.INVISIBLE) != 0
                        ) {
                            break
                        }
                        if (hasGrapheme != null && hasGrapheme[j]) break
                        if (!isGridSafe(cj)) break
                    }
                    chars[n++] = if (blank) ' ' else cj.toChar()
                    if (!blank) lastNonSpace = n
                    c++
                }
                val paint = paintFor(style)
                paint.color = color
                canvas.drawText(chars, 0, lastNonSpace, start * cw, baseline, paint)
                c = start + lastNonSpace
                continue
            }
            drawCell(canvas, snap, i, c * cw, fg[i], g)
            c++
        }

        // 3. Underline / strikethrough, merged per span.
        drawLines(canvas, snap, base, cols, CellFlags.UNDERLINE, underlineY)
        drawLines(canvas, snap, base, cols, CellFlags.STRIKETHROUGH, strikeY)
    }

    /** One cell's glyph at x (row-relative), e.g. under a block cursor with an inverted [color]. */
    fun drawCell(
        canvas: Canvas,
        snap: ScreenSnapshot,
        index: Int,
        x: Float,
        color: Int,
        grapheme: Boolean,
    ) {
        val f = snap.flags[index]
        val cp = snap.codepoints[index]
        if (cp == 0 || f and (CellFlags.WIDE_SPACER or CellFlags.INVISIBLE) != 0) return
        if (!grapheme && BoxDrawing.handles(cp)) {
            linePaint.color = color
            BoxDrawing.draw(canvas, cp, x, cellWidth, cellHeight.toFloat(), lineThickness, linePaint)
            return
        }
        val paint = paintFor(f and STYLE_MASK)
        paint.color = color
        val span = (if (f and CellFlags.WIDE != 0) 2 else 1) * cellWidth
        val text = if (grapheme) snap.graphemes[index] else null
        val n = if (text == null) Character.toChars(cp, one, 0) else 0
        val w = if (text != null) paint.measureText(text) else paint.measureText(one, 0, n)
        if (w > span * 1.02f && w > 0f) {
            val save = canvas.save()
            canvas.scale(span / w, 1f, x, 0f)
            if (text !=
                null
            ) {
                canvas.drawText(text, x, baseline, paint)
            } else {
                canvas.drawText(one, 0, n, x, baseline, paint)
            }
            canvas.restoreToCount(save)
        } else {
            val dx = x + (span - w) / 2f
            if (text !=
                null
            ) {
                canvas.drawText(text, dx, baseline, paint)
            } else {
                canvas.drawText(one, 0, n, dx, baseline, paint)
            }
        }
    }

    private fun drawLines(
        canvas: Canvas,
        snap: ScreenSnapshot,
        base: Int,
        cols: Int,
        flag: Int,
        y: Float,
    ) {
        val flags = snap.flags
        val fg = snap.foreground
        var c = 0
        while (c < cols) {
            if (flags[base + c] and flag == 0) {
                c++
                continue
            }
            val color = fg[base + c]
            val start = c
            while (c < cols && flags[base + c] and flag != 0 && fg[base + c] == color) c++
            linePaint.color = color
            canvas.drawRect(start * cellWidth, y, c * cellWidth, y + lineThickness, linePaint)
        }
    }

    private fun paintFor(style: Int): Paint =
        textPaints[
            (if (style and CellFlags.BOLD != 0) 1 else 0) or (if (style and CellFlags.ITALIC != 0) 2 else 0),
        ]

    private fun isGridSafe(cp: Int): Boolean {
        if (cp >= 0x10000 || cp in 0xD800..0xDFFF || BoxDrawing.handles(cp)) return false
        when (gridSafe[cp].toInt()) {
            1 -> return true
            2 -> return false
        }
        one[0] = cp.toChar()
        val p = textPaints[0]
        val safe = abs(p.measureText(one, 0, 1) - cellWidth) < 0.02f && p.hasGlyph(cp.toChar().toString())
        gridSafe[cp] = if (safe) 1 else 2
        return safe
    }

    companion object {
        private const val STYLE_MASK = CellFlags.BOLD or CellFlags.ITALIC
        private const val STYLE_BOLD = 1
        private const val STYLE_ITALIC = 2
        private const val STYLE_BOLD_ITALIC = 3
    }
}
