package com.mtajchert.flocket.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shape definitions for the Flocket app.
 *
 * ## Material 3 Shape Scale
 *
 * Material 3 uses a shape scale with these sizes:
 * - Extra Small (4dp): Chips, small components
 * - Small (8dp): Buttons, text fields
 * - Medium (12dp): Cards, dialogs
 * - Large (16dp): Bottom sheets, navigation drawers
 * - Extra Large (28dp): Large dialogs, full-screen elements
 *
 * ## Usage
 *
 * Shapes are automatically applied to Material 3 components.
 * For custom shapes, access through MaterialTheme:
 *
 * ```kotlin
 * Box(
 *     modifier = Modifier
 *         .clip(MaterialTheme.shapes.medium)
 *         .background(MaterialTheme.colorScheme.surface)
 * )
 * ```
 */
val FlocketShapes =
    Shapes(
        // Extra small - for very small components like chips
        extraSmall = RoundedCornerShape(4.dp),
        // Small - for buttons, small cards
        small = RoundedCornerShape(8.dp),
        // Medium - for cards, dialogs
        medium = RoundedCornerShape(12.dp),
        // Large - for bottom sheets, navigation drawers
        large = RoundedCornerShape(16.dp),
        // Extra large - for large dialogs, full-screen modals
        extraLarge = RoundedCornerShape(28.dp),
    )

/** Named corner shapes from the design (design v2 shape decisions). */
object FlocketCorners {
    /** Filter chips, reply chips, extra keys. */
    val Chip = RoundedCornerShape(8.dp)

    /** Agent status pill. */
    val StatusPill = RoundedCornerShape(6.dp)

    /** Host status pill (dot + label). */
    val HostPill = RoundedCornerShape(12.dp)

    /** List cards. */
    val Card = RoundedCornerShape(16.dp)

    /** Large cards, terminal previews. */
    val CardLarge = RoundedCornerShape(20.dp)

    /** Bottom sheets (top corners only). */
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** Floating action buttons. */
    val Fab = RoundedCornerShape(16.dp)

    /** Corner radius of an agent tile of the given size (about 28%). */
    fun tileRadius(size: Dp) = size * 0.28f
}
