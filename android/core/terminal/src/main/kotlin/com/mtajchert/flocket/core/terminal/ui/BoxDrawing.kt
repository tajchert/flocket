package com.mtajchert.flocket.core.terminal.ui

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws box-drawing lines (U+2500-U+254F, U+256D-U+257B) and block elements (U+2580-U+259F)
 * geometrically so borders of TUIs (htop, tmux, lazygit) join seamlessly across cells and rows,
 * which font glyphs cannot do when the line height exceeds the glyph height.
 * Braille (U+2800-U+28FF) is drawn as square dots on a fixed grid, like Ghostty and the desktop app:
 * the bundled font has no braille, and fallback glyphs sit and scale differently per pattern, so an
 * agent's dot spinner (⠋⠙⠹…) jittered from frame to frame.
 * Double lines (U+2550-U+256C) and other symbols fall back to the font.
 */
internal object BoxDrawing {
    // Arms per code point: up<<6 | right<<4 | down<<2 | left; 0 = none, 1 = light, 2 = heavy.
    private val LINES: IntArray =
        intArrayOf(
            // 2500
            arms(0, 1, 0, 1),
            arms(0, 2, 0, 2),
            arms(1, 0, 1, 0),
            arms(2, 0, 2, 0),
            arms(0, 1, 0, 1),
            arms(0, 2, 0, 2),
            arms(1, 0, 1, 0),
            arms(2, 0, 2, 0),
            arms(0, 1, 0, 1),
            arms(0, 2, 0, 2),
            arms(1, 0, 1, 0),
            arms(2, 0, 2, 0),
            arms(0, 1, 1, 0),
            arms(0, 2, 1, 0),
            arms(0, 1, 2, 0),
            arms(0, 2, 2, 0),
            // 2510
            arms(0, 0, 1, 1),
            arms(0, 0, 1, 2),
            arms(0, 0, 2, 1),
            arms(0, 0, 2, 2),
            arms(1, 1, 0, 0),
            arms(1, 2, 0, 0),
            arms(2, 1, 0, 0),
            arms(2, 2, 0, 0),
            arms(1, 0, 0, 1),
            arms(1, 0, 0, 2),
            arms(2, 0, 0, 1),
            arms(2, 0, 0, 2),
            arms(1, 1, 1, 0),
            arms(1, 2, 1, 0),
            arms(2, 1, 1, 0),
            arms(1, 1, 2, 0),
            // 2520
            arms(2, 1, 2, 0),
            arms(2, 2, 1, 0),
            arms(1, 2, 2, 0),
            arms(2, 2, 2, 0),
            arms(1, 0, 1, 1),
            arms(1, 0, 1, 2),
            arms(2, 0, 1, 1),
            arms(1, 0, 2, 1),
            arms(2, 0, 2, 1),
            arms(2, 0, 1, 2),
            arms(1, 0, 2, 2),
            arms(2, 0, 2, 2),
            arms(0, 1, 1, 1),
            arms(0, 1, 1, 2),
            arms(0, 2, 1, 1),
            arms(0, 2, 1, 2),
            // 2530
            arms(0, 1, 2, 1),
            arms(0, 1, 2, 2),
            arms(0, 2, 2, 1),
            arms(0, 2, 2, 2),
            arms(1, 1, 0, 1),
            arms(1, 1, 0, 2),
            arms(1, 2, 0, 1),
            arms(1, 2, 0, 2),
            arms(2, 1, 0, 1),
            arms(2, 1, 0, 2),
            arms(2, 2, 0, 1),
            arms(2, 2, 0, 2),
            arms(1, 1, 1, 1),
            arms(1, 1, 1, 2),
            arms(1, 2, 1, 1),
            arms(1, 2, 1, 2),
            // 2540
            arms(2, 1, 1, 1),
            arms(1, 1, 2, 1),
            arms(2, 1, 2, 1),
            arms(2, 1, 1, 2),
            arms(2, 2, 1, 1),
            arms(1, 1, 2, 2),
            arms(1, 2, 2, 1),
            arms(2, 2, 1, 2),
            arms(1, 2, 2, 2),
            arms(2, 1, 2, 2),
            arms(2, 2, 2, 1),
            arms(2, 2, 2, 2),
            arms(0, 1, 0, 1),
            arms(0, 2, 0, 2),
            arms(1, 0, 1, 0),
            arms(2, 0, 2, 0),
        )

    // 256D..257B: rounded corners (drawn square), half lines.
    private val TAIL: IntArray =
        intArrayOf(
            arms(0, 1, 1, 0),
            arms(0, 0, 1, 1),
            arms(1, 0, 0, 1),
            arms(1, 1, 0, 0),
            0,
            0,
            0, // 2571-2573 diagonals: font
            arms(0, 0, 0, 1),
            arms(1, 0, 0, 0),
            arms(0, 1, 0, 0),
            arms(0, 0, 1, 0),
            arms(0, 0, 0, 2),
            arms(2, 0, 0, 0),
            arms(0, 2, 0, 0),
            arms(0, 0, 2, 0),
        )

    private fun arms(
        up: Int,
        right: Int,
        down: Int,
        left: Int,
    ) = (up shl 6) or (right shl 4) or (down shl 2) or left

    fun handles(cp: Int): Boolean =
        cp in 0x2500..0x254F ||
            (cp in 0x256D..0x257B && TAIL[cp - 0x256D] != 0) ||
            cp in 0x2580..0x259F ||
            cp in BRAILLE

    /** Draw [cp] into the cell at ([x], 0) of size [w] x [h]. [paint] carries the color. */
    fun draw(
        canvas: Canvas,
        cp: Int,
        x: Float,
        w: Float,
        h: Float,
        light: Float,
        paint: Paint,
    ) {
        if (cp in BRAILLE) {
            drawBraille(canvas, cp, x, w, h, paint)
            return
        }
        if (cp >= 0x2580) {
            drawBlock(canvas, cp, x, w, h, paint)
            return
        }
        val spec = if (cp < 0x2550) LINES[cp - 0x2500] else TAIL[cp - 0x256D]
        val up = spec shr 6 and 3
        val right = spec shr 4 and 3
        val down = spec shr 2 and 3
        val left = spec and 3
        // Snap the center lines to whole pixels so neighbors join exactly.
        val cx = (x + w / 2).roundToInt().toFloat()
        val cy = (h / 2).roundToInt().toFloat()
        val lt = max(1f, light.roundToInt().toFloat())
        val ht = lt * 2

        fun t(weight: Int) = if (weight == 2) ht else lt
        val hHalf = max(t(up), t(down)) / 2
        val vHalf = max(t(left), t(right)) / 2
        val save = paint.isAntiAlias
        paint.isAntiAlias = false
        if (left != 0) canvas.drawRect(x, cy - t(left) / 2, cx + hHalf, cy + t(left) / 2, paint)
        if (right != 0) canvas.drawRect(cx - hHalf, cy - t(right) / 2, x + w, cy + t(right) / 2, paint)
        if (up != 0) canvas.drawRect(cx - t(up) / 2, 0f, cx + t(up) / 2, cy + vHalf, paint)
        if (down != 0) canvas.drawRect(cx - t(down) / 2, cy - vHalf, cx + t(down) / 2, h, paint)
        paint.isAntiAlias = save
    }

    private val BRAILLE = 0x2800..0x28FF

    /** (col, row) of braille bits 0-7: dots 1,2,3 then 4,5,6 down the columns, then 7 and 8 at the bottom. */
    private val BRAILLE_CELLS = intArrayOf(0, 0, 0, 1, 0, 2, 1, 0, 1, 1, 1, 2, 0, 3, 1, 3)

    private fun drawBraille(
        canvas: Canvas,
        cp: Int,
        x: Float,
        w: Float,
        h: Float,
        paint: Paint,
    ) {
        // Whole device pixels, so every frame of a spinner puts each dot on exactly the same pixels.
        val left = x.roundToInt()
        val dots = brailleDots(cp, (x + w).roundToInt() - left, h.roundToInt()) ?: return
        val save = paint.isAntiAlias
        paint.isAntiAlias = false
        for (i in dots.indices step 4) {
            canvas.drawRect(
                (left + dots[i]).toFloat(),
                dots[i + 1].toFloat(),
                (left + dots[i + 2]).toFloat(),
                dots[i + 3].toFloat(),
                paint,
            )
        }
        paint.isAntiAlias = save
    }

    /**
     * The dots of braille [cp] in a [width] x [height] pixel cell, as `x0, y0, x1, y1` quads; null when
     * [cp] is not braille. Ghostty's dot sizing, with vertical spacing even across adjacent cells.
     * Adapted from Herdr.app `src/sprites.rs` (Apache-2.0).
     */
    internal fun brailleDots(
        cp: Int,
        width: Int,
        height: Int,
    ): IntArray? {
        if (cp !in BRAILLE) return null
        var dot = minOf(width / 4, height / 8)
        var xs = width / 4
        val ys = height / 8
        var xm = xs / 2
        val ym = ys / 2
        var xr = width - 2 * xm - xs - 2 * dot
        var yr = height - 2 * ym - 3 * ys - 4 * dot
        if (xr >= 2 && yr >= 4 && dot == 0) {
            dot += 1
            xr -= 2
            yr -= 4
        }
        if (xr >= 2 && xm == 0) {
            xm = 1
            xr -= 2
        }
        if (yr >= 2 && ym == 0) yr -= 2
        if (xr >= 1) {
            xs += 1
            xr -= 1
        }
        if (yr >= 3) yr -= 3
        if (xr >= 2) {
            xm += 1
            xr -= 2
        }
        if (yr >= 2) yr -= 2
        if (xr >= 2 && yr >= 4) dot += 1
        val bits = cp - BRAILLE.first
        val out = IntArray(Integer.bitCount(bits) * 4)
        var n = 0
        for (bit in 0 until 8) {
            if (bits and (1 shl bit) == 0) continue
            val col = BRAILLE_CELLS[bit * 2]
            val row = BRAILLE_CELLS[bit * 2 + 1]
            val dx = xm + col * (dot + xs)
            val dy = ((2 * row + 1) * height - 4 * dot + 4) / 8
            out[n++] = dx
            out[n++] = dy
            out[n++] = dx + dot
            out[n++] = dy + dot
        }
        return out
    }

    private fun drawBlock(
        canvas: Canvas,
        cp: Int,
        x: Float,
        w: Float,
        h: Float,
        paint: Paint,
    ) {
        val save = paint.isAntiAlias
        val alpha = paint.alpha
        paint.isAntiAlias = false
        when (cp) {
            0x2580 -> {
                canvas.drawRect(x, 0f, x + w, h / 2, paint)
            }

            in 0x2581..0x2588 -> {
                canvas.drawRect(x, h - h * (cp - 0x2580) / 8f, x + w, h, paint)
            }

            in 0x2589..0x258F -> {
                canvas.drawRect(x, 0f, x + w * (0x2590 - cp) / 8f, h, paint)
            }

            0x2590 -> {
                canvas.drawRect(x + w / 2, 0f, x + w, h, paint)
            }

            in 0x2591..0x2593 -> {
                paint.alpha = alpha * (cp - 0x2590) / 4
                canvas.drawRect(x, 0f, x + w, h, paint)
            }

            0x2594 -> {
                canvas.drawRect(x, 0f, x + w, h / 8, paint)
            }

            0x2595 -> {
                canvas.drawRect(x + w * 7 / 8, 0f, x + w, h, paint)
            }

            else -> {
                // Quadrants 2596-259F: UL=1, UR=2, LL=4, LR=8.
                val q = QUADRANTS[cp - 0x2596]
                val mx = x + w / 2
                val my = h / 2
                if (q and 1 != 0) canvas.drawRect(x, 0f, mx, my, paint)
                if (q and 2 != 0) canvas.drawRect(mx, 0f, x + w, my, paint)
                if (q and 4 != 0) canvas.drawRect(x, my, mx, h, paint)
                if (q and 8 != 0) canvas.drawRect(mx, my, x + w, h, paint)
            }
        }
        paint.alpha = alpha
        paint.isAntiAlias = save
    }

    private val QUADRANTS = intArrayOf(4, 8, 1, 1 or 4 or 8, 1 or 8, 1 or 2 or 4, 1 or 2 or 8, 2, 2 or 4, 2 or 4 or 8)
}
