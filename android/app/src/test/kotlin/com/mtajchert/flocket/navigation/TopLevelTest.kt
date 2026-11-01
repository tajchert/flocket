package com.mtajchert.flocket.navigation

import com.mtajchert.flocket.feature.onboarding.api.OnboardingRoute
import com.mtajchert.flocket.feature.terminal.api.TerminalRoute
import com.mtajchert.flocket.feature.terminals.api.TerminalsRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopLevelTest {
    private val terminal = TerminalRoute(hostId = "h1", address = "t1")

    @Test
    fun `terminal hides the navigation bar on single-pane windows only`() {
        assertTrue(terminal.isFullScreen(singlePane = true))
        assertFalse(terminal.isFullScreen(singlePane = false))
    }

    @Test
    fun `terminal also hides the rail in focus mode and on compact height`() {
        assertTrue(terminal.isFullScreen(singlePane = false, focusMode = true))
        assertTrue(terminal.isFullScreen(singlePane = false, compactHeight = true))
        assertFalse(terminal.isFullScreen(singlePane = false, focusMode = false, compactHeight = false))
    }

    @Test
    fun `focus mode and compact height never hide the rail on a list`() {
        assertFalse(TerminalsRoute.isFullScreen(singlePane = false, focusMode = true, compactHeight = true))
    }

    @Test
    fun `top-level lists keep the navigation bar, onboarding never shows it`() {
        assertFalse(TerminalsRoute.isFullScreen(singlePane = true))
        assertTrue(OnboardingRoute.isFullScreen(singlePane = false))
    }
}
