package com.mtajchert.flocket.core.ui.nav

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.dp

/**
 * Which list-detail pane an entry is, for `:app`'s pane cards. Add [metadata] next to the
 * `ListDetailSceneStrategy` metadata: `metadata = ListDetailSceneStrategy.listPane() + PaneRole.List.metadata()`.
 */
enum class PaneRole {
    List,
    Detail,
    ;

    fun metadata(): Map<String, Any> = mapOf(KEY to this)

    companion object {
        private const val KEY = "flocket.paneRole"

        /** The role an entry declared with [metadata], or null. */
        fun of(metadata: Map<String, Any>): PaneRole? = metadata[KEY] as? PaneRole
    }
}

/** A pane's place in the list-detail row; picks the rounded corners and the window edges it pads for. */
enum class PanePosition {
    /** The list: beside the rail, a detail pane after it. Leading corners round. */
    Leading,

    /** The detail: after the list, at the window's end. Trailing corners round. */
    Trailing,

    /** The only card in the row (no adjacent pane). All corners round. */
    Alone,
}

/**
 * Draws a list-detail pane as a rounded card on the window background (design v2 tablet layout): an 8dp
 * margin from the window edges it touches, 24dp corners on its outer edges. The card pads for the system
 * bars on those edges itself, so the screen inside must not (its own inset handling sees them consumed).
 *
 * The list card is `surfaceContainerLow`, and inside it the surface tones step up one level ([listPaneColors])
 * so screens that draw `background`/`surface` blend into the card and their `surfaceContainerLow` cards stay
 * visible. A detail card keeps the screen's own colors unless [containerColor] is given.
 *
 * With [enabled] false it draws nothing and only hosts [content]. `:app` applies it to list-detail entries
 * ([LocalPaneLayout] `paneCards`); features only use it through [DetailPanePlaceholder].
 */
@Composable
fun PaneCard(
    position: PanePosition,
    role: PaneRole,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val list = role == PaneRole.List
    val outerEnd = position != PanePosition.Leading
    // Margins and corners ease in and out (focus mode, a pane joining or leaving the row) instead of snapping.
    val margin by animateDpAsState(if (enabled) PaneMargin else 0.dp, label = "paneMargin")
    val endMargin by animateDpAsState(if (enabled && outerEnd) PaneMargin else 0.dp, label = "paneEndMargin")
    val startCorner by animateDpAsState(
        if (enabled && position != PanePosition.Trailing) PaneCorner else 0.dp,
        label = "paneStartCorner",
    )
    val endCorner by animateDpAsState(
        if (enabled && position != PanePosition.Leading) PaneCorner else 0.dp,
        label = "paneEndCorner",
    )
    val drawn = enabled || margin > 0.dp
    val cardModifier =
        if (drawn) {
            val color = if (list) scheme.surfaceContainerLow else containerColor
            // Once disabled the screen inside pads for the system bars itself; the card only eases its margins out.
            val insets =
                if (enabled) {
                    val sides =
                        if (outerEnd) WindowInsetsSides.Vertical + WindowInsetsSides.End else WindowInsetsSides.Vertical
                    Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(sides))
                } else {
                    Modifier
                }
            Modifier
                .fillMaxSize()
                .then(insets)
                .padding(top = margin, bottom = margin, end = endMargin)
                .clip(
                    RoundedCornerShape(
                        topStart = startCorner,
                        bottomStart = startCorner,
                        topEnd = endCorner,
                        bottomEnd = endCorner,
                    ),
                ).then(if (color.isSpecified) Modifier.background(color) else Modifier)
        } else {
            Modifier
        }
    // The same tree whether or not the card shows, so switching keeps the content's state.
    Box(modifier.then(cardModifier)) {
        MaterialTheme(colorScheme = if (drawn && list) scheme.listPaneColors() else scheme, content = content)
    }
}

/** The detail pane's "nothing selected" hint, drawn as a detail card when panes are cards. */
@Composable
fun DetailPanePlaceholder(
    text: String,
    modifier: Modifier = Modifier,
) {
    PaneCard(
        PanePosition.Trailing,
        PaneRole.Detail,
        modifier = modifier,
        enabled = LocalPaneLayout.current.paneCards,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(
                text,
                Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Surface tones inside the list card: one step up, so the card is the screen's background. */
fun ColorScheme.listPaneColors(): ColorScheme =
    copy(
        background = surfaceContainerLow,
        surface = surfaceContainerLow,
        surfaceContainerLow = surfaceContainer,
        surfaceContainer = surfaceContainerHigh,
        surfaceContainerHigh = surfaceContainerHighest,
    )

private val PaneMargin = 8.dp
private val PaneCorner = 24.dp
