@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package com.mtajchert.flocket.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Design v2 palette ("Inbox direction"). These constants are internal: composables read
 * `MaterialTheme.colorScheme` (Material roles) and `MaterialTheme.flocketColors` (fixed semantic
 * colors), never the raw values. Names follow the design tokens: bg, surf1..3, on, onv, outline, ...
 */

// ---- Light ----
internal val LightBg = Color(0xFFF6FAF8)
internal val LightSurf1 = Color(0xFFEFF5F3)
internal val LightSurf2 = Color(0xFFE8EFEC)
internal val LightSurf3 = Color(0xFFDDE6E3)
internal val LightDim = Color(0xFFD6DEDB)
internal val LightOn = Color(0xFF161D1B)
internal val LightOnVariant = Color(0xFF3F4946)
internal val LightOutline = Color(0xFF6F7976)
internal val LightOutlineVariant = Color(0xFFC9D2CF)
internal val LightPrimary = Color(0xFF006A60)
internal val LightPrimaryContainer = Color(0xFFA6EFE3)
internal val LightOnPrimaryContainer = Color(0xFF00201C)
internal val LightSecondary = Color(0xFF4A635F)
internal val LightSecondaryContainer = Color(0xFFCCE8E2)
internal val LightOnSecondaryContainer = Color(0xFF05201C)
internal val LightTertiary = Color(0xFF3B6379)
internal val LightTertiaryContainer = Color(0xFFC7E7FF)
internal val LightOnTertiaryContainer = Color(0xFF001E2E)
internal val LightError = Color(0xFFBA1A1A)
internal val LightErrorContainer = Color(0xFFFFDAD6)
internal val LightOnErrorContainer = Color(0xFF410002)
internal val LightInverse = Color(0xFF2B3230)
internal val LightOnInverse = Color(0xFFECF2EF)
internal val LightInversePrimary = Color(0xFF82D5C8)

// ---- Dark ----
internal val DarkBg = Color(0xFF0F1514)
internal val DarkSurf1 = Color(0xFF161D1C)
internal val DarkSurf2 = Color(0xFF1B2221)
internal val DarkSurf3 = Color(0xFF262D2C)
internal val DarkBright = Color(0xFF353C3B)
internal val DarkLowest = Color(0xFF0A0F0E)
internal val DarkOn = Color(0xFFDEE4E1)
internal val DarkOnVariant = Color(0xFFBEC9C5)
internal val DarkOutline = Color(0xFF899390)
internal val DarkOutlineVariant = Color(0xFF323A38)
internal val DarkPrimary = Color(0xFF82D5C8)
internal val DarkOnPrimary = Color(0xFF003731)
internal val DarkPrimaryContainer = Color(0xFF005048)
internal val DarkOnPrimaryContainer = Color(0xFFA6EFE3)
internal val DarkSecondary = Color(0xFFB1CCC5)
internal val DarkOnSecondary = Color(0xFF1C3530)
internal val DarkSecondaryContainer = Color(0xFF2F4A46)
internal val DarkOnSecondaryContainer = Color(0xFFCCE8E2)
internal val DarkTertiary = Color(0xFFA5CBE5)
internal val DarkOnTertiary = Color(0xFF053449)
internal val DarkTertiaryContainer = Color(0xFF214B61)
internal val DarkOnTertiaryContainer = Color(0xFFC7E7FF)
internal val DarkError = Color(0xFFFFB4AB)
internal val DarkOnError = Color(0xFF690005)
internal val DarkErrorContainer = Color(0xFF93000A)
internal val DarkOnErrorContainer = Color(0xFFFFDAD6)
internal val DarkInverse = Color(0xFFDEE4E1)
internal val DarkOnInverse = Color(0xFF2B3230)
internal val DarkInversePrimary = Color(0xFF006A60)

/**
 * Fixed semantic colors that do NOT come from dynamic color (a wallpaper must never turn "needs
 * you" blue). They follow light/dark only. Read them with [MaterialTheme.flocketColors]:
 *
 * ```kotlin
 * val c = MaterialTheme.flocketColors
 * Text("Needs you", color = c.attention)
 * ```
 *
 * Amber means "blocked on you"; red ([errorSolid], and the Material `error` family) means error only.
 * Never hard-code hex values in features.
 *
 * @property attention amber text/icon color on the normal background (design `att`).
 * @property attentionSolid saturated amber for badges and rings (`attsolid`).
 * @property attentionContainer amber pill background (`attc`).
 * @property onAttentionContainer text on [attentionContainer] (`onattc`).
 * @property errorSolid saturated red for badges (`errsolid`); error text uses `colorScheme.error`.
 * @property done green text/icon color (`done`).
 * @property doneContainer green pill background (`donec`).
 * @property onDoneContainer text on [doneContainer] (`ondonec`).
 * @property terminalBackground terminal preview background (`tbg`).
 * @property terminalForeground terminal preview text (`tfg`).
 * @property terminalDim dimmed terminal preview text (`tdim`).
 * @property key extra-key cap background (`key`).
 * @property keyboardBar background of the extra-keys / keyboard bar (`kbd`).
 */
@Immutable
data class FlocketColors(
    val attention: Color,
    val attentionSolid: Color,
    val attentionContainer: Color,
    val onAttentionContainer: Color,
    val errorSolid: Color,
    val done: Color,
    val doneContainer: Color,
    val onDoneContainer: Color,
    val terminalBackground: Color,
    val terminalForeground: Color,
    val terminalDim: Color,
    val key: Color,
    val keyboardBar: Color,
)

internal val LightFlocketColors =
    FlocketColors(
        attention = Color(0xFF8A4F00),
        attentionSolid = Color(0xFFE0560F),
        attentionContainer = Color(0xFFFFDCBD),
        onAttentionContainer = Color(0xFF2C1600),
        errorSolid = Color(0xFFC4161C),
        done = Color(0xFF3B6936),
        doneContainer = Color(0xFFC3EEB6),
        onDoneContainer = Color(0xFF052104),
        terminalBackground = Color(0xFFFBFCFB),
        terminalForeground = Color(0xFF1B1F1E),
        terminalDim = Color(0xFF6B7572),
        key = Color(0xFFFFFFFF),
        keyboardBar = Color(0xFFE2E8E5),
    )

internal val DarkFlocketColors =
    FlocketColors(
        attention = Color(0xFFFFB870),
        attentionSolid = Color(0xFFFF7A3D),
        attentionContainer = Color(0xFF6A3C00),
        onAttentionContainer = Color(0xFFFFDCBD),
        errorSolid = Color(0xFFFF5449),
        done = Color(0xFFA7D394),
        doneContainer = Color(0xFF24501F),
        onDoneContainer = Color(0xFFC3EEB6),
        terminalBackground = Color(0xFF0A0E0D),
        terminalForeground = Color(0xFFD7DEDB),
        terminalDim = Color(0xFF7F8A87),
        key = Color(0xFF343C3A),
        keyboardBar = Color(0xFF1A201F),
    )

internal val LocalFlocketColors = staticCompositionLocalOf { LightFlocketColors }

/** The fixed semantic colors of the current theme; see [FlocketColors]. */
val MaterialTheme.flocketColors: FlocketColors
    @Composable
    @ReadOnlyComposable
    get() = LocalFlocketColors.current
