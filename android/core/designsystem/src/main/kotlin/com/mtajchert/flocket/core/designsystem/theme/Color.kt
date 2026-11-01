package com.mtajchert.flocket.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/*
 * Fallback palette used when dynamic color is unavailable (Android 11 and below) or turned off.
 *
 * Seed: a calm teal-green (#2F8F83). Tonal values follow the Material 3 tone ladder. These
 * constants are internal: composables read `MaterialTheme.colorScheme`, never these directly.
 */

// Primary - teal
internal val Teal10 = Color(0xFF00201C)
internal val Teal20 = Color(0xFF003731)
internal val Teal30 = Color(0xFF00504A)
internal val Teal40 = Color(0xFF1B6B63)
internal val Teal80 = Color(0xFF82D5CA)
internal val Teal90 = Color(0xFF9EF2E6)

// Secondary - grey green
internal val Sage10 = Color(0xFF05201C)
internal val Sage20 = Color(0xFF1C3530)
internal val Sage30 = Color(0xFF334B46)
internal val Sage40 = Color(0xFF4A635E)
internal val Sage80 = Color(0xFFB1CCC5)
internal val Sage90 = Color(0xFFCCE8E1)

// Tertiary - muted blue
internal val Slate10 = Color(0xFF001E2E)
internal val Slate20 = Color(0xFF053449)
internal val Slate30 = Color(0xFF214B61)
internal val Slate40 = Color(0xFF3B6379)
internal val Slate80 = Color(0xFFA5CBE5)
internal val Slate90 = Color(0xFFC7E7FF)

// Error - red
internal val Red10 = Color(0xFF410002)
internal val Red20 = Color(0xFF690005)
internal val Red30 = Color(0xFF93000A)
internal val Red40 = Color(0xFFBA1A1A)
internal val Red80 = Color(0xFFFFB4AB)
internal val Red90 = Color(0xFFFFDAD6)

// Neutral
internal val Gray10 = Color(0xFF191C1B)
internal val Gray90 = Color(0xFFE0E3E1)
internal val Gray99 = Color(0xFFFAFDFB)

// Neutral variant
internal val GrayVariant30 = Color(0xFF3F4946)
internal val GrayVariant50 = Color(0xFF6F7976)
internal val GrayVariant60 = Color(0xFF899390)
internal val GrayVariant80 = Color(0xFFBEC9C5)
internal val GrayVariant90 = Color(0xFFDAE5E1)
