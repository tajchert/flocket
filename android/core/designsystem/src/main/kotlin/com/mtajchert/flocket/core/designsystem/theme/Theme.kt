package com.mtajchert.flocket.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Light fallback scheme: the design v2 tokens (used when dynamic color is off or unavailable). */
private val LightColorScheme =
    lightColorScheme(
        primary = LightPrimary,
        onPrimary = Color.White,
        primaryContainer = LightPrimaryContainer,
        onPrimaryContainer = LightOnPrimaryContainer,
        secondary = LightSecondary,
        onSecondary = Color.White,
        secondaryContainer = LightSecondaryContainer,
        onSecondaryContainer = LightOnSecondaryContainer,
        tertiary = LightTertiary,
        onTertiary = Color.White,
        tertiaryContainer = LightTertiaryContainer,
        onTertiaryContainer = LightOnTertiaryContainer,
        error = LightError,
        onError = Color.White,
        errorContainer = LightErrorContainer,
        onErrorContainer = LightOnErrorContainer,
        background = LightBg,
        onBackground = LightOn,
        surface = LightBg,
        onSurface = LightOn,
        surfaceVariant = LightSurf3,
        onSurfaceVariant = LightOnVariant,
        outline = LightOutline,
        outlineVariant = LightOutlineVariant,
        inverseSurface = LightInverse,
        inverseOnSurface = LightOnInverse,
        inversePrimary = LightInversePrimary,
        scrim = Color.Black,
        surfaceTint = LightPrimary,
        surfaceBright = LightBg,
        surfaceDim = LightDim,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = LightSurf1,
        surfaceContainer = LightSurf2,
        surfaceContainerHigh = LightSurf3,
        surfaceContainerHighest = LightSurf3,
    )

/** Dark fallback scheme: the design v2 tokens. */
private val DarkColorScheme =
    darkColorScheme(
        primary = DarkPrimary,
        onPrimary = DarkOnPrimary,
        primaryContainer = DarkPrimaryContainer,
        onPrimaryContainer = DarkOnPrimaryContainer,
        secondary = DarkSecondary,
        onSecondary = DarkOnSecondary,
        secondaryContainer = DarkSecondaryContainer,
        onSecondaryContainer = DarkOnSecondaryContainer,
        tertiary = DarkTertiary,
        onTertiary = DarkOnTertiary,
        tertiaryContainer = DarkTertiaryContainer,
        onTertiaryContainer = DarkOnTertiaryContainer,
        error = DarkError,
        onError = DarkOnError,
        errorContainer = DarkErrorContainer,
        onErrorContainer = DarkOnErrorContainer,
        background = DarkBg,
        onBackground = DarkOn,
        surface = DarkBg,
        onSurface = DarkOn,
        surfaceVariant = DarkSurf3,
        onSurfaceVariant = DarkOnVariant,
        outline = DarkOutline,
        outlineVariant = DarkOutlineVariant,
        inverseSurface = DarkInverse,
        inverseOnSurface = DarkOnInverse,
        inversePrimary = DarkInversePrimary,
        scrim = Color.Black,
        surfaceTint = DarkPrimary,
        surfaceBright = DarkBright,
        surfaceDim = DarkBg,
        surfaceContainerLowest = DarkLowest,
        surfaceContainerLow = DarkSurf1,
        surfaceContainer = DarkSurf2,
        surfaceContainerHigh = DarkSurf3,
        surfaceContainerHighest = DarkSurf3,
    )

/**
 * Main theme composable for the Flocket app.
 *
 * ## Why Wrap MaterialTheme?
 *
 * 1. **Centralized Configuration**: Theme settings are in one place
 * 2. **Dynamic Colors Support**: Handles Android 12+ wallpaper-based colors
 * 3. **Consistent Defaults**: All screens use the same theme automatically
 *
 * ## Dynamic Colors
 *
 * On Android 12 (API 31) and above, the theme can use colors extracted
 * from the user's wallpaper. This is enabled by default but can be
 * disabled by setting [dynamicColor] to false.
 *
 * ## Semantic colors
 *
 * Status colors (attention, done, terminal preview...) are fixed and not dynamic; read them with
 * `MaterialTheme.flocketColors`. Mono text styles live in [FlocketMono].
 *
 * ## Usage
 *
 * ```kotlin
 * setContent {
 *     FlocketTheme {
 *         // Your app content
 *         MyScreen()
 *     }
 * }
 * ```
 *
 * @param darkTheme Whether to use dark theme. Defaults to system setting.
 * @param dynamicColor Whether to use Android 12+ dynamic colors. Defaults to true.
 * @param content The composable content to be themed.
 */
@Composable
fun FlocketTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            // Use dynamic colors on Android 12+ when enabled
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) {
                    dynamicDarkColorScheme(context)
                } else {
                    dynamicLightColorScheme(context)
                }
            }

            // Fall back to our custom color schemes
            darkTheme -> {
                DarkColorScheme
            }

            else -> {
                LightColorScheme
            }
        }

    CompositionLocalProvider(
        LocalFlocketColors provides if (darkTheme) DarkFlocketColors else LightFlocketColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FlocketTypography,
            shapes = FlocketShapes,
            content = content,
        )
    }
}
