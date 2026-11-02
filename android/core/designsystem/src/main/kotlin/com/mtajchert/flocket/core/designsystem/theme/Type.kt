package com.mtajchert.flocket.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mtajchert.flocket.core.designsystem.R

/**
 * Typography scale for the Flocket app.
 *
 * ## Material 3 Type Scale
 *
 * Material 3 defines a type scale with these roles:
 * - Display: Large, impactful headlines
 * - Headline: Section headers
 * - Title: Smaller headers, dialog titles
 * - Body: Paragraph text
 * - Label: Buttons, input labels
 *
 * ## Customization
 *
 * This uses the system default font (FontFamily.Default).
 * To use a custom font:
 *
 * ```kotlin
 * private val MyFontFamily = FontFamily(
 *     Font(R.font.my_font_regular, FontWeight.Normal),
 *     Font(R.font.my_font_medium, FontWeight.Medium),
 *     Font(R.font.my_font_bold, FontWeight.Bold),
 * )
 *
 * val FlocketTypography = Typography(
 *     displayLarge = TextStyle(
 *         fontFamily = MyFontFamily,
 *         ...
 *     ),
 *     ...
 * )
 * ```
 *
 * ## Usage
 *
 * Access typography through MaterialTheme:
 *
 * ```kotlin
 * Text(
 *     text = "Hello",
 *     style = MaterialTheme.typography.headlineMedium,
 * )
 * ```
 */
val FlocketTypography =
    Typography(
        // Display styles - for large, impactful text
        displayLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 57.sp,
                lineHeight = 64.sp,
                letterSpacing = (-0.25).sp,
            ),
        displayMedium =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 45.sp,
                lineHeight = 52.sp,
                letterSpacing = 0.sp,
            ),
        displaySmall =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 36.sp,
                lineHeight = 44.sp,
                letterSpacing = 0.sp,
            ),
        // Headline styles - for section headers
        headlineLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 32.sp,
                lineHeight = 40.sp,
                letterSpacing = 0.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                letterSpacing = 0.sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                letterSpacing = 0.sp,
            ),
        // Title styles - for smaller headers
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = 0.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        // Body styles - for paragraph text
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.25.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.4.sp,
            ),
        // Label styles - for buttons, input labels
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
    )

/**
 * JetBrains Mono (OFL-1.1, see THIRD_PARTY_NOTICES.md): the one mono family, shared by the terminal
 * renderer and by mono UI text (previews, addresses, fingerprints, commands, key caps). The
 * SemiBold weight maps to the bold file.
 */
val FlocketMonoFamily =
    FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_regular, FontWeight.Medium),
        Font(R.font.jetbrains_mono_bold, FontWeight.SemiBold),
        Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
        Font(R.font.jetbrains_mono_italic, FontWeight.Normal, FontStyle.Italic),
        Font(R.font.jetbrains_mono_bold_italic, FontWeight.Bold, FontStyle.Italic),
    )

/** Mono text styles for UI text. Combine with `.copy(color = ...)` as needed. */
object FlocketMono {
    /** 11sp semibold: key caps, tiny tags. */
    val label =
        TextStyle(
            fontFamily = FlocketMonoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

    /** 12sp: addresses, fingerprints, terminal previews in list rows. */
    val small =
        TextStyle(
            fontFamily = FlocketMonoFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )

    /** 13sp: commands and longer mono text. */
    val body =
        TextStyle(
            fontFamily = FlocketMonoFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
}
