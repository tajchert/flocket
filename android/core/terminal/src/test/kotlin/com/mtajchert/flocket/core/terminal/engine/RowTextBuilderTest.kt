package com.mtajchert.flocket.core.terminal.engine

import com.mtajchert.flocket.core.terminal.fake.FakeTerminalEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class RowTextBuilderTest {
    @Test
    fun `wrapped rows join without a newline and keep spaces at the wrap`() {
        val b = RowTextBuilder()
        val cp = "ab  ".map { if (it == ' ') 0 else it.code }.toIntArray()
        val flags = IntArray(4)
        b.appendRow(cp, flags, 0, 0, 3, wrapped = true) { null }
        b.appendRow(cp, flags, 0, 0, 3, wrapped = false) { null }
        b.appendRow(cp, flags, 0, 0, 3, wrapped = false) { null }
        assertEquals("ab  ab\nab", b.build())
    }

    @Test
    fun `snapshot textRange skips wide spacers and uses grapheme text`() {
        val e = FakeTerminalEngine(8, 2)
        e.feed("你好́ a  \r\nnext".encodeToByteArray())
        val s = e.snapshot()
        assertEquals("你好́ a\nne", s.textRange(0, 0, 1, 1))
        assertEquals("好́", s.textRange(0, 2, 0, 3))
    }
}
