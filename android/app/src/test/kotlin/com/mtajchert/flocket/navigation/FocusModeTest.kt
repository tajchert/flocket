package com.mtajchert.flocket.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusModeTest {
    @Test
    fun `focus mode needs a request, multiple panes and a terminal on top`() {
        assertTrue(isFocusMode(requested = true, multiPane = true, onTerminal = true))
        assertFalse(isFocusMode(requested = false, multiPane = true, onTerminal = true))
        assertFalse(isFocusMode(requested = true, multiPane = false, onTerminal = true))
        assertFalse(isFocusMode(requested = true, multiPane = true, onTerminal = false))
    }

    @Test
    fun `back in focus mode restores the sidebar instead of navigating`() {
        var requested = true
        var navigatedBack = 0

        fun back() = if (backLeavesFocus(requested)) requested = false else navigatedBack++

        back()
        assertFalse(requested)
        assertTrue(navigatedBack == 0)
        assertFalse(isFocusMode(requested, multiPane = true, onTerminal = true))
    }

    @Test
    fun `back without focus mode navigates back`() {
        var navigatedBack = 0
        if (backLeavesFocus(false)) error("unexpected") else navigatedBack++
        assertTrue(navigatedBack == 1)
    }

    @Test
    fun `focus resets when the window turns single-pane or the route stops being a terminal`() {
        assertTrue(shouldResetFocus(multiPane = false, onTerminal = true))
        assertTrue(shouldResetFocus(multiPane = true, onTerminal = false))
        assertFalse(shouldResetFocus(multiPane = true, onTerminal = true))
    }
}
