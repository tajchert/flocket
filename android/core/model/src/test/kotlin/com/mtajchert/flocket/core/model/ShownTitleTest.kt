package com.mtajchert.flocket.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ShownTitleTest {
    @Test
    fun `a title's spinner is not part of it`() {
        assertEquals("Fix the tests", shownTitle("✳ Fix the tests")) // Claude Code's
        assertEquals("working", shownTitle("⠋ working")) // braille
        assertEquals("two of them", shownTitle("◐ ✳  two of them"))
        assertEquals("~/Projects/herdr", shownTitle("~/Projects/herdr")) // a folder keeps its ~
        assertEquals("π - pi", shownTitle("π - pi")) // a letter is no spinner
        assertEquals("✳", shownTitle("✳")) // nothing after it: kept
        assertEquals("✓ build", shownTitle("✓ build")) // a status icon stays
        assertEquals("✗ build", shownTitle("✗ build"))
    }

    @Test
    fun `every frame of a spinning title reads the same`() {
        val frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏".map { "$it Refactor the parser" } + "·✢✳✶✻✽".map { "$it Refactor the parser" }
        assertEquals(setOf("Refactor the parser"), frames.map(::shownTitle).toSet())
    }
}
