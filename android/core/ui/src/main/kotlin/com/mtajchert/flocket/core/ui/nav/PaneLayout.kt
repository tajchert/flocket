// The two locals below are the reviewed exception to the compose CompositionLocal allowlist.
@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package com.mtajchert.flocket.core.ui.nav

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How `:app` is laying out the list-detail panes right now. Features read it to adapt density and
 * chrome; they never measure the window themselves (docs/ADAPTIVE_LAYOUTS.md).
 */
@Immutable
data class PaneLayout(
    /** List and detail can be shown side by side (medium/expanded width). */
    val multiPane: Boolean = false,
    /** In a multi-pane window: the list pane (sidebar) is showing. False in focus mode. */
    val sidebarVisible: Boolean = true,
    /** Compact window height, e.g. a phone in landscape: chrome should be minimal. */
    val compactHeight: Boolean = false,
)

val LocalPaneLayout = staticCompositionLocalOf { PaneLayout() }

/** Shows or hides the sidebar (focus mode) in a multi-pane window. No-op on single-pane windows. */
fun interface SidebarToggle {
    fun setSidebarVisible(visible: Boolean)
}

val LocalSidebarToggle = staticCompositionLocalOf { SidebarToggle { } }
