package com.mtajchert.flocket.navigation

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import com.mtajchert.flocket.core.ui.nav.PaneLayout
import com.mtajchert.flocket.core.ui.nav.PanePosition
import com.mtajchert.flocket.core.ui.nav.PaneRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
class PaneCardsTest {
    // List-detail: primary = detail, secondary = list, tertiary = extra.
    private val both =
        ThreePaneScaffoldValue(
            primary = PaneAdaptedValue.Expanded,
            secondary = PaneAdaptedValue.Expanded,
            tertiary = PaneAdaptedValue.Hidden,
        )
    private val listOnly =
        ThreePaneScaffoldValue(
            primary = PaneAdaptedValue.Hidden,
            secondary = PaneAdaptedValue.Expanded,
            tertiary = PaneAdaptedValue.Hidden,
        )
    private val detailOnly =
        ThreePaneScaffoldValue(
            primary = PaneAdaptedValue.Expanded,
            secondary = PaneAdaptedValue.Hidden,
            tertiary = PaneAdaptedValue.Hidden,
        )

    @Test
    fun `list and detail side by side round their outer corners`() {
        assertEquals(PanePosition.Leading, panePosition(PaneRole.List, both))
        assertEquals(PanePosition.Trailing, panePosition(PaneRole.Detail, both))
    }

    @Test
    fun `a pane without a neighbour rounds every corner`() {
        assertEquals(PanePosition.Alone, panePosition(PaneRole.List, listOnly))
        assertEquals(PanePosition.Alone, panePosition(PaneRole.Detail, detailOnly))
    }

    @Test
    fun `panes are cards only on a two-pane window with the sidebar up and room to spare`() {
        assertTrue(PaneLayout(multiPane = true).paneCards)
        assertFalse(PaneLayout(multiPane = false).paneCards)
        assertFalse(PaneLayout(multiPane = true, sidebarVisible = false).paneCards)
        assertFalse(PaneLayout(multiPane = true, compactHeight = true).paneCards)
    }
}
