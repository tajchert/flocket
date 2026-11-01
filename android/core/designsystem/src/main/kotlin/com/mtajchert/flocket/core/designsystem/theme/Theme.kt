package com.mtajchert.flocket.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Light color scheme for the app.
 *
 * Uses the teal palette as primary color family.
 */
private val LightColorScheme =
    lightColorScheme(
        primary = Teal40,
        onPrimary = Gray99,
        primaryContainer = Teal90,
        onPrimaryContainer = Teal10,
        secondary = Sage40,
        onSecondary = Gray99,
        secondaryContainer = Sage90,
        onSecondaryContainer = Sage10,
        tertiary = Slate40,
        onTertiary = Gray99,
        tertiaryContainer = Slate90,
        onTertiaryContainer = Slate10,
        error = Red40,
        onError = Gray99,
        errorContainer = Red90,
        onErrorContainer = Red10,
        background = Gray99,
        onBackground = Gray10,
        surface = Gray99,
        onSurface = Gray10,
        surfaceVariant = GrayVariant90,
        onSurfaceVariant = GrayVariant30,
        outline = GrayVariant50,
        outlineVariant = GrayVariant80,
    )

/**
 * Dark color scheme for the app.
 *
 * Uses lighter tones of the color palette for better contrast.
 */
private val DarkColorScheme =
    darkColorScheme(
        primary = Teal80,
        onPrimary = Teal20,
        primaryContainer = Teal30,
        onPrimaryContainer = Teal90,
        secondary = Sage80,
        onSecondary = Sage20,
        secondaryContainer = Sage30,
        onSecondaryContainer = Sage90,
        tertiary = Slate80,
        onTertiary = Slate20,
        tertiaryContainer = Slate30,
        onTertiaryContainer = Slate90,
        error = Red80,
        onError = Red20,
        errorContainer = Red30,
        onErrorContainer = Red90,
        background = Gray10,
        onBackground = Gray90,
        surface = Gray10,
        onSurface = Gray90,
        surfaceVariant = GrayVariant30,
        onSurfaceVariant = GrayVariant80,
        outline = GrayVariant60,
        outlineVariant = GrayVariant30,
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FlocketTypography,
        shapes = FlocketShapes,
        content = content,
    )
}
