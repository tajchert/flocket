package com.mtajchert.flocket.core.terminal.controller

import com.mtajchert.flocket.core.terminal.engine.ScreenSnapshot

/**
 * Something the renderer can pull the latest screen from. [TerminalController] implements it;
 * previews and screenshot tests use [StaticFrameSource].
 */
interface TerminalFrameSource {
    /**
     * Copy every row that changed since the last call into [target] (resizing it if needed) and
     * OR the changed rows into `target.dirtyRows`. Returns true if anything changed. Called on
     * the UI thread; must be cheap (row copies under a short lock, no allocation in steady state).
     */
    fun acquireFrame(target: ScreenSnapshot): Boolean

    /**
     * Smooth scrolling needs the row just above the viewport (it is partly visible while the view
     * sits between two rows). While [wanted], the source keeps one ready; it costs nothing otherwise.
     */
    fun setRowAboveWanted(wanted: Boolean) = Unit

    /**
     * Copy the row above the viewport of the latest frame into [target] if it changed since the
     * last call with this target. Returns true if [target] changed. `target.id` is -1 when there
     * is none (no scrollback above, or not [setRowAboveWanted]).
     */
    fun acquireRowAbove(target: RowSnapshot): Boolean = false

    /**
     * [acquireFrame] and [acquireRowAbove] together, so the row always belongs to the frame (sources
     * that publish both override this under one lock). Returns [FRAME_CHANGED] | [ROW_ABOVE_CHANGED].
     */
    fun acquire(
        target: ScreenSnapshot,
        rowAbove: RowSnapshot,
    ): Int =
        (if (acquireFrame(target)) FRAME_CHANGED else 0) or (if (acquireRowAbove(rowAbove)) ROW_ABOVE_CHANGED else 0)

    companion object {
        const val FRAME_CHANGED = 1
        const val ROW_ABOVE_CHANGED = 2
    }
}

/** One screen row outside the viewport, `id` in [ScreenSnapshot.historyBase] space (-1 = none). */
class RowSnapshot {
    val cells = ScreenSnapshot(1, 1)
    var id: Long = NONE
        internal set
    internal var version = -1

    companion object {
        const val NONE = -1L
    }
}

/** A fixed screen (previews, screenshot tests). */
class StaticFrameSource(
    private val snapshot: ScreenSnapshot,
) : TerminalFrameSource {
    private var delivered: ScreenSnapshot? = null

    override fun acquireFrame(target: ScreenSnapshot): Boolean {
        if (delivered === target) return false
        delivered = target
        val all = BooleanArray(snapshot.rows) { true }
        copyRows(snapshot, all, target, force = true)
        return true
    }
}

/**
 * Copies rows flagged in [dirty] from [src] to [dst] (both must have equal size unless [force],
 * which resizes [dst] and copies everything). Header fields are always copied. Marks the copied
 * rows dirty in [dst]. Allocation-free when sizes match and there are no graphemes.
 */
internal fun copyRows(
    src: ScreenSnapshot,
    dirty: BooleanArray,
    dst: ScreenSnapshot,
    force: Boolean,
): Boolean {
    val resized = dst.cols != src.cols || dst.rows != src.rows
    dst.resize(src.cols, src.rows)
    val cols = src.cols
    var any = resized
    val all = force || resized
    for (r in 0 until src.rows) {
        if (!all && !dirty[r]) continue
        val from = r * cols
        System.arraycopy(src.codepoints, from, dst.codepoints, from, cols)
        System.arraycopy(src.foreground, from, dst.foreground, from, cols)
        System.arraycopy(src.background, from, dst.background, from, cols)
        System.arraycopy(src.flags, from, dst.flags, from, cols)
        dst.dirtyRows[r] = true
        any = true
    }
    if (any && (src.graphemes.isNotEmpty() || dst.graphemes.isNotEmpty())) {
        if (all) {
            dst.graphemes.clear()
            dst.graphemes.putAll(src.graphemes)
        } else {
            dst.graphemes.keys.removeAll { dirty[it / cols] }
            for ((k, v) in src.graphemes) if (dirty[k / cols]) dst.graphemes[k] = v
        }
    }
    if (dst.cursorCol != src.cursorCol ||
        dst.cursorRow != src.cursorRow ||
        dst.cursorVisible != src.cursorVisible ||
        dst.cursorStyle != src.cursorStyle ||
        dst.viewportOffset != src.viewportOffset ||
        dst.scrollbackRows != src.scrollbackRows ||
        dst.historyBase != src.historyBase ||
        dst.altScreen != src.altScreen
    ) {
        any = true
    }
    dst.cursorCol = src.cursorCol
    dst.cursorRow = src.cursorRow
    dst.cursorVisible = src.cursorVisible
    dst.cursorStyle = src.cursorStyle
    dst.scrollbackRows = src.scrollbackRows
    dst.viewportOffset = src.viewportOffset
    dst.historyBase = src.historyBase
    dst.altScreen = src.altScreen
    dst.title = src.title
    return any
}
