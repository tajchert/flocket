package com.mtajchert.flocket.core.terminal.ui

import com.mtajchert.flocket.core.terminal.engine.TerminalTheme
import com.mtajchert.flocket.core.terminal.fake.FakeTerminalEngine

/** Built-in terminal color schemes (ARGB). */
object TerminalThemes {
    val Dark: TerminalTheme = FakeTerminalEngine.DEFAULT_THEME

    val Light: TerminalTheme =
        TerminalTheme(
            foreground = 0xFF2B2F36.toInt(),
            background = 0xFFFAFAF7.toInt(),
            cursor = 0xFF3B6FD8.toInt(),
            palette =
                intArrayOf(
                    0xFF3A3F4B.toInt(),
                    0xFFC4323F.toInt(),
                    0xFF3C8A2E.toInt(),
                    0xFF9A6A00.toInt(),
                    0xFF2D63C8.toInt(),
                    0xFF9B3DB8.toInt(),
                    0xFF1D8191.toInt(),
                    0xFFBFC3CA.toInt(),
                    0xFF6B7180.toInt(),
                    0xFFE0505C.toInt(),
                    0xFF4DA63B.toInt(),
                    0xFFB98200.toInt(),
                    0xFF4A80E6.toInt(),
                    0xFFB45AD1.toInt(),
                    0xFF2A9CAE.toInt(),
                    0xFF2B2F36.toInt(),
                ),
        )

    fun forDarkMode(dark: Boolean): TerminalTheme = if (dark) Dark else Light
}
