package com.mtajchert.flocket.core.terminal.ui

import kotlin.math.ceil

/**
 * Pixel-smooth scrollback position, kept by [TerminalView] (pure, JVM-tested).
 *
 * Positions are row ids (`historyBase + screen row`, see `ScreenSnapshot.historyBase`), so a view
 * scrolled up into history stays on the same content while output arrives. [top] is the fractional
 * id of the row at the top edge of the view. The engine viewport sits on whole rows: the view asks for
 * `ceil(top)` and draws that viewport [shiftRows] lower, revealing part of the row above it.
 *
 * Live (following the bottom) is the default; the fractional part only exists while scrolled up.
 * The engine lags one or two frames behind requests: [shiftRows] never asks for a row it does not
 * have, so a fast fling moves in whole rows until it slows down.
 */
internal class SmoothScroll {
    /** Following the bottom: new output scrolls the view. */
    var live = true
        private set

    /** Fractional id of the row at the top edge of the view. */
    var top = 0.0
        private set

    /** Finger down or fling running: frames do not override [top]. */
    var interacting = false

    private var oldest = 0L
    private var bottomTop = 0L
    private var actualTop = 0L
    private var requested = NONE
    private var pendingFrames = 0

    /** True when there is scrollback to scroll into (false on the alternate screen). */
    val canScroll: Boolean get() = bottomTop > oldest

    /** Id of the viewport's top row in the latest frame. */
    val frameTop: Long get() = actualTop

    /** Feed every acquired frame's header. */
    fun onFrame(
        historyBase: Long,
        scrollbackRows: Int,
        viewportOffset: Int,
    ) {
        val previousBottom = bottomTop
        oldest = historyBase
        bottomTop = historyBase + scrollbackRows
        actualTop = bottomTop - viewportOffset
        if (requested != NONE && (actualTop == requested || ++pendingFrames > MAX_PENDING_FRAMES)) requested = NONE
        if (scrollbackRows == 0) {
            live = true
            requested = NONE
        } else if (!interacting && requested == NONE) {
            if (viewportOffset == 0) {
                // Output arrived, or input snapped the engine to the bottom. Less than a row above
                // the bottom with nothing new: keep the fraction.
                if (bottomTop != previousBottom || ceil(top).toLong() != actualTop) live = true
            } else if (live || ceil(top).toLong() != actualTop) {
                live = false // moved by someone else: adopt the engine's position
                top = actualTop.toDouble()
            }
        }
        if (live) top = bottomTop.toDouble()
        top = top.coerceIn(oldest.toDouble(), bottomTop.toDouble())
    }

    /**
     * Move by [rows] (positive = toward newer output). Returns the top row id to request from the
     * engine, or null when the engine already shows (or was asked for) that row.
     */
    fun move(rows: Double): Long? {
        if (!canScroll || rows == 0.0) return null
        top = (top + rows).coerceIn(oldest.toDouble(), bottomTop.toDouble())
        live = top >= bottomTop
        val want = ceil(top).toLong()
        val current = if (requested != NONE) requested else actualTop
        if (want == current) return null
        requested = want
        pendingFrames = 0
        return want
    }

    /** True when [top] reached the oldest row or the bottom (a fling can stop). */
    val atEdge: Boolean get() = live || top <= oldest

    /**
     * How far (rows, 0..1) to draw the frame's viewport below its natural position; above 0 the
     * row above the viewport ([frameTop] - 1) is partly visible.
     */
    fun shiftRows(): Float = if (live) 0f else (actualTop - top).coerceIn(0.0, 1.0).toFloat()

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val MAX_PENDING_FRAMES = 4
    }
}
