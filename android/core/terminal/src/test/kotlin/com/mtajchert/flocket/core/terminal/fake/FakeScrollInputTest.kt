package com.mtajchert.flocket.core.terminal.fake

import com.mtajchert.flocket.core.terminal.engine.ScrollInput
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeScrollInputTest {
    private fun engine(vararg modes: String) =
        FakeTerminalEngine(cols = 10, rows = 5, scrollbackLines = 100).apply {
            for (m in modes) feed("\u001b[?$m".encodeToByteArray())
        }

    private fun FakeTerminalEngine.input() = snapshot().scrollInput

    private fun FakeTerminalEngine.scroll(
        lines: Int,
        col: Int = 2,
        row: Int = 3,
    ) = encodeScroll(lines, col, row).decodeToString()

    @Test
    fun `the main screen scrolls its own scrollback`() {
        val e = engine()
        assertEquals(ScrollInput.Scrollback, e.input())
        assertEquals("", e.scroll(-3))
    }

    @Test
    fun `mouse tracking turns swipes into wheel events at the touched cell`() {
        val e = engine("1049h", "1000h", "1006h")
        assertEquals(ScrollInput.MouseWheel, e.input())
        assertEquals("\u001b[<64;3;4M\u001b[<64;3;4M", e.scroll(-2)) // older = wheel up
        assertEquals("\u001b[<65;3;4M", e.scroll(1))
    }

    @Test
    fun `mouse tracking without SGR uses the X10 encoding`() {
        val e = engine("1002h")
        assertEquals(ScrollInput.MouseWheel, e.input())
        assertEquals("\u001b[M" + (32 + 64).toChar() + (32 + 3).toChar() + (32 + 4).toChar(), e.scroll(-1))
    }

    @Test
    fun `the alternate screen without mouse tracking gets arrow keys`() {
        val e = engine("1049h")
        assertEquals(ScrollInput.ArrowKeys, e.input())
        assertEquals("\u001b[A\u001b[A", e.scroll(-2))
        assertEquals("\u001b[B", e.scroll(1))
    }

    @Test
    fun `alternate scroll off leaves the alternate screen alone`() {
        val e = engine("1049h", "1007l")
        assertEquals(ScrollInput.None, e.input())
        assertEquals("", e.scroll(-2))
    }

    @Test
    fun `leaving mouse tracking goes back to scrollback`() {
        val e = engine("1000h", "1000l")
        assertEquals(ScrollInput.Scrollback, e.input())
    }
}
