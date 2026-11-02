package com.mtajchert.flocket.navigation

import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import org.junit.Assert.assertEquals
import org.junit.Test

class PaneDirectiveTest {
    private fun window(
        widthDp: Float,
        heightDp: Float,
    ) = WindowAdaptiveInfo(WindowSizeClass.compute(widthDp, heightDp), Posture())

    @Test
    fun `a phone shows one pane`() {
        assertEquals(1, paneScaffoldDirective(window(412f, 892f)).maxHorizontalPartitions)
    }

    @Test
    fun `an unfolded foldable shows list and detail side by side in both orientations`() {
        // Galaxy Z Fold 6 inner screen at the default display size: medium width either way.
        assertEquals(2, paneScaffoldDirective(window(707f, 823f)).maxHorizontalPartitions)
        assertEquals(2, paneScaffoldDirective(window(823f, 707f)).maxHorizontalPartitions)
    }

    @Test
    fun `a tablet shows two panes with the wider sidebar`() {
        val directive = paneScaffoldDirective(window(1280f, 800f))
        assertEquals(2, directive.maxHorizontalPartitions)
        assertEquals(300.dp, directive.defaultPanePreferredWidth)
        assertEquals(0.dp, directive.horizontalPartitionSpacerSize)
    }

    @Test
    fun `a medium window uses the narrower sidebar`() {
        assertEquals(280.dp, paneScaffoldDirective(window(707f, 823f)).defaultPanePreferredWidth)
    }
}
