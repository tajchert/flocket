package com.mtajchert.flocket.core.terminal.controller

import com.mtajchert.flocket.core.session.TerminalSize
import com.mtajchert.flocket.core.terminal.engine.ScreenSnapshot
import com.mtajchert.flocket.core.terminal.fake.FakeTerminalEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalControllerScrollTest {
    private val clock = ManualFrameClock()

    private fun TestScope.controller(): TerminalController =
        TerminalController(
            engineFactory = { c, r -> FakeTerminalEngine(c, r, scrollbackLines = 50) },
            theme = FakeTerminalEngine.DEFAULT_THEME,
            frameClock = clock,
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )

    private fun ScreenSnapshot.line(row: Int) =
        buildString {
            for (c in 0 until cols) codepoints[row * cols + c].let { append(if (it == 0) ' ' else it.toChar()) }
        }.trimEnd()

    /** 10 x 3 terminal with line1..line20 (17 rows of scrollback, ids 0..19). */
    private suspend fun TestScope.filled(): Pair<TerminalController, FakeStream> {
        val c = controller()
        val s = FakeStream(TerminalSize(10, 3))
        c.bind(s)
        s.chunks.send((1..20).joinToString("\r\n") { "line$it" }.encodeToByteArray())
        advanceUntilIdle()
        clock.tick()
        advanceUntilIdle()
        return c to s
    }

    private fun TestScope.frame(c: TerminalController): ScreenSnapshot {
        clock.tick()
        advanceUntilIdle()
        return ScreenSnapshot(1, 1).also { c.acquireFrame(it) }
    }

    @Test
    fun `scrollToRow is absolute so moves in flight do not add up`() =
        runTest {
            val (c, _) = filled()
            c.scrollToRow(10)
            c.scrollToRow(12) // before any frame: still lands on 12, not 10 + 2 rows
            val f = frame(c)
            assertEquals(12L, f.historyBase + f.scrollbackRows - f.viewportOffset)
            assertEquals("line13", f.line(0))
            c.scrollToRow(17)
            assertEquals(0, frame(c).viewportOffset)
            c.close()
        }

    @Test
    fun `the row above the viewport is published only while wanted`() =
        runTest {
            val (c, _) = filled()
            val above = RowSnapshot()
            c.scrollToRow(10)
            frame(c)
            assertFalse(c.acquireRowAbove(above))
            c.setRowAboveWanted(true)
            frame(c)
            assertTrue(c.acquireRowAbove(above))
            assertEquals(9L, above.id)
            assertEquals("line10", above.cells.line(0))
            assertFalse(c.acquireRowAbove(above)) // unchanged: no copy
            c.close()
        }

    @Test
    fun `selection text spans scrollback rows the viewport does not show`() =
        runTest {
            val (c, _) = filled()
            assertEquals("ne2\nline3\nline4", c.selectionText(1, 2, 3, 9, altScreen = false))
            c.close()
        }

    @Test
    fun `selection text is null after a screen switch or for blank cells`() =
        runTest {
            val (c, s) = filled()
            s.chunks.send("\u001b[?1049h".encodeToByteArray())
            advanceUntilIdle()
            assertEquals(null, c.selectionText(1, 2, 3, 9, altScreen = false))
            assertEquals(null, c.selectionText(0, 0, 0, 9, altScreen = true)) // empty alt screen
            c.close()
        }

    @Test
    fun `scrollToRow resolves against output fed after the last frame`() =
        runTest {
            val c =
                TerminalController(
                    engineFactory = { cc, r -> FakeTerminalEngine(cc, r, scrollbackLines = 5) },
                    theme = FakeTerminalEngine.DEFAULT_THEME,
                    frameClock = clock,
                    engineDispatcher = StandardTestDispatcher(testScheduler),
                )
            val s = FakeStream(TerminalSize(10, 3))
            c.bind(s)
            s.chunks.send((1..8).joinToString("\r\n") { "line$it" }.encodeToByteArray())
            advanceUntilIdle()
            frame(c) // line1..line8: scrollback full (line1..line5), ids 0..4
            s.chunks.send("\r\nline9\r\nline10".encodeToByteArray()) // evicts two rows, no frame yet
            advanceUntilIdle()
            c.scrollToRow(3) // id 3 = line4, now screen row 1
            assertEquals("line4", frame(c).line(0))
            c.close()
        }

    @Test
    fun `a frame and its row above are acquired together`() =
        runTest {
            val (c, _) = filled()
            c.setRowAboveWanted(true)
            c.scrollToRow(10)
            frame(c)
            val target = ScreenSnapshot(1, 1)
            val above = RowSnapshot()
            val got = c.acquire(target, above)
            assertEquals(TerminalFrameSource.ROW_ABOVE_CHANGED, got and TerminalFrameSource.ROW_ABOVE_CHANGED)
            assertEquals(target.historyBase + target.scrollbackRows - target.viewportOffset - 1, above.id)
            c.close()
        }
}
