package com.mtajchert.flocket.core.terminal.fake

import com.mtajchert.flocket.core.terminal.engine.CellFlags
import com.mtajchert.flocket.core.terminal.engine.CursorStyle
import com.mtajchert.flocket.core.terminal.engine.Key
import com.mtajchert.flocket.core.terminal.engine.KeyInput
import com.mtajchert.flocket.core.terminal.engine.RowTextBuilder
import com.mtajchert.flocket.core.terminal.engine.ScreenSnapshot
import com.mtajchert.flocket.core.terminal.engine.TerminalEngine
import com.mtajchert.flocket.core.terminal.engine.TerminalEvent
import com.mtajchert.flocket.core.terminal.engine.TerminalTheme
import java.util.Base64

/**
 * A tiny, pure-Kotlin [TerminalEngine] for previews, screenshot tests and JVM unit tests.
 *
 * NOT a real terminal emulator. It understands: printable UTF-8 (split sequences across feeds),
 * CR/LF/BS/TAB/BEL, CSI cursor movement (CUU/CUD/CUF/CUB/CUP/HVP/CHA/VPA), ED/EL, SGR (16/256/
 * truecolor, bold, faint, italic, underline, strikethrough, inverse, invisible), DECTCEM (?25),
 * bracketed paste (?2004), DECSCUSR cursor shapes, OSC 0/2 title, OSC 7 cwd, OSC 52 clipboard, and
 * basic East-Asian-wide characters / combining marks. Scrollback only when [scrollbackLines] > 0 (rows
 * scrolled off the top, soft-wrap flags, viewport scrolling); no alternate screen, no reflow, no charsets,
 * no wrap-pending subtleties. Production uses `core:terminal-ghostty`.
 *
 * Like every engine it is not thread-safe; confine it to one thread.
 */
class FakeTerminalEngine(
    cols: Int = 80,
    rows: Int = 24,
    theme: TerminalTheme = DEFAULT_THEME,
    private val scrollbackLines: Int = 0,
) : TerminalEngine {
    override var cols: Int = cols
        private set
    override var rows: Int = rows
        private set

    private var theme = theme
    private var cp = IntArray(cols * rows)
    private var fgSpec = IntArray(cols * rows) { DEFAULT_COLOR }
    private var bgSpec = IntArray(cols * rows) { DEFAULT_COLOR }
    private var attrs = IntArray(cols * rows)
    private val graphemes = HashMap<Int, String>()
    private var dirty = BooleanArray(rows) { true }
    private var wrapped = BooleanArray(rows)
    private val history = ArrayDeque<HistoryRow>()
    private var historyBase = 0L
    private var viewOffset = 0
    private var fullDirty = true
    private var lastTarget: ScreenSnapshot? = null

    private var curCol = 0
    private var curRow = 0
    private var cursorVisible = true
    private var cursorStyle = CursorStyle.Block
    private var bracketedPaste = false

    // Current SGR pen.
    private var penFg = DEFAULT_COLOR
    private var penBg = DEFAULT_COLOR
    private var penAttrs = 0

    private val events = ArrayList<TerminalEvent>()
    private var title = ""
    private var closed = false

    // Parser state.
    private var state = State.Ground
    private val params = StringBuilder()
    private val osc = StringBuilder()
    private var utf8Need = 0
    private var utf8Cp = 0

    /** Total bytes fed (handy for "no bytes dropped" assertions). */
    var bytesFed: Long = 0
        private set

    override fun feed(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        check(!closed) { "engine closed" }
        bytesFed += length
        for (i in offset until offset + length) onByte(bytes[i].toInt() and 0xFF)
    }

    override fun resize(
        cols: Int,
        rows: Int,
    ) {
        check(!closed) { "engine closed" }
        require(cols > 0 && rows > 0)
        if (cols == this.cols && rows == this.rows) return
        val n = cols * rows
        val ncp = IntArray(n)
        val nfg = IntArray(n) { DEFAULT_COLOR }
        val nbg = IntArray(n) { DEFAULT_COLOR }
        val nat = IntArray(n)
        for (r in 0 until minOf(rows, this.rows)) {
            for (c in 0 until minOf(cols, this.cols)) {
                val o = r * this.cols + c
                val d = r * cols + c
                ncp[d] = cp[o]
                nfg[d] = fgSpec[o]
                nbg[d] = bgSpec[o]
                nat[d] = attrs[o]
            }
        }
        graphemes.clear()
        this.cols = cols
        this.rows = rows
        cp = ncp
        fgSpec = nfg
        bgSpec = nbg
        attrs = nat
        dirty = BooleanArray(rows) { true }
        wrapped = BooleanArray(rows)
        fullDirty = true
        curCol = curCol.coerceIn(0, cols - 1)
        curRow = curRow.coerceIn(0, rows - 1)
    }

    override fun setTheme(theme: TerminalTheme) {
        this.theme = theme
        fullDirty = true
    }

    override fun snapshot(target: ScreenSnapshot?): ScreenSnapshot {
        check(!closed) { "engine closed" }
        val snap = target ?: ScreenSnapshot(cols, rows)
        val full = fullDirty || snap !== lastTarget || snap.cols != cols || snap.rows != rows
        snap.resize(cols, rows)
        val scrolled = viewOffset != 0
        for (r in 0 until rows) {
            val d = full || scrolled || dirty[r]
            snap.dirtyRows[r] = d
            if (!d) continue
            copyRow(history.size - viewOffset + r, snap, r)
            dirty[r] = false
        }
        fullDirty = false
        lastTarget = snap
        snap.cursorCol = minOf(curCol, cols - 1)
        snap.cursorRow = curRow
        snap.cursorStyle = cursorStyle
        snap.cursorVisible = cursorVisible && viewOffset == 0
        snap.scrollbackRows = history.size
        snap.viewportOffset = viewOffset
        snap.historyBase = historyBase
        snap.title = title
        return snap
    }

    /** Resolve screen-buffer row [screenRow] (history first, then the screen) into row [dst] of [snap]. */
    private fun copyRow(
        screenRow: Int,
        snap: ScreenSnapshot,
        dst: Int,
    ) {
        val t = theme
        val h = history.getOrNull(screenRow)
        val src = screenRow - history.size
        for (c in 0 until cols) {
            val o = dst * cols + c
            val srcCp: Int
            val srcFg: Int
            val srcBg: Int
            val a: Int
            val g: String?
            if (h != null) {
                val inRow = c < h.cp.size
                srcCp = if (inRow) h.cp[c] else 0
                srcFg = if (inRow) h.fg[c] else DEFAULT_COLOR
                srcBg = if (inRow) h.bg[c] else DEFAULT_COLOR
                a = if (inRow) h.attrs[c] else 0
                g = h.graphemes[c]
            } else {
                val i = src * cols + c
                srcCp = cp[i]
                srcFg = fgSpec[i]
                srcBg = bgSpec[i]
                a = attrs[i]
                g = graphemes[i]
            }
            var fg = resolve(srcFg, t.foreground, t)
            var bg = resolve(srcBg, t.background, t)
            if (a and ATTR_INVERSE != 0) {
                val tmp = fg
                fg = bg
                bg = tmp
            }
            if (a and CellFlags.FAINT != 0) fg = blend(fg, bg)
            snap.codepoints[o] = srcCp
            snap.foreground[o] = fg
            snap.background[o] = bg
            snap.flags[o] = a and ATTR_INVERSE.inv()
            if (g != null) snap.graphemes[o] = g else snap.graphemes.remove(o)
        }
    }

    override fun readRow(
        screenRow: Int,
        target: ScreenSnapshot,
    ): Boolean {
        check(!closed) { "engine closed" }
        if (screenRow !in 0 until history.size + rows) return false
        target.resize(cols, 1)
        copyRow(screenRow, target, 0)
        target.dirtyRows[0] = true
        return true
    }

    override fun textRange(
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int,
    ): String {
        val b = RowTextBuilder()
        val last = history.size + rows - 1
        for (r in startRow.coerceAtLeast(0)..endRow.coerceAtMost(last)) {
            val c0 = (if (r == startRow) startCol else 0).coerceIn(0, cols - 1)
            val c1 = (if (r == endRow) endCol else cols - 1).coerceIn(0, cols - 1)
            val h = history.getOrNull(r)
            if (h != null) {
                b.appendRow(h.cp, h.attrs, 0, c0, minOf(c1, h.cp.size - 1), h.wrapped && r < endRow) { h.graphemes[it] }
            } else {
                val s = r - history.size
                b.appendRow(cp, attrs, s * cols, c0, c1, wrapped[s] && r < endRow) { graphemes[it] }
            }
        }
        return b.build()
    }

    override fun scrollViewport(deltaRows: Int) {
        val v = (viewOffset - deltaRows).coerceIn(0, history.size)
        if (v != viewOffset) fullDirty = true
        viewOffset = v
    }

    override fun scrollToBottom() {
        if (viewOffset != 0) fullDirty = true
        viewOffset = 0
    }

    override fun encodeKey(event: KeyInput): ByteArray = encodeKeyInput(event).encodeToByteArray()

    override fun encodePaste(text: String): ByteArray {
        val body = text.replace("\r\n", "\r").replace('\n', '\r')
        return (if (bracketedPaste) "\u001b[200~$body\u001b[201~" else body).encodeToByteArray()
    }

    override fun drainEvents(): List<TerminalEvent> {
        if (events.isEmpty()) return emptyList()
        val out = ArrayList(events)
        events.clear()
        return out
    }

    override fun text(historyLines: Int): String =
        buildString {
            for (r in 0 until rows) {
                val line = StringBuilder()
                for (c in 0 until cols) {
                    val i = r * cols + c
                    if (attrs[i] and CellFlags.WIDE_SPACER != 0) continue
                    val g = graphemes[i]
                    when {
                        g != null -> line.append(g)
                        cp[i] == 0 -> line.append(' ')
                        else -> line.appendCodePoint(cp[i])
                    }
                }
                append(line.trimEnd())
                if (r < rows - 1) append('\n')
            }
        }.trimEnd('\n')

    override fun close() {
        closed = true
    }

    val isClosed: Boolean get() = closed

    // ---------------------------------------------------------------- parser

    private enum class State { Ground, Esc, Csi, Osc, OscEsc }

    private fun onByte(b: Int) {
        if (utf8Need > 0) {
            if (b and 0xC0 == 0x80) {
                utf8Cp = (utf8Cp shl 6) or (b and 0x3F)
                if (--utf8Need == 0) print(utf8Cp)
                return
            }
            utf8Need = 0
            print(REPLACEMENT)
        }
        when (state) {
            State.Ground -> {
                ground(b)
            }

            State.Esc -> {
                esc(b)
            }

            State.Csi -> {
                csi(b)
            }

            State.Osc -> {
                if (b == 0x07) {
                    endOsc()
                } else if (b == 0x1B) {
                    state = State.OscEsc
                } else {
                    osc.appendCodePoint(b)
                }
            }

            State.OscEsc -> {
                if (b == '\\'.code) endOsc() else state = State.Ground
            }
        }
    }

    private fun ground(b: Int) {
        when {
            b == 0x1B -> state = State.Esc
            b == '\r'.code -> curCol = 0
            b == '\n'.code || b == 0x0B || b == 0x0C -> lineFeed()
            b == 0x08 -> curCol = maxOf(0, minOf(curCol, cols - 1) - 1)
            b == '\t'.code -> curCol = minOf(cols - 1, (curCol / 8 + 1) * 8)
            b == 0x07 -> events += TerminalEvent.Bell
            b < 0x20 || b == 0x7F -> Unit
            b < 0x80 -> print(b)
            b and 0xE0 == 0xC0 -> startUtf8(b and 0x1F, 1)
            b and 0xF0 == 0xE0 -> startUtf8(b and 0x0F, 2)
            b and 0xF8 == 0xF0 -> startUtf8(b and 0x07, 3)
            else -> print(REPLACEMENT)
        }
    }

    private fun startUtf8(
        bits: Int,
        need: Int,
    ) {
        utf8Cp = bits
        utf8Need = need
    }

    private fun esc(b: Int) {
        state = State.Ground
        when (b.toChar()) {
            '[' -> {
                params.setLength(0)
                state = State.Csi
            }

            ']' -> {
                osc.setLength(0)
                state = State.Osc
            }

            'c' -> {
                reset()
            }

            'M' -> {
                if (curRow > 0) curRow--
            }

            else -> {
                Unit
            }
        }
    }

    private fun csi(b: Int) {
        if (b in 0x30..0x3F || b in 0x20..0x2F) {
            params.append(b.toChar())
            return
        }
        state = State.Ground
        val raw = params.toString()
        val private = raw.startsWith("?")
        val intermediate = raw.contains(' ')
        val p =
            raw
                .trimStart('?')
                .trimEnd(' ')
                .split(';')
                .map { it.toIntOrNull() }

        fun arg(
            i: Int,
            def: Int = 1,
        ): Int = p.getOrNull(i)?.takeIf { it != 0 } ?: def
        when (b.toChar()) {
            'A' -> {
                curRow = maxOf(0, curRow - arg(0))
            }

            'B' -> {
                curRow = minOf(rows - 1, curRow + arg(0))
            }

            'C' -> {
                curCol = minOf(cols - 1, curCol + arg(0))
            }

            'D' -> {
                curCol = maxOf(0, minOf(curCol, cols - 1) - arg(0))
            }

            'G' -> {
                curCol = (arg(0) - 1).coerceIn(0, cols - 1)
            }

            'd' -> {
                curRow = (arg(0) - 1).coerceIn(0, rows - 1)
            }

            'H', 'f' -> {
                curRow = (arg(0) - 1).coerceIn(0, rows - 1)
                curCol = (arg(1) - 1).coerceIn(0, cols - 1)
            }

            'J' -> {
                eraseDisplay(p.getOrNull(0) ?: 0)
            }

            'K' -> {
                eraseLine(p.getOrNull(0) ?: 0)
            }

            'm' -> {
                sgr(p)
            }

            'q' -> {
                if (intermediate) {
                    cursorStyle =
                        when (p.getOrNull(0) ?: 0) {
                            3, 4 -> CursorStyle.Underline
                            5, 6 -> CursorStyle.Bar
                            else -> CursorStyle.Block
                        }
                }
            }

            'h', 'l' -> {
                if (private) {
                    val on = b.toChar() == 'h'
                    when (p.getOrNull(0)) {
                        25 -> cursorVisible = on
                        2004 -> bracketedPaste = on
                    }
                }
            }

            else -> {
                Unit
            }
        }
    }

    private fun endOsc() {
        state = State.Ground
        val s = osc.toString()
        val sep = s.indexOf(';')
        if (sep < 0) return
        val body = s.substring(sep + 1)
        when (s.substring(0, sep)) {
            "0", "2" -> {
                title = body
                events += TerminalEvent.TitleChanged(body)
            }

            "7" -> {
                events += TerminalEvent.WorkingDirectoryChanged(body)
            }

            "52" -> {
                val data = body.substringAfter(';', "")
                runCatching { String(Base64.getDecoder().decode(data)) }
                    .onSuccess { events += TerminalEvent.ClipboardWrite(it) }
            }
        }
    }

    private fun sgr(p: List<Int?>) {
        var i = 0
        if (p.isEmpty()) penReset()
        while (i < p.size) {
            when (val v = p[i] ?: 0) {
                0 -> {
                    penReset()
                }

                1 -> {
                    penAttrs = penAttrs or CellFlags.BOLD
                }

                2 -> {
                    penAttrs = penAttrs or CellFlags.FAINT
                }

                3 -> {
                    penAttrs = penAttrs or CellFlags.ITALIC
                }

                4 -> {
                    penAttrs = penAttrs or CellFlags.UNDERLINE
                }

                7 -> {
                    penAttrs = penAttrs or ATTR_INVERSE
                }

                8 -> {
                    penAttrs = penAttrs or CellFlags.INVISIBLE
                }

                9 -> {
                    penAttrs = penAttrs or CellFlags.STRIKETHROUGH
                }

                22 -> {
                    penAttrs = penAttrs and (CellFlags.BOLD or CellFlags.FAINT).inv()
                }

                23 -> {
                    penAttrs = penAttrs and CellFlags.ITALIC.inv()
                }

                24 -> {
                    penAttrs = penAttrs and CellFlags.UNDERLINE.inv()
                }

                27 -> {
                    penAttrs = penAttrs and ATTR_INVERSE.inv()
                }

                28 -> {
                    penAttrs = penAttrs and CellFlags.INVISIBLE.inv()
                }

                29 -> {
                    penAttrs = penAttrs and CellFlags.STRIKETHROUGH.inv()
                }

                in 30..37 -> {
                    penFg = palette(v - 30)
                }

                39 -> {
                    penFg = DEFAULT_COLOR
                }

                in 40..47 -> {
                    penBg = palette(v - 40)
                }

                49 -> {
                    penBg = DEFAULT_COLOR
                }

                in 90..97 -> {
                    penFg = palette(v - 90 + 8)
                }

                in 100..107 -> {
                    penBg = palette(v - 100 + 8)
                }

                38, 48 -> {
                    val mode = p.getOrNull(i + 1)
                    val spec =
                        if (mode == 5) {
                            i += 2
                            palette(p.getOrNull(i) ?: 0)
                        } else if (mode == 2) {
                            val r = p.getOrNull(i + 2) ?: 0
                            val g = p.getOrNull(i + 3) ?: 0
                            val bl = p.getOrNull(i + 4) ?: 0
                            i += 4
                            RGB_BIT or (r and 0xFF shl 16) or (g and 0xFF shl 8) or (bl and 0xFF)
                        } else {
                            DEFAULT_COLOR
                        }
                    if (v == 38) penFg = spec else penBg = spec
                }
            }
            i++
        }
    }

    private fun penReset() {
        penFg = DEFAULT_COLOR
        penBg = DEFAULT_COLOR
        penAttrs = 0
    }

    // ------------------------------------------------------------- screen ops

    private fun print(codepoint: Int) {
        if (isCombining(codepoint)) {
            // Attach to the previous cell's grapheme.
            val prevCol = if (curCol > 0) curCol - 1 else return
            var idx = curRow * cols + prevCol
            if (attrs[idx] and CellFlags.WIDE_SPACER != 0 && prevCol > 0) idx--
            val base = graphemes[idx] ?: String(Character.toChars(cp[idx]))
            graphemes[idx] = base + String(Character.toChars(codepoint))
            dirty[curRow] = true
            return
        }
        val wide = isWide(codepoint)
        val width = if (wide) 2 else 1
        if (curCol + width > cols) {
            wrapped[curRow] = true
            curCol = 0
            lineFeed()
        }
        val i = curRow * cols + curCol
        put(i, codepoint, if (wide) CellFlags.WIDE else 0)
        if (wide) put(i + 1, 0, CellFlags.WIDE_SPACER)
        dirty[curRow] = true
        // curCol may equal cols: "pending wrap", resolved by the next print.
        curCol += width
    }

    private fun put(
        i: Int,
        codepoint: Int,
        extra: Int,
    ) {
        cp[i] = codepoint
        fgSpec[i] = penFg
        bgSpec[i] = penBg
        attrs[i] = penAttrs or extra
        graphemes.remove(i)
    }

    private fun lineFeed() {
        if (curRow < rows - 1) {
            curRow++
            return
        }
        if (scrollbackLines > 0) pushHistory()
        // Scroll up by one row.
        System.arraycopy(wrapped, 1, wrapped, 0, rows - 1)
        wrapped[rows - 1] = false
        System.arraycopy(cp, cols, cp, 0, cols * (rows - 1))
        System.arraycopy(fgSpec, cols, fgSpec, 0, cols * (rows - 1))
        System.arraycopy(bgSpec, cols, bgSpec, 0, cols * (rows - 1))
        System.arraycopy(attrs, cols, attrs, 0, cols * (rows - 1))
        val moved = HashMap<Int, String>()
        for ((k, v) in graphemes) if (k >= cols) moved[k - cols] = v
        graphemes.clear()
        graphemes.putAll(moved)
        clearCells((rows - 1) * cols, rows * cols)
        dirty.fill(true)
    }

    private fun pushHistory() {
        val g = HashMap<Int, String>()
        for ((k, v) in graphemes) if (k < cols) g[k] = v
        history.addLast(
            HistoryRow(
                cp.copyOf(cols),
                fgSpec.copyOf(cols),
                bgSpec.copyOf(cols),
                attrs.copyOf(cols),
                g,
                wrapped[0],
            ),
        )
        if (history.size > scrollbackLines) {
            history.removeFirst()
            historyBase++
        } else if (viewOffset > 0) {
            viewOffset++ // stay on the same content while scrolled up
        }
    }

    private fun clearCells(
        from: Int,
        to: Int,
    ) {
        for (i in from until to) {
            cp[i] = 0
            fgSpec[i] = DEFAULT_COLOR
            bgSpec[i] = penBg
            attrs[i] = 0
            graphemes.remove(i)
        }
    }

    private fun eraseDisplay(mode: Int) {
        val cur = curRow * cols + minOf(curCol, cols - 1)
        when (mode) {
            0 -> clearCells(cur, cols * rows)
            1 -> clearCells(0, cur + 1)
            else -> clearCells(0, cols * rows).also { wrapped.fill(false) }
        }
        dirty.fill(true)
    }

    private fun eraseLine(mode: Int) {
        val start = curRow * cols
        when (mode) {
            0 -> clearCells(start + minOf(curCol, cols), start + cols)
            1 -> clearCells(start, start + minOf(curCol, cols - 1) + 1)
            else -> clearCells(start, start + cols).also { wrapped[curRow] = false }
        }
        dirty[curRow] = true
    }

    private fun reset() {
        clearCells(0, cols * rows)
        wrapped.fill(false)
        historyBase += history.size
        history.clear()
        viewOffset = 0
        penReset()
        curCol = 0
        curRow = 0
        cursorVisible = true
        cursorStyle = CursorStyle.Block
        dirty.fill(true)
    }

    private fun resolve(
        spec: Int,
        default: Int,
        t: TerminalTheme,
    ): Int =
        when {
            spec == DEFAULT_COLOR -> default
            spec and RGB_BIT != 0 -> (0xFF shl 24) or (spec and 0xFFFFFF)
            else -> indexed(spec and 0xFF, t)
        }

    private class HistoryRow(
        val cp: IntArray,
        val fg: IntArray,
        val bg: IntArray,
        val attrs: IntArray,
        val graphemes: Map<Int, String>,
        val wrapped: Boolean,
    )

    companion object {
        private const val DEFAULT_COLOR = -1
        private const val RGB_BIT = 1 shl 24
        private const val ATTR_INVERSE = 1 shl 20
        private const val REPLACEMENT = 0xFFFD

        private fun palette(index: Int) = index and 0xFF

        /** xterm 256-color resolution (0-15 from the theme, 16-231 cube, 232-255 grays). */
        fun indexed(
            index: Int,
            theme: TerminalTheme,
        ): Int =
            when {
                index < 16 -> {
                    theme.palette[index]
                }

                index < 232 -> {
                    val n = index - 16
                    val steps = intArrayOf(0, 95, 135, 175, 215, 255)
                    argb(steps[n / 36], steps[n / 6 % 6], steps[n % 6])
                }

                else -> {
                    (8 + (index - 232) * 10).let { argb(it, it, it) }
                }
            }

        private fun argb(
            r: Int,
            g: Int,
            b: Int,
        ) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

        private fun blend(
            fg: Int,
            bg: Int,
        ): Int {
            fun ch(shift: Int) = (((fg shr shift) and 0xFF) * 2 + ((bg shr shift) and 0xFF)) / 3
            return argb(ch(16), ch(8), ch(0))
        }

        /** Basic East Asian Wide / emoji ranges (not exhaustive). */
        fun isWide(cp: Int): Boolean =
            cp in 0x1100..0x115F ||
                cp in 0x2E80..0x303E ||
                cp in 0x3041..0x33FF ||
                cp in 0x3400..0x4DBF ||
                cp in 0x4E00..0x9FFF ||
                cp in 0xA000..0xA4CF ||
                cp in 0xAC00..0xD7A3 ||
                cp in 0xF900..0xFAFF ||
                cp in 0xFE30..0xFE4F ||
                cp in 0xFF00..0xFF60 ||
                cp in 0xFFE0..0xFFE6 ||
                cp in 0x1F300..0x1F64F ||
                cp in 0x1F900..0x1F9FF ||
                cp in 0x20000..0x3FFFD

        private fun isCombining(cp: Int): Boolean =
            cp in 0x0300..0x036F ||
                cp == 0x200D ||
                cp in 0xFE00..0xFE0F ||
                cp in 0x1F3FB..0x1F3FF ||
                Character.getType(cp) == Character.NON_SPACING_MARK.toInt()

        /** A small, conventional xterm-style key encoding table (no app-cursor or kitty modes). */
        fun encodeKeyInput(e: KeyInput): String {
            val mod = 1 + (if (e.shift) 1 else 0) + (if (e.alt) 2 else 0) + (if (e.ctrl) 4 else 0)

            fun csi(
                final: Char,
                num: Int = 1,
            ) = if (mod > 1) {
                "\u001b[$num;$mod$final"
            } else if (num == 1) {
                "\u001b[$final"
            } else {
                "\u001b[$num$final"
            }

            fun tilde(num: Int) = if (mod > 1) "\u001b[$num;$mod~" else "\u001b[$num~"

            fun ss3(final: Char) = if (mod > 1) "\u001b[1;$mod$final" else "\u001bO$final"
            val altPrefix = if (e.alt) "\u001b" else ""
            return when (e.key) {
                Key.Character -> {
                    val text = e.text ?: return ""
                    if (e.ctrl && text.length == 1) {
                        val c = text[0].uppercaseChar()
                        val ctl =
                            when (c) {
                                in '@'..'_' -> (c.code and 0x1F).toChar().toString()
                                ' ', '2' -> "\u0000"
                                '/' -> "\u001f"
                                '?' -> "\u007f"
                                else -> text
                            }
                        altPrefix + ctl
                    } else {
                        altPrefix + text
                    }
                }

                Key.Enter -> {
                    altPrefix + "\r"
                }

                Key.Tab -> {
                    if (e.shift) "\u001b[Z" else altPrefix + "\t"
                }

                Key.Backspace -> {
                    altPrefix + if (e.ctrl) "\u0008" else "\u007f"
                }

                Key.Escape -> {
                    altPrefix + "\u001b"
                }

                Key.Space -> {
                    altPrefix + if (e.ctrl) "\u0000" else " "
                }

                Key.Up -> {
                    csi('A')
                }

                Key.Down -> {
                    csi('B')
                }

                Key.Right -> {
                    csi('C')
                }

                Key.Left -> {
                    csi('D')
                }

                Key.Home -> {
                    csi('H')
                }

                Key.End -> {
                    csi('F')
                }

                Key.PageUp -> {
                    tilde(5)
                }

                Key.PageDown -> {
                    tilde(6)
                }

                Key.Insert -> {
                    tilde(2)
                }

                Key.Delete -> {
                    tilde(3)
                }

                Key.F1 -> {
                    ss3('P')
                }

                Key.F2 -> {
                    ss3('Q')
                }

                Key.F3 -> {
                    ss3('R')
                }

                Key.F4 -> {
                    ss3('S')
                }

                Key.F5 -> {
                    tilde(15)
                }

                Key.F6 -> {
                    tilde(17)
                }

                Key.F7 -> {
                    tilde(18)
                }

                Key.F8 -> {
                    tilde(19)
                }

                Key.F9 -> {
                    tilde(20)
                }

                Key.F10 -> {
                    tilde(21)
                }

                Key.F11 -> {
                    tilde(23)
                }

                Key.F12 -> {
                    tilde(24)
                }
            }
        }

        /** Dark default used when no theme is given. */
        val DEFAULT_THEME =
            TerminalTheme(
                foreground = 0xFFD8DEE9.toInt(),
                background = 0xFF1B1D23.toInt(),
                cursor = 0xFFE5C07B.toInt(),
                palette =
                    intArrayOf(
                        0xFF2E3440.toInt(),
                        0xFFE06C75.toInt(),
                        0xFF98C379.toInt(),
                        0xFFE5C07B.toInt(),
                        0xFF61AFEF.toInt(),
                        0xFFC678DD.toInt(),
                        0xFF56B6C2.toInt(),
                        0xFFD8DEE9.toInt(),
                        0xFF5C6370.toInt(),
                        0xFFF08A93.toInt(),
                        0xFFB5E08F.toInt(),
                        0xFFF2D49B.toInt(),
                        0xFF82C2F5.toInt(),
                        0xFFD7A0E8.toInt(),
                        0xFF7DD3DD.toInt(),
                        0xFFFFFFFF.toInt(),
                    ),
            )
    }
}
