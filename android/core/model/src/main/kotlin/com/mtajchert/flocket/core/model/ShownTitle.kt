package com.mtajchert.flocket.core.model

/** Frames agents spin, not status icons (✓ build stays): braille dots, Claude Code's stars and half circles. */
private fun isSpinnerGlyph(c: Char): Boolean =
    c in '⠀'..'⣿' ||
        c == '·' ||
        c == '✢' ||
        c == '✳' ||
        c == '✶' ||
        c == '✻' ||
        c == '✽' ||
        c in '◐'..'◓'

/**
 * A program title as it is shown: an agent's spinner in front of it (`✳ Fix the tests`, braille dots) is no
 * part of it. Flocket draws its own working indicator, and a title that spins would redraw (and jump) ten
 * times a second. A title that is only a glyph, or a glyph not followed by a space, is kept as it is.
 * Adapted from Herdr.app `shown_title` (`src/terminal.rs`, Apache-2.0).
 */
fun shownTitle(title: String): String {
    val rest = title.trimStart { isSpinnerGlyph(it) || it.isWhitespace() }
    val led = title.substring(0, title.length - rest.length)
    return if (rest.isEmpty() || led.none(::isSpinnerGlyph) || !led.last().isWhitespace()) title else rest
}
