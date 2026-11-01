package com.mtajchert.flocket.core.terminal.ghostty

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtajchert.flocket.core.terminal.engine.CellFlags
import com.mtajchert.flocket.core.terminal.engine.CursorStyle
import com.mtajchert.flocket.core.terminal.engine.Key
import com.mtajchert.flocket.core.terminal.engine.KeyInput
import com.mtajchert.flocket.core.terminal.engine.ScreenSnapshot
import com.mtajchert.flocket.core.terminal.engine.TerminalEvent
import com.mtajchert.flocket.core.terminal.engine.TerminalTheme
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GhosttyTerminalEngineTest {
    private lateinit var engine: GhosttyTerminalEngine

    private val theme =
        TerminalTheme(
            foreground = 0xFFE0E0E0.toInt(),
            background = 0xFF101010.toInt(),
            cursor = 0xFFFFFFFF.toInt(),
            palette = IntArray(16) { 0xFF000000.toInt() or (it * 0x101010) or 0x000001 * it },
        )

    @Before
    fun setUp() {
        engine = GhosttyTerminalEngine(cols = 20, rows = 5)
        engine.setTheme(theme)
    }

    @After
    fun tearDown() = engine.close()

    private fun feed(s: String) = engine.feed(s.toByteArray(Charsets.UTF_8))

    private fun feedBytes(vararg b: Int) = engine.feed(ByteArray(b.size) { b[it].toByte() })

    private fun ScreenSnapshot.row(r: Int): String =
        (0 until cols)
            .joinToString("") { c ->
                val i = index(c, r)
                if (flags[i] and CellFlags.WIDE_SPACER != 0) "" else textAt(i)
            }.trimEnd()

    private fun key(
        k: Key,
        text: String? = null,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ) = engine.encodeKey(KeyInput(k, text, ctrl = ctrl, alt = alt, shift = shift)).toString(Charsets.ISO_8859_1)

    @Test
    fun zwjFamilyIsOneWideGrapheme() {
        feed("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67X")
        val s = engine.snapshot()
        assertEquals("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", s.textAt(s.index(0, 0)))
        assertEquals(3, s.cursorCol)
        assertEquals("X", s.textAt(s.index(2, 0)))
    }

    @Test
    fun zwjFamilyStaysOneGraphemeAfterSoftReset() {
        feed("\u001b[!p")
        feed("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67X")
        val s = engine.snapshot()
        assertEquals("X", s.textAt(s.index(2, 0)))
    }

    @Test
    fun zwjFamilyStaysOneGraphemeAfterHardReset() {
        feed("\u001bc")
        feed("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67X")
        val s = engine.snapshot()
        assertEquals("X", s.textAt(s.index(2, 0)))
    }

    @Test
    fun flagIsOneWideCell() {
        feed("\uD83C\uDDF5\uD83C\uDDF1X")
        val s = engine.snapshot()
        assertEquals("\uD83C\uDDF5\uD83C\uDDF1", s.textAt(s.index(0, 0)))
        assertEquals("X", s.textAt(s.index(2, 0)))
    }

    @Test
    fun programCanDisableGraphemeClustering() {
        feed("\u001b[?2027l")
        feed("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67X")
        val s = engine.snapshot()
        assertTrue(s.cursorCol > 3)
    }

    @Test
    fun plainTextAndCursor() {
        feed("hello\r\nworld")
        val s = engine.snapshot()
        assertEquals("hello", s.row(0))
        assertEquals("world", s.row(1))
        assertEquals(5, s.cursorCol)
        assertEquals(1, s.cursorRow)
        assertTrue(s.cursorVisible)
        assertEquals(theme.foreground, s.foreground[s.index(0, 0)])
        assertEquals(theme.background, s.background[s.index(0, 0)])
        assertTrue(s.dirtyRows[0] && s.dirtyRows[1])
    }

    @Test
    fun incrementalSnapshotMarksOnlyChangedRowsDirty() {
        feed("a\r\nb\r\nc")
        val s = engine.snapshot()
        feed("\u001b[1;1HX")
        engine.snapshot(s)
        assertEquals("X", s.row(0))
        assertEquals("b", s.row(1))
        assertTrue(s.dirtyRows[0])
        assertFalse("dirty=${s.dirtyRows.toList()}", s.dirtyRows[1])
        // Row 2 is dirty too: the cursor left it (ghostty dirties the previous cursor row).
        assertFalse("dirty=${s.dirtyRows.toList()}", s.dirtyRows[3] || s.dirtyRows[4])
    }

    @Test
    fun sgrColors16_256_truecolor_andAttributes() {
        feed(
            "\u001b[31mR\u001b[0m\u001b[38;5;196mP\u001b[0m\u001b[38;2;1;2;3mT\u001b[0m\u001b[1;4;3mB\u001b[0m\u001b[48;2;9;8;7mG\u001b[0m",
        )
        val s = engine.snapshot()
        assertEquals(theme.palette[1], s.foreground[s.index(0, 0)])
        assertEquals(0xFFFF0000.toInt(), s.foreground[s.index(1, 0)]) // palette 196 = #ff0000
        assertEquals(0xFF010203.toInt(), s.foreground[s.index(2, 0)])
        val f = s.flags[s.index(3, 0)]
        assertTrue(f and CellFlags.BOLD != 0 && f and CellFlags.UNDERLINE != 0 && f and CellFlags.ITALIC != 0)
        assertEquals(0xFF090807.toInt(), s.background[s.index(4, 0)])
        assertEquals(theme.foreground, s.foreground[s.index(5, 0)]) // empty cell uses defaults
    }

    @Test
    fun inverseSwapsColors() {
        feed("\u001b[7mI")
        val s = engine.snapshot()
        assertEquals(theme.background, s.foreground[0])
        assertEquals(theme.foreground, s.background[0])
    }

    @Test
    fun wideCharsAndEmojiGraphemes() {
        feed("\u001b[?2027h") // grapheme clustering is off by default in libghostty-vt
        feed("中A👍🏽!") // CJK, A, thumbs-up + skin tone modifier, !
        val s = engine.snapshot()
        assertEquals(0x4e2d, s.codepoints[0])
        assertTrue(s.flags[0] and CellFlags.WIDE != 0)
        assertTrue(s.flags[1] and CellFlags.WIDE_SPACER != 0)
        assertEquals('A'.code, s.codepoints[2])
        val emojiIdx = 3
        assertTrue(s.flags[emojiIdx] and CellFlags.WIDE != 0)
        assertEquals(
            "cps=${(0 until 8).map { s.codepoints[it].toString(16) }} g=${s.graphemes}",
            "👍🏽",
            s.textAt(emojiIdx),
        )
        assertEquals('!'.code, s.codepoints[5])
    }

    @Test
    fun combiningMarkIsOneGrapheme() {
        feed("e\u0301x")
        val s = engine.snapshot()
        assertEquals("e\u0301", s.textAt(0))
        assertEquals('x'.code, s.codepoints[1])
    }

    @Test
    fun cursorMovementAndErase() {
        feed("abcdef\u001b[3D\u001b[Kxy\u001b[2;5HZ")
        val s = engine.snapshot()
        assertEquals("abcxy", s.row(0))
        assertEquals("    Z", s.row(1))
        assertEquals(5, s.cursorCol)
        assertEquals(1, s.cursorRow)
    }

    @Test
    fun cursorStyleAndVisibility() {
        feed("\u001b[?25l\u001b[6 q")
        val s = engine.snapshot()
        assertFalse(s.cursorVisible)
        assertEquals(CursorStyle.Bar, s.cursorStyle)
    }

    @Test
    fun alternateScreen1049RestoresPrimary() {
        feed("primary")
        feed("\u001b[?1049h\u001b[2J\u001b[HALT")
        var s = engine.snapshot()
        assertEquals("ALT", s.row(0))
        feed("\u001b[?1049l")
        s = engine.snapshot(s)
        assertEquals("primary", s.row(0))
    }

    @Test
    fun scrollbackAndViewportScrolling() {
        for (i in 1..12) feed("line$i\r\n")
        var s = engine.snapshot()
        assertTrue(s.scrollbackRows >= 8)
        assertEquals(0, s.viewportOffset)
        engine.scrollViewport(-3)
        s = engine.snapshot(s)
        assertEquals(3, s.viewportOffset)
        assertTrue(s.dirtyRows.all { it })
        engine.scrollToBottom()
        s = engine.snapshot(s)
        assertEquals(0, s.viewportOffset)
        val text = engine.text(historyLines = 100)
        assertTrue(text.startsWith("line1\n"))
        assertTrue(text.contains("line12"))
    }

    @Test
    fun resizeKeepsContent() {
        feed("hello")
        engine.resize(30, 8)
        val s = engine.snapshot()
        assertEquals(30, s.cols)
        assertEquals(8, s.rows)
        assertEquals("hello", s.row(0))
    }

    @Test
    fun eventsTitleBellClipboardPwdNotification() {
        feed("\u001b]2;my title\u0007\u0007")
        feed("\u001b]52;c;aGVsbG8=\u0007")
        feed("\u001b]7;file://host/tmp/x\u0007")
        feed("\u001b]777;notify;Build;done\u0007")
        val ev = engine.drainEvents()
        assertTrue(ev.contains(TerminalEvent.TitleChanged("my title")))
        assertTrue(ev.contains(TerminalEvent.Bell))
        assertTrue(ev.contains(TerminalEvent.ClipboardWrite("hello")))
        assertTrue(ev.contains(TerminalEvent.WorkingDirectoryChanged("file://host/tmp/x")))
        assertTrue(ev.contains(TerminalEvent.Notification("Build", "done")))
        assertTrue(engine.drainEvents().isEmpty())
        assertEquals("my title", engine.snapshot().title)
    }

    @Test
    fun splitUtf8AndEscapeSequencesAcrossFeeds() {
        val bytes = "\u001b[31mé".toByteArray()
        for (b in bytes) engine.feed(byteArrayOf(b))
        val s = engine.snapshot()
        assertEquals(0xE9, s.codepoints[0])
        assertEquals(theme.palette[1], s.foreground[0])
    }

    @Test
    fun queriesProduceNoCrashAndNoOutput() {
        feed("\u001b[c\u001b[6n\u001b]11;?\u0007\u001b[>q")
        feed("ok")
        assertEquals("ok", engine.snapshot().row(0))
    }

    @Test
    fun attachFirstPaintFixture() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val hex =
            ctx.assets
                .open("attach-first-paint.hex")
                .bufferedReader()
                .readText()
                .trim()
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        // Dirty state first, then the repaint (starts with DECSTR ESC[!p).
        feed("garbage\r\nmore")
        engine.feed(bytes)
        val s = engine.snapshot()
        assertEquals("sh-3.2$", s.row(0))
        assertEquals("", s.row(1))
        assertEquals(8, s.cursorCol)
        assertEquals(0, s.cursorRow)
        // Mid-stream repaint beginning with CAN and a mode 9001 toggle must be tolerated.
        feedBytes(0x18)
        feed("\u001b[?9001h\u001b[Hagain")
        assertEquals("again2$", engine.snapshot(s).row(0))
        // OSC 7501 (program status) must not break parsing.
        feed("\u001b]7501;state=idle\u0007!")
        assertEquals("again!$", engine.snapshot(s).row(0))
    }

    @Test
    fun keyArrowsHonorApplicationCursorMode() {
        assertEquals("\u001b[A", key(Key.Up))
        assertEquals("\u001b[D", key(Key.Left))
        feed("\u001b[?1h")
        assertEquals("\u001bOA", key(Key.Up))
        assertEquals("\u001bOD", key(Key.Left))
        feed("\u001b[?1l")
        assertEquals("\u001b[A", key(Key.Up))
    }

    @Test
    fun keyBasics() {
        assertEquals("\u0003", key(Key.Character, "c", ctrl = true))
        assertEquals("\r", key(Key.Enter))
        assertEquals("\u001bx", key(Key.Character, "x", alt = true))
        assertEquals("a", key(Key.Character, "a"))
        assertEquals("A", key(Key.Character, "A", shift = true))
        assertEquals("\u007f", key(Key.Backspace))
        assertEquals("\t", key(Key.Tab))
        assertEquals("\u001b", key(Key.Escape))
        assertEquals("\u001b[3~", key(Key.Delete))
        assertEquals("\u001bOP", key(Key.F1))
        assertEquals("é".toByteArray().toString(Charsets.ISO_8859_1), key(Key.Character, "é"))
    }

    @Test
    fun keySpaceAndEditingKeysWithModifiers() {
        assertEquals(" ", key(Key.Space))
        assertEquals("\u0000", key(Key.Space, ctrl = true))
        assertEquals("\u001b ", key(Key.Space, alt = true))
        assertEquals(" ", key(Key.Space, shift = true))
        assertEquals("\u001b\r", key(Key.Enter, alt = true))
        assertEquals("\u001b[Z", key(Key.Tab, shift = true))
        assertEquals("\u001b\u007f", key(Key.Backspace, alt = true))
        assertEquals("\u0008", key(Key.Backspace, ctrl = true))
        assertEquals("\u001b\u001b", key(Key.Escape, alt = true))
    }

    @Test
    fun mode9001DoesNotAlterKeyEncoding() {
        feed("\u001b[?9001h")
        assertEquals("\u0003", key(Key.Character, "c", ctrl = true))
        assertEquals("\u001b[A", key(Key.Up))
        assertEquals("\r", key(Key.Enter))
    }

    @Test
    fun pasteBracketedOnlyWhenEnabled() {
        assertArrayEquals("a\rb".toByteArray(), engine.encodePaste("a\nb"))
        feed("\u001b[?2004h")
        assertArrayEquals("\u001b[200~a\nb\u001b[201~".toByteArray(), engine.encodePaste("a\nb"))
        feed("\u001b[?2004l")
        assertArrayEquals("x".toByteArray(), engine.encodePaste("x"))
    }

    @Test
    fun useAfterCloseThrowsAndCloseIsIdempotent() {
        val e = GhosttyTerminalEngine(5, 2)
        e.close()
        e.close()
        val failed = runCatching { e.feed(byteArrayOf(1)) }.exceptionOrNull()
        assertTrue(failed is IllegalStateException)
    }

    @Test
    fun longCommittedTextIsEncodedWhole() {
        // 25 UTF-8 bytes: a 16-byte native buffer used to cut it mid-sequence.
        val family = "👨‍👩‍👧‍👦"
        val out = engine.encodeKey(KeyInput(Key.Character, family))
        assertEquals(family, out.decodeToString())
        assertEquals("é", engine.encodeKey(KeyInput(Key.Character, "é")).decodeToString())
    }

    @Test
    fun outOfRangeSizesAreRejected() {
        assertTrue(runCatching { engine.resize(70_000, 5) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(20, engine.cols)
        assertTrue(runCatching { GhosttyTerminalEngine(5, 2, scrollbackLines = -1) }.isFailure)
    }

    @Test
    fun targetResizedByCallerIsRepaintedFully() {
        feed("top\r\nbottom")
        val s = engine.snapshot()
        s.resize(10, 2)
        s.resize(20, 5) // fresh, empty arrays of the same size
        engine.snapshot(s)
        assertEquals("top", s.row(0))
        assertEquals("bottom", s.row(1))
    }

    @Test
    fun throughputSmoke() {
        val line = ("\u001b[32mHello world 0123456789 abcdefghij\u001b[0m\r\n").repeat(200).toByteArray()
        val s = ScreenSnapshot(20, 5)
        val t0 = System.nanoTime()
        repeat(200) {
            engine.feed(line)
            engine.snapshot(s)
        }
        val ms = (System.nanoTime() - t0) / 1e6
        println("GHOSTTY_PERF 200x(${line.size}B feed + snapshot) = $ms ms")
        assertTrue("too slow: $ms ms", ms < 5000)
    }

    // ---- Smooth scrolling and selection: readRow, textRange, historyBase, scrollViewportTo ----

    private fun lines(
        from: Int,
        to: Int,
    ) = feed((from..to).joinToString("") { "\r\nline$it" })

    @Test
    fun readRowOfAScrollbackRowMatchesTheSnapshotOfThatRow() {
        feed("\u001b[31mred\u001b[0m \u001b[1;44mbold\u001b[0m")
        lines(1, 10)
        val row = ScreenSnapshot(1, 1)
        assertTrue(engine.readRow(0, row))
        assertEquals("red bold", row.row(0))
        assertEquals(theme.palette[1], row.foreground[0])
        assertTrue(row.flags[4] and CellFlags.BOLD != 0)
        // The same row through the viewport: identical cells.
        engine.scrollViewportTo(0)
        val snap = engine.snapshot()
        assertEquals("red bold", snap.row(0))
        assertArrayEquals(snap.codepoints.copyOf(20), row.codepoints.copyOf(20))
        assertArrayEquals(snap.foreground.copyOf(20), row.foreground.copyOf(20))
        assertArrayEquals(snap.background.copyOf(20), row.background.copyOf(20))
        assertArrayEquals(snap.flags.copyOf(20), row.flags.copyOf(20))
        assertFalse(engine.readRow(10_000, row))
    }

    @Test
    fun textRangeJoinsWrappedLinesAndKeepsWideCharsAndZwjEmojiWhole() {
        val long = "abcdefghijklmnopqrstuvwxyz0123" // 30 cells: wraps once at 20 columns
        feed("$long\r\nnext\r\na你b \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 x")
        assertEquals("$long\nnext", engine.textRange(0, 0, 2, 19))
        assertEquals("a你b \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 x", engine.textRange(3, 0, 3, 19))
        // An end past the last row is clamped, never an error.
        assertEquals("next", engine.textRange(2, 0, 10_000, 19).lines()[0])
    }

    /**
     * Feeds 50-line "frames" with a snapshot after each (like the controller) and checks the row-id
     * contract on every frame: a row that is still there keeps its id and text, historyBase never
     * decreases, and ids of rows that are gone are never reused.
     */
    private fun checkRowIds(
        scrollbackLines: Int,
        frames: Int,
    ): Pair<GhosttyTerminalEngine, ScreenSnapshot> {
        val e = GhosttyTerminalEngine(cols = 20, rows = 5, scrollbackLines = scrollbackLines)
        val seen = HashMap<Long, String>()
        var maxSeen = -1L
        var lastBase = 0L
        var pruned = false
        var n = 0
        val row = ScreenSnapshot(1, 1)
        var snap = e.snapshot()
        repeat(frames) {
            e.feed((1..50).joinToString("") { "line${++n}\r\n" }.toByteArray())
            snap = e.snapshot()
            val base = snap.historyBase
            assertTrue("historyBase went back", base >= lastBase)
            if (base > lastBase) pruned = true
            for ((id, text) in seen) {
                if (id < base) continue
                assertTrue(e.readRow((id - base).toInt(), row))
                assertEquals("row id $id changed content", text, row.row(0))
            }
            seen.keys.removeAll { it < base }
            // Ids handed out from now on must not collide with ids of rows that are gone.
            if (seen.isEmpty()) assertTrue("id reused", base > maxSeen)
            for (screenRow in intArrayOf(0, snap.scrollbackRows / 2, snap.scrollbackRows - 1)) {
                if (screenRow < 0 || !e.readRow(screenRow, row)) continue
                val id = base + screenRow
                seen[id] = row.row(0)
                maxSeen = maxOf(maxSeen, id)
            }
            lastBase = base
        }
        assertTrue("nothing was trimmed", pruned)
        return e to snap
    }

    @Test
    fun historyBaseIsExactWhenTrimmingKeepsTheNewestRows() {
        // Ghostty trims whole pages (about 2233 rows at 20 columns). With a cap above one page the
        // newest rows always survive a trim, so ids equal absolute line numbers: line k has id k - 1.
        val (e, snap) = checkRowIds(scrollbackLines = 3000, frames = 200)
        try {
            val row = ScreenSnapshot(1, 1)
            assertTrue(e.readRow(0, row))
            assertEquals("line${snap.historyBase + 1}", row.row(0))
            assertEquals(10_000L, snap.historyBase + snap.scrollbackRows + snap.cursorRow)
            assertEquals(snap.historyBase, e.historyBase())
        } finally {
            e.close()
        }
    }

    @Test
    fun rowIdsStayStableWhenATrimRemovesEveryRowSeenBefore() {
        // A 100-line cap is far below one page: the first trim drops the whole first page, newer than
        // anything the last snapshot saw. Old ids then all point at evicted rows (a selection there is
        // cleared) and the surviving rows get new ids above them; nothing is renumbered.
        checkRowIds(scrollbackLines = 100, frames = 60).first.close()
    }

    @Test
    fun alternateScreenIsFlaggedAndLeavesHistoryBaseAlone() {
        lines(1, 30)
        val primary = engine.snapshot()
        assertFalse(primary.altScreen)
        feed("\u001b[?1049hvim")
        val alt = engine.snapshot()
        assertTrue(alt.altScreen)
        assertEquals(0, alt.scrollbackRows)
        assertEquals(primary.historyBase, alt.historyBase)
        feed("\u001b[?1049l")
        val back = engine.snapshot()
        assertFalse(back.altScreen)
        assertEquals(primary.scrollbackRows, back.scrollbackRows)
    }

    @Test
    fun scrollViewportToIsAbsoluteAndClampsToTheBottom() {
        lines(1, 30)
        engine.scrollViewportTo(3)
        val s = engine.snapshot()
        assertEquals("line3", s.row(0)) // screen row 0 is the empty first line
        assertEquals(s.scrollbackRows - 3, s.viewportOffset)
        engine.scrollViewportTo(10_000)
        assertEquals(0, engine.snapshot().viewportOffset)
    }
}
