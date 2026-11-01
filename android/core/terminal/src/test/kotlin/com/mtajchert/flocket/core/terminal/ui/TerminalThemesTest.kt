package com.mtajchert.flocket.core.terminal.ui

import com.mtajchert.flocket.core.model.TerminalColorScheme
import com.mtajchert.flocket.core.terminal.engine.TerminalTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class TerminalThemesTest {
    private fun lin(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Int): Double =
        0.2126 * lin((argb shr 16) and 0xFF) + 0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)

    private fun contrast(
        a: Int,
        b: Int,
    ): Double {
        val (hi, lo) = luminance(a).let { x -> luminance(b).let { y -> maxOf(x, y) to minOf(x, y) } }
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `follow app picks light or dark and the rest map to their own theme`() {
        assertEquals(TerminalThemes.Dark, TerminalThemes.forScheme(TerminalColorScheme.FollowApp, appDark = true))
        assertEquals(TerminalThemes.Light, TerminalThemes.forScheme(TerminalColorScheme.FollowApp, appDark = false))
        assertEquals(TerminalThemes.Light, TerminalThemes.forScheme(TerminalColorScheme.Light, appDark = true))
        assertEquals(TerminalThemes.Dark, TerminalThemes.forScheme(TerminalColorScheme.Dark, appDark = false))
        assertEquals(TerminalThemes.SolarizedDark, TerminalThemes.forScheme(TerminalColorScheme.SolarizedDark, false))
        assertEquals(TerminalThemes.SolarizedLight, TerminalThemes.forScheme(TerminalColorScheme.SolarizedLight, true))
        assertEquals(
            TerminalThemes.HighContrastDark,
            TerminalThemes.forScheme(TerminalColorScheme.HighContrastDark, false),
        )
    }

    @Test
    fun `every scheme has a 16 color palette and is opaque`() {
        for (s in TerminalColorScheme.entries) {
            val t: TerminalTheme = TerminalThemes.forScheme(s, true)
            assertEquals(16, t.palette.size)
            assertTrue(t.palette.all { (it ushr 24) == 0xFF } && (t.background ushr 24) == 0xFF)
        }
    }

    @Test
    fun `high contrast reaches AAA for text and every ANSI color except black`() {
        val t = TerminalThemes.HighContrastDark
        assertTrue(contrast(t.foreground, t.background) >= 7.0)
        assertTrue(contrast(t.cursor, t.background) >= 7.0)
        for (i in 1 until 16) {
            val c = contrast(t.palette[i], t.background)
            assertTrue("color $i has contrast $c", c >= 7.0)
        }
    }

    @Test
    fun `solarized text keeps the published contrast and the other themes pass AA`() {
        assertTrue(contrast(TerminalThemes.SolarizedDark.foreground, TerminalThemes.SolarizedDark.background) >= 4.0)
        assertTrue(contrast(TerminalThemes.SolarizedLight.foreground, TerminalThemes.SolarizedLight.background) >= 4.0)
        for (t in listOf(TerminalThemes.Dark, TerminalThemes.Light)) {
            assertTrue(contrast(t.foreground, t.background) >= 4.5)
        }
    }
}
