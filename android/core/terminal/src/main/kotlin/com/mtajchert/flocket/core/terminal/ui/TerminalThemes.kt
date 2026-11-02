package com.mtajchert.flocket.core.terminal.ui

import com.mtajchert.flocket.core.model.TerminalColorScheme
import com.mtajchert.flocket.core.terminal.engine.TerminalTheme
import com.mtajchert.flocket.core.terminal.fake.FakeTerminalEngine

/** Built-in terminal color schemes (ARGB). */
object TerminalThemes {
    /** Design v2 terminal colors (`tbg`/`tfg`) with the brand orange cursor over the default ANSI palette. */
    val Dark: TerminalTheme =
        FakeTerminalEngine.DEFAULT_THEME.copy(
            foreground = 0xFFD7DEDB.toInt(),
            background = 0xFF0A0E0D.toInt(),
            cursor = 0xFFFF7A3D.toInt(),
        )

    /** Design v2 light terminal colors (`tbg`/`tfg`/`tcur`). */
    val Light: TerminalTheme =
        TerminalTheme(
            foreground = 0xFF1B1F1E.toInt(),
            background = 0xFFFBFCFB.toInt(),
            cursor = 0xFFE0560F.toInt(),
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

    private val SOLARIZED_PALETTE =
        intArrayOf(
            0xFF073642.toInt(),
            0xFFDC322F.toInt(),
            0xFF859900.toInt(),
            0xFFB58900.toInt(),
            0xFF268BD2.toInt(),
            0xFFD33682.toInt(),
            0xFF2AA198.toInt(),
            0xFFEEE8D5.toInt(),
            0xFF002B36.toInt(),
            0xFFCB4B16.toInt(),
            0xFF586E75.toInt(),
            0xFF657B83.toInt(),
            0xFF839496.toInt(),
            0xFF6C71C4.toInt(),
            0xFF93A1A1.toInt(),
            0xFFFDF6E3.toInt(),
        )

    /** Ethan Schoonover's Solarized (MIT), light variant; ANSI mapping as in the reference terminal ports. */
    val SolarizedLight: TerminalTheme =
        TerminalTheme(
            foreground = 0xFF657B83.toInt(),
            background = 0xFFFDF6E3.toInt(),
            cursor = 0xFF586E75.toInt(),
            palette = SOLARIZED_PALETTE,
        )

    /** Solarized dark. */
    val SolarizedDark: TerminalTheme =
        TerminalTheme(
            foreground = 0xFF839496.toInt(),
            background = 0xFF002B36.toInt(),
            cursor = 0xFF93A1A1.toInt(),
            palette = SOLARIZED_PALETTE,
        )

    /**
     * Pure black with white text (21:1) and ANSI colors that all reach at least 7:1 against black
     * (WCAG AAA), except color 0 which is black by definition.
     */
    val HighContrastDark: TerminalTheme =
        TerminalTheme(
            foreground = 0xFFFFFFFF.toInt(),
            background = 0xFF000000.toInt(),
            cursor = 0xFFFFFF00.toInt(),
            palette =
                intArrayOf(
                    0xFF000000.toInt(),
                    0xFFFF6B6B.toInt(),
                    0xFF5FFF5F.toInt(),
                    0xFFFFFF55.toInt(),
                    0xFF8AAEFF.toInt(),
                    0xFFFF79FF.toInt(),
                    0xFF55FFFF.toInt(),
                    0xFFE5E5E5.toInt(),
                    0xFF999999.toInt(),
                    0xFFFF8F8F.toInt(),
                    0xFF8FFF8F.toInt(),
                    0xFFFFFF99.toInt(),
                    0xFFA5C0FF.toInt(),
                    0xFFFFA5FF.toInt(),
                    0xFF99FFFF.toInt(),
                    0xFFFFFFFF.toInt(),
                ),
        )

    fun forDarkMode(dark: Boolean): TerminalTheme = if (dark) Dark else Light

    /** The theme for a user's [scheme]; [appDark] resolves [TerminalColorScheme.FollowApp]. */
    fun forScheme(
        scheme: TerminalColorScheme,
        appDark: Boolean,
    ): TerminalTheme =
        when (scheme) {
            TerminalColorScheme.FollowApp -> forDarkMode(appDark)
            TerminalColorScheme.Light -> Light
            TerminalColorScheme.Dark -> Dark
            TerminalColorScheme.SolarizedLight -> SolarizedLight
            TerminalColorScheme.SolarizedDark -> SolarizedDark
            TerminalColorScheme.HighContrastDark -> HighContrastDark
        }
}
