package com.mtajchert.flocket.core.terminal.ghostty

import com.mtajchert.flocket.core.terminal.engine.CursorStyle
import com.mtajchert.flocket.core.terminal.engine.Key
import com.mtajchert.flocket.core.terminal.engine.KeyInput
import com.mtajchert.flocket.core.terminal.engine.ScreenSnapshot
import com.mtajchert.flocket.core.terminal.engine.TerminalEngine
import com.mtajchert.flocket.core.terminal.engine.TerminalEvent
import com.mtajchert.flocket.core.terminal.engine.TerminalTheme

/**
 * [TerminalEngine] backed by libghostty-vt through JNI (ADR-0003).
 *
 * Threading: NOT thread-safe. All calls, including [close], must come from one thread or a
 * single-parallelism dispatcher chosen by the owner. [close] frees native memory and is idempotent;
 * every other call after [close] throws [IllegalStateException] (it never touches freed memory).
 *
 * Replies libghostty would write to the PTY (DA, DSR, color queries...) are never forwarded: the
 * host core already answers them (protocol/SPEC.md section 7.3).
 */
class GhosttyTerminalEngine(
    cols: Int,
    rows: Int,
    scrollbackLines: Int = DEFAULT_SCROLLBACK_LINES,
) : TerminalEngine {
    private var handle: Long

    override var cols: Int = cols
        private set
    override var rows: Int = rows
        private set

    private val header = IntArray(SnapshotHeader.SIZE)
    private var dirtyInts = IntArray(rows)
    private var lastTarget: ScreenSnapshot? = null
    private var lastTitleGeneration = 0
    private var title = ""
    private var graphemeBuf = IntArray(0)

    init {
        require(cols > 0 && rows > 0) { "terminal size must be positive: ${cols}x$rows" }
        GhosttyNative.ensureLoaded()
        handle = GhosttyNative.nativeCreate(cols, rows, scrollbackLines)
        check(handle != 0L) { "libghostty-vt failed to allocate a terminal" }
    }

    private fun h(): Long {
        check(handle != 0L) { "GhosttyTerminalEngine used after close()" }
        return handle
    }

    override fun feed(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "range out of bounds" }
        GhosttyNative.nativeFeed(h(), bytes, offset, length)
    }

    override fun resize(
        cols: Int,
        rows: Int,
    ) {
        require(cols > 0 && rows > 0) { "terminal size must be positive: ${cols}x$rows" }
        GhosttyNative.nativeResize(h(), cols, rows)
        this.cols = cols
        this.rows = rows
        dirtyInts = IntArray(rows)
    }

    override fun setTheme(theme: TerminalTheme) {
        GhosttyNative.nativeSetTheme(h(), theme.foreground, theme.background, theme.cursor, theme.palette)
    }

    override fun snapshot(target: ScreenSnapshot?): ScreenSnapshot {
        val handle = h()
        val snap = target ?: ScreenSnapshot(cols, rows)
        val sizeChanged = snap.cols != cols || snap.rows != rows
        snap.resize(cols, rows)
        // A different or resized target has no valid previous contents: repaint everything.
        val full = sizeChanged || snap !== lastTarget
        GhosttyNative.nativeSnapshot(
            handle,
            full,
            cols,
            rows,
            snap.codepoints,
            snap.foreground,
            snap.background,
            snap.flags,
            dirtyInts,
            header,
        )
        lastTarget = snap
        for (r in 0 until rows) snap.dirtyRows[r] = dirtyInts[r] != 0
        applyGraphemes(handle, snap)
        snap.cursorCol = header[SnapshotHeader.CURSOR_COL]
        snap.cursorRow = header[SnapshotHeader.CURSOR_ROW]
        snap.cursorVisible = header[SnapshotHeader.CURSOR_VISIBLE] != 0
        snap.cursorStyle =
            when (header[SnapshotHeader.CURSOR_STYLE]) {
                1 -> CursorStyle.Underline
                2 -> CursorStyle.Bar
                else -> CursorStyle.Block
            }
        snap.scrollbackRows = header[SnapshotHeader.SCROLLBACK_ROWS]
        snap.viewportOffset = header[SnapshotHeader.VIEWPORT_OFFSET]
        val gen = header[SnapshotHeader.TITLE_GENERATION]
        if (gen != lastTitleGeneration) {
            lastTitleGeneration = gen
            title = GhosttyNative.nativeTitle(handle).decodeToString()
        }
        snap.title = title
        return snap
    }

    /** Grapheme side channel: [cellIndex, n, cp0..] per multi-codepoint cell, for the rows just painted. */
    private fun applyGraphemes(
        handle: Long,
        snap: ScreenSnapshot,
    ) {
        val map = snap.graphemes
        if (map.isNotEmpty()) {
            val cols = snap.cols
            map.keys.removeAll { idx -> dirtyInts[idx / cols] != 0 }
        }
        val count = header[SnapshotHeader.GRAPHEME_INTS]
        if (count == 0) return
        if (graphemeBuf.size < count) graphemeBuf = IntArray(count + count / 2)
        GhosttyNative.nativeFetchGraphemes(handle, graphemeBuf)
        var i = 0
        val sb = StringBuilder()
        while (i < count) {
            val cell = graphemeBuf[i]
            val n = graphemeBuf[i + 1]
            sb.setLength(0)
            for (k in 0 until n) sb.appendCodePoint(graphemeBuf[i + 2 + k])
            map[cell] = sb.toString()
            i += 2 + n
        }
    }

    override fun scrollViewport(deltaRows: Int) = GhosttyNative.nativeScroll(h(), deltaRows)

    override fun scrollToBottom() = GhosttyNative.nativeScrollToBottom(h())

    override fun encodeKey(event: KeyInput): ByteArray {
        var mods = 0
        if (event.shift) mods = mods or MOD_SHIFT
        if (event.ctrl) mods = mods or MOD_CTRL
        if (event.alt) mods = mods or MOD_ALT
        if (event.meta) mods = mods or MOD_META
        val text = event.text?.takeIf { it.isNotEmpty() }?.encodeToByteArray()
        return GhosttyNative.nativeEncodeKey(h(), event.key.ordinal, mods, text, text?.size ?: 0)
    }

    override fun encodePaste(text: String): ByteArray = GhosttyNative.nativeEncodePaste(h(), text.encodeToByteArray())

    override fun drainEvents(): List<TerminalEvent> {
        val raw = GhosttyNative.nativeDrainEvents(h()) ?: return emptyList()
        return EventDecoder.decode(raw)
    }

    override fun text(historyLines: Int): String =
        GhosttyNative.nativeText(h(), historyLines.coerceAtLeast(0)).decodeToString()

    override fun close() {
        val h = handle
        if (h == 0L) return
        handle = 0L
        GhosttyNative.nativeFree(h)
    }

    companion object {
        const val DEFAULT_SCROLLBACK_LINES = 10_000
        private const val MOD_SHIFT = 1
        private const val MOD_CTRL = 2
        private const val MOD_ALT = 4
        private const val MOD_META = 8

        init {
            // Keep the Key ordinals in sync with key_for() in flocket_ghostty.cpp.
            check(
                Key.Character.ordinal == 0 && Key.Enter.ordinal == 1 && Key.F12.ordinal == 27,
            ) { "Key enum order changed" }
        }
    }
}

/** Decodes the native event stream: `[type u8]{[len u32le][utf8]}*` (see nDrainEvents). */
internal object EventDecoder {
    fun decode(raw: ByteArray): List<TerminalEvent> {
        val out = ArrayList<TerminalEvent>()
        var p = 0

        fun str(): String {
            val n =
                (raw[p].toInt() and 0xFF) or ((raw[p + 1].toInt() and 0xFF) shl 8) or
                    ((raw[p + 2].toInt() and 0xFF) shl 16) or ((raw[p + 3].toInt() and 0xFF) shl 24)
            val s = String(raw, p + 4, n, Charsets.UTF_8)
            p += 4 + n
            return s
        }
        while (p < raw.size) {
            when (raw[p++].toInt()) {
                1 -> {
                    out += TerminalEvent.TitleChanged(str())
                }

                2 -> {
                    out += TerminalEvent.Bell
                }

                3 -> {
                    out += TerminalEvent.ClipboardWrite(str())
                }

                4 -> {
                    out += TerminalEvent.WorkingDirectoryChanged(str())
                }

                5 -> {
                    val title = str()
                    out += TerminalEvent.Notification(title, str())
                }

                else -> {
                    return out
                } // unknown type: stream is unparseable from here
            }
        }
        return out
    }
}

/** Creates [GhosttyTerminalEngine]s; one per terminal. Fails fast if the native libraries are missing. */
class GhosttyTerminalEngineFactory(
    private val scrollbackLines: Int = GhosttyTerminalEngine.DEFAULT_SCROLLBACK_LINES,
) {
    fun create(
        cols: Int,
        rows: Int,
    ): TerminalEngine = GhosttyTerminalEngine(cols, rows, scrollbackLines)
}
