package com.mtajchert.flocket.navigation

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavEntryDecorator
import com.mtajchert.flocket.core.ui.nav.LocalPaneLayout
import com.mtajchert.flocket.core.ui.nav.PaneCard
import com.mtajchert.flocket.core.ui.nav.PanePosition
import com.mtajchert.flocket.core.ui.nav.PaneRole

/**
 * Draws the panes of the list-detail scene as rounded cards (design v2 tablet layout, [PaneCard]) while
 * [com.mtajchert.flocket.core.ui.nav.PaneLayout.paneCards] holds, for entries that declare a [PaneRole]. Entries shown alone (phones, focus mode,
 * compact height, non-list-detail routes) render unchanged: only the list-detail scene provides
 * [LocalListDetailSceneScope].
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun <T : Any> rememberPaneCardEntryDecorator(): NavEntryDecorator<T> =
    remember {
        NavEntryDecorator { entry ->
            val role = PaneRole.of(entry.metadata)
            val scene = LocalListDetailSceneScope.current
            if (role == null || scene == null) {
                entry.Content()
            } else {
                // One composition shape whether or not the card shows, so toggling it keeps the pane's state.
                PaneCard(
                    position = panePosition(role, scene.scaffoldTransitionScope.scaffoldStateTransition.targetState),
                    role = role,
                    enabled = LocalPaneLayout.current.paneCards,
                ) { entry.Content() }
            }
        }
    }

/**
 * Where a pane of [role] sits given the scaffold [value]. A pane with no expanded neighbour is [PanePosition.Alone].
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal fun panePosition(
    role: PaneRole,
    value: ThreePaneScaffoldValue,
): PanePosition {
    val list = value[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Expanded
    val detail = value[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded
    return when (role) {
        PaneRole.List -> if (detail) PanePosition.Leading else PanePosition.Alone
        PaneRole.Detail -> if (list) PanePosition.Trailing else PanePosition.Alone
    }
}
