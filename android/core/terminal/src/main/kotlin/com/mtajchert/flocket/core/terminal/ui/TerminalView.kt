package com.mtajchert.flocket.core.terminal.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.mtajchert.flocket.core.terminal.R
import com.mtajchert.flocket.core.terminal.controller.RowSnapshot
import com.mtajchert.flocket.core.terminal.controller.TerminalController
import com.mtajchert.flocket.core.terminal.controller.TerminalFrameSource
import com.mtajchert.flocket.core.terminal.controller.TerminalViewport
import com.mtajchert.flocket.core.terminal.engine.CellFlags
import com.mtajchert.flocket.core.terminal.engine.CursorStyle
import com.mtajchert.flocket.core.terminal.engine.ScreenSnapshot
import com.mtajchert.flocket.core.terminal.engine.TerminalTheme
import com.mtajchert.flocket.core.terminal.engine.textRange
import com.mtajchert.flocket.core.terminal.fake.FakeTerminalEngine
import com.mtajchert.flocket.core.terminal.input.KeyEventMapper
import com.mtajchert.flocket.core.terminal.input.TerminalInputConnection
import com.mtajchert.flocket.core.terminal.input.TerminalInputSink
import com.mtajchert.flocket.core.terminal.input.asInputSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The terminal surface as a hardware-accelerated [View] (hosted by `TerminalSurface` via
 * `AndroidView`). Why a View: per-row [RenderNode]s (re-record only dirty rows, replay the rest),
 * a direct [InputConnection] for the IME, and no recomposition on the frame path.
 *
 * Frame path: the controller's frame version changes -> [invalidate] -> [onDraw] pulls changed
 * rows into [render] (`acquireFrame`), re-records those rows' display lists and replays all rows,
 * then draws selection, cursor and IME composing text on top. Software canvases (layoutlib
 * screenshots, Robolectric) draw rows directly.
 *
 * Scrolling is pixel-smooth ([SmoothScroll]): between two rows the viewport is drawn shifted down and
 * the row above it (pulled from the source while scrolled) fills the gap. Selection ([TerminalSelection])
 * is anchored in row ids, with Material-style handles and the platform floating toolbar.
 */
@SuppressLint("ViewConstructor")
class TerminalView(
    context: Context,
) : View(context) {
    private val typefaces = TerminalTypefaces.get(context)
    private val renderer = TerminalRenderer(typefaces)
    private val render = ScreenSnapshot(1, 1)
    private var rowNodes: Array<RenderNode?> = emptyArray()
    private var rowValid = BooleanArray(0)
    private var hasGrapheme: BooleanArray? = null
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val density = resources.displayMetrics.density

    // Row above the viewport (smooth scrolling) and its display list.
    private val above = RowSnapshot()
    private var aboveNode: RenderNode? = null
    private var aboveValid = false
    private var aboveGrapheme: BooleanArray? = null
    private var aboveGraphemeBuf: BooleanArray? = null
    private val smooth = SmoothScroll()
    private var lastCols = 0
    private var lastAlt = false

    private var source: TerminalFrameSource? = null
    private var controller: TerminalController? = null
    private var sink: TerminalInputSink? = null
    private var frameJob: Job? = null

    var theme: TerminalTheme = FakeTerminalEngine.DEFAULT_THEME
        set(value) {
            if (field == value) return
            field = value
            invalidateAllRows()
        }

    /** Font size in sp (pinch-zoom changes it in Owner mode; persisted by the caller). */
    var fontSizeSp: Float = DEFAULT_FONT_SP
        set(value) {
            val v = value.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            if (field == v) return
            field = v
            applyFontSize()
        }

    var onFontSizeChange: (Float) -> Unit = {}

    /** Show the soft keyboard on tap (false when a hardware keyboard is the only input). */
    var showKeyboardOnTap: Boolean = true

    /** Draw the cursor as focused even without View focus (screenshots, previews). */
    var forceFocusedLook: Boolean = false

    /** Selection handle color (Material `primary`); the highlight is the same color, translucent. Null = theme cursor. */
    var selectionColor: Int? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val padPx = (PADDING_DP * density)
    private var composing: String? = null
    private var inputConnection: TerminalInputConnection? = null

    // Layout transform (content -> view): x' = tx + x * scale.
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var userZoom = 1f
    private var pinchScale = 1f
    private var panX = 0f

    // Selection: model in row ids, a pending visible-grid selection from [select], handle drag state.
    private val selection = TerminalSelection()
    private var pendingSelStart = -1
    private var pendingSelEnd = -1
    private var selecting = false // long-press drag extends the end
    private var dragHandle = HANDLE_NONE
    private var dragDx = 0f
    private var dragDy = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var autoScrolling = false
    private var actionMode: ActionMode? = null
    private val handlePath = Path()
    private val handleRect = RectF()
    private val handleRadii = FloatArray(8)
    private val handleRadius = HANDLE_RADIUS_DP * density
    private val handleTouch = HANDLE_TOUCH_DP * density
    private var shiftPx = 0f // current draw shift in content units (see onDraw)
    private var uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val scroller = OverScroller(context)
    private var flingLastY = 0
    private var twoFingerDownAt = 0L
    private var scaled = false

    init {
        contentDescription = "Terminal"
        isFocusable = true
        isFocusableInTouchMode = true
        applyFontSize()
    }

    /** Render [controller]'s frames and send input to it. */
    fun bind(controller: TerminalController) {
        this.controller = controller
        this.sink = controller.asInputSink()
        bindSource(controller)
        if (isAttachedToWindow) startFrames()
        reportViewport()
    }

    /** Render a static or custom frame source (previews, screenshots). Input is ignored. */
    fun bindSource(source: TerminalFrameSource) {
        this.source = source
        invalidateAllRows()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startFrames()
    }

    override fun onDetachedFromWindow() {
        frameJob?.cancel()
        frameJob = null
        actionMode?.finish()
        source?.setRowAboveWanted(false)
        uiScope.cancel()
        uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        super.onDetachedFromWindow()
    }

    private fun startFrames() {
        val c = controller ?: return
        frameJob?.cancel()
        frameJob = CoroutineScope(Dispatchers.Main.immediate).launch { c.frameVersion.collect { invalidate() } }
    }

    private fun applyFontSize() {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        renderer.setTextSize(px)
        invalidateAllRows()
        reportViewport()
    }

    private fun invalidateAllRows() {
        rowValid.fill(false)
        aboveValid = false
        invalidate()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        reportViewport()
    }

    private fun reportViewport() {
        val c = controller ?: return
        if (width == 0 || height == 0) return
        c.onViewportChanged(
            TerminalViewport(
                widthPx = (width - 2 * padPx).toInt().coerceAtLeast(1),
                heightPx = (height - 2 * padPx).toInt().coerceAtLeast(1),
                cellWidthPx = renderer.cellWidth,
                cellHeightPx = renderer.cellHeight.toFloat(),
            ),
        )
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val src = source
        val bg = theme.background
        // AndroidView hosts do not clip children: never paint outside our bounds.
        val outer = canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(bg)
        if (src == null) {
            canvas.restoreToCount(outer)
            return
        }
        val acquired = src.acquire(render, above)
        if (acquired and TerminalFrameSource.FRAME_CHANGED != 0) onFrameAcquired()
        if (acquired and TerminalFrameSource.ROW_ABOVE_CHANGED != 0) onRowAboveAcquired()
        if (pendingSelStart >= 0) applyPendingSelection()
        val cols = render.cols
        val rows = render.rows
        val cw = renderer.cellWidth
        val ch = renderer.cellHeight
        val contentW = cols * cw
        val contentH = rows * ch.toFloat()

        val availW = width - 2 * padPx
        val availH = height - 2 * padPx
        val fit = min(1f, availW / contentW)
        val isOwner = controller?.isOwner?.value ?: true
        scale = fit * (if (isOwner) 1f else userZoom) * pinchScale
        val scaledW = contentW * scale
        val scaledH = contentH * scale
        panX = panX.coerceIn(0f, max(0f, scaledW - availW))
        tx = padPx - panX
        ty =
            if (scaledH <= availH) {
                padPx
            } else {
                // Keep the cursor row visible (IME open before the debounced resize, big passive terminals).
                val cursorBottom = (render.cursorRow + 1) * ch * scale
                padPx - (cursorBottom - availH).coerceIn(0f, scaledH - availH)
            }
        // Smooth scroll: shift the viewport down by a fraction of a row (whole device pixels) when the
        // row above it is available.
        val hasAbove = above.id != RowSnapshot.NONE && above.id == smooth.frameTop - 1 && above.cells.cols == cols
        val shiftRows = if (hasAbove) smooth.shiftRows() else 0f
        shiftPx = if (shiftRows > 0f) (shiftRows * ch * scale).roundToInt() / scale else 0f

        val save = canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)
        canvas.translate(0f, shiftPx)
        val hw = canvas.isHardwareAccelerated
        if (hw) ensureNodes(rows)
        val g = hasGrapheme
        if (shiftPx > 0f) drawRowAbove(canvas, hw, bg, contentW, ch)
        for (r in 0 until rows) {
            if (hw) {
                val node = rowNodes[r]!!
                if (!rowValid[r]) {
                    node.setPosition(0, r * ch, ceil(contentW).toInt(), (r + 1) * ch)
                    val rc = node.beginRecording()
                    renderer.drawRow(rc, render, r, bg, g)
                    node.endRecording()
                    rowValid[r] = true
                }
                canvas.drawRenderNode(node)
            } else {
                val s = canvas.save()
                canvas.translate(0f, (r * ch).toFloat())
                renderer.drawRow(canvas, render, r, bg, g)
                canvas.restoreToCount(s)
            }
        }
        drawSelection(canvas, cw, ch)
        drawCursor(canvas, cw, ch)
        canvas.restoreToCount(save)
        drawHandles(canvas)
        canvas.restoreToCount(outer)
    }

    private fun drawRowAbove(
        canvas: Canvas,
        hw: Boolean,
        bg: Int,
        contentW: Float,
        ch: Int,
    ) {
        if (hw) {
            val node = aboveNode ?: RenderNode("terminal-row-above").also { aboveNode = it }
            if (!aboveValid) {
                node.setPosition(0, -ch, ceil(contentW).toInt(), 0)
                val rc = node.beginRecording()
                renderer.drawRow(rc, above.cells, 0, bg, aboveGrapheme)
                node.endRecording()
                aboveValid = true
            }
            canvas.drawRenderNode(node)
        } else {
            val s = canvas.save()
            canvas.translate(0f, -ch.toFloat())
            renderer.drawRow(canvas, above.cells, 0, bg, aboveGrapheme)
            canvas.restoreToCount(s)
        }
    }

    private fun onFrameAcquired() {
        val rows = render.rows
        if (rowValid.size != rows) rowValid = BooleanArray(rows)
        for (r in 0 until rows) if (render.dirtyRows[r]) rowValid[r] = false
        render.dirtyRows.fill(false)
        val n = render.cols * rows
        if (render.graphemes.isEmpty()) {
            hasGrapheme = null
        } else {
            val g = hasGrapheme?.takeIf { it.size == n } ?: BooleanArray(n)
            g.fill(false)
            for (k in render.graphemes.keys) if (k < n) g[k] = true
            hasGrapheme = g
        }
        val wasLive = smooth.live
        smooth.onFrame(render.historyBase, render.scrollbackRows, render.viewportOffset)
        if (wasLive != smooth.live) updateRowAboveWanted()
        // A width change reflows rows and a screen switch (shell <-> vim) changes what the row ids
        // point at; evicted rows are gone. Row-count changes (IME show/hide) keep ids valid.
        if (render.cols != lastCols || render.altScreen != lastAlt) {
            if (lastCols != 0 && pendingSelStart < 0) clearSelection()
            lastCols = render.cols
            lastAlt = render.altScreen
        }
        if (selection.isEvicted(render.historyBase)) clearSelection()
    }

    private fun onRowAboveAcquired() {
        aboveValid = false
        val cells = above.cells
        if (cells.graphemes.isEmpty()) {
            aboveGrapheme = null
            return
        }
        val a =
            aboveGraphemeBuf?.takeIf { it.size == cells.cols }
                ?: BooleanArray(cells.cols).also { aboveGraphemeBuf = it }
        a.fill(false)
        for (k in cells.graphemes.keys) if (k < a.size) a[k] = true
        aboveGrapheme = a
    }

    private fun updateRowAboveWanted() {
        source?.setRowAboveWanted(smooth.canScroll && (!smooth.live || smooth.interacting))
    }

    private fun ensureNodes(rows: Int) {
        if (rowNodes.size == rows) return
        val old = rowNodes
        rowNodes = Array(rows) { i -> old.getOrNull(i) ?: RenderNode("terminal-row") }
        for (i in rows until old.size) old[i]?.discardDisplayList()
        rowValid = BooleanArray(rows)
    }

    private fun drawCursor(
        canvas: Canvas,
        cw: Float,
        ch: Int,
    ) {
        val r = render.cursorRow
        val c = render.cursorCol
        val composingText = composing
        if (r !in 0 until render.rows || c !in 0 until render.cols) return
        val x = c * cw
        val y = (r * ch).toFloat()
        if (composingText != null) {
            // IME composition overlay (CJK, swipe typing) at the cursor.
            val w = renderer.cellWidth * composingText.length.coerceAtLeast(1)
            overlayPaint.color = theme.background
            canvas.drawRect(x, y, x + w, y + ch, overlayPaint)
            overlayPaint.color = theme.foreground
            overlayPaint.typeface = typefaces.regular
            overlayPaint.textSize = renderer.textSizePx
            canvas.drawText(composingText, x, y + renderer.baseline, overlayPaint)
            canvas.drawRect(x, y + ch - 2 * density, x + w, y + ch - density, overlayPaint)
            return
        }
        if (!render.cursorVisible || render.viewportOffset != 0) return
        val idx = render.index(c, r)
        val span = if (render.flags[idx] and CellFlags.WIDE != 0) 2 else 1
        val focusedLook = hasFocus() || forceFocusedLook
        overlayPaint.color = theme.cursor
        when (render.cursorStyle) {
            CursorStyle.Block -> {
                if (focusedLook) {
                    canvas.drawRect(x, y, x + span * cw, y + ch, overlayPaint)
                    val save = canvas.save()
                    canvas.translate(0f, y)
                    renderer.drawCell(
                        canvas,
                        render,
                        idx,
                        x,
                        render.background[idx],
                        render.graphemes.isNotEmpty() && hasGrapheme?.get(idx) == true,
                    )
                    canvas.restoreToCount(save)
                } else {
                    strokePaint.color = theme.cursor
                    strokePaint.strokeWidth = max(1f, density)
                    val h = strokePaint.strokeWidth / 2
                    canvas.drawRect(x + h, y + h, x + span * cw - h, y + ch - h, strokePaint)
                }
            }

            CursorStyle.Bar -> {
                canvas.drawRect(x, y, x + max(1f, 2 * density), y + ch, overlayPaint)
            }

            CursorStyle.Underline -> {
                canvas.drawRect(x, y + ch - max(1f, 2 * density), x + span * cw, y + ch, overlayPaint)
            }
        }
    }

    private fun highlightColor(): Int = ((selectionColor ?: theme.cursor) and 0x00FFFFFF) or 0x66000000

    private fun drawSelection(
        canvas: Canvas,
        cw: Float,
        ch: Int,
    ) {
        if (!selection.isActive) return
        val cols = render.cols
        overlayPaint.color = highlightColor()
        val first = if (shiftPx > 0f) -1 else 0
        for (r in first until render.rows) {
            val packed = selection.colsIn(smooth.frameTop + r, cols)
            if (packed < 0) continue
            val c0 = TerminalSelection.firstCol(packed)
            val c1 = TerminalSelection.lastCol(packed)
            canvas.drawRect(c0 * cw, (r * ch).toFloat(), (c1 + 1) * cw, ((r + 1) * ch).toFloat(), overlayPaint)
        }
    }

    /** Visible row index of row id [row] (-1 = the row above), or [OFF_SCREEN]. No boxing: draw path. */
    private fun visibleRow(row: Long): Int {
        val r = row - smooth.frameTop
        val first = if (shiftPx > 0f) -1 else 0
        return if (r >= first && r < render.rows) r.toInt() else OFF_SCREEN
    }

    private fun flagsAt(
        r: Int,
        c: Int,
    ): Int =
        when {
            r == -1 -> above.cells.flags.getOrElse(c) { 0 }
            r in 0 until render.rows && c in 0 until render.cols -> render.flags[r * render.cols + c]
            else -> 0
        }

    /** Handle hotspot in view coordinates: x at the cell edge, y at the bottom of the row. Packed floats. */
    private fun handleX(startHandle: Boolean): Float {
        val p = (if (startHandle) selection.start else selection.end) ?: return Float.NaN
        val r = visibleRow(p.row)
        if (r == OFF_SCREEN) return Float.NaN
        val col =
            if (startHandle) {
                p.col
            } else {
                p.col + if (flagsAt(r, p.col) and CellFlags.WIDE != 0) 2 else 1
            }
        return tx + col * renderer.cellWidth * scale
    }

    private fun handleY(startHandle: Boolean): Float {
        val p = (if (startHandle) selection.start else selection.end) ?: return Float.NaN
        val r = visibleRow(p.row)
        if (r == OFF_SCREEN) return Float.NaN
        return ty + ((r + 1) * renderer.cellHeight + shiftPx) * scale
    }

    private fun drawHandles(canvas: Canvas) {
        if (!selection.isActive) return
        overlayPaint.color = (selectionColor ?: theme.cursor) or 0xFF000000.toInt()
        for (startHandle in BOOLEANS) {
            val x = handleX(startHandle)
            val y = handleY(startHandle)
            if (x.isNaN() || y.isNaN()) continue
            buildHandlePath(startHandle, x, y)
            canvas.drawPath(handlePath, overlayPaint)
        }
    }

    /** Material teardrop: a circle with the corner that touches the text squared off. */
    private fun buildHandlePath(
        startHandle: Boolean,
        x: Float,
        y: Float,
    ) {
        val d = 2 * handleRadius
        if (startHandle) handleRect.set(x - d, y, x, y + d) else handleRect.set(x, y, x + d, y + d)
        handleRadii.fill(handleRadius)
        // Corner order: top-left, top-right, bottom-right, bottom-left (x, y each).
        val corner = if (startHandle) 2 else 0
        handleRadii[corner] = 0f
        handleRadii[corner + 1] = 0f
        handlePath.reset()
        handlePath.addRoundRect(handleRect, handleRadii, Path.Direction.CW)
    }

    /** View coordinates of the center of a handle's drop ([0], [1]), or NaN when off screen. For tests. */
    internal fun handleCenter(startHandle: Boolean): FloatArray {
        val x = handleX(startHandle)
        return floatArrayOf(
            if (startHandle) x - handleRadius else x + handleRadius,
            handleY(startHandle) + handleRadius,
        )
    }

    /** View coordinates of the center of visible cell ([col], [row]). For tests. */
    internal fun cellCenter(
        col: Int,
        row: Int,
    ): FloatArray =
        floatArrayOf(
            tx + (col + 0.5f) * renderer.cellWidth * scale,
            ty + ((row + 0.5f) * renderer.cellHeight + shiftPx) * scale,
        )

    /** Which handle (if any) is under ([x], [y]). */
    private fun handleAt(
        x: Float,
        y: Float,
    ): Int {
        if (!selection.isActive) return HANDLE_NONE
        var best = HANDLE_NONE
        var bestDist = handleTouch
        for (startHandle in BOOLEANS) {
            val hx = handleX(startHandle)
            val hy = handleY(startHandle)
            if (hx.isNaN() || hy.isNaN()) continue
            val cx = if (startHandle) hx - handleRadius else hx + handleRadius
            val dist = hypot(x - cx, y - (hy + handleRadius))
            if (dist <= bestDist) {
                bestDist = dist
                best = if (startHandle) HANDLE_START else HANDLE_END
            }
        }
        return best
    }

    // --------------------------------------------------------------- keyboard

    override fun onCheckIsTextEditor(): Boolean = sink != null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val s = sink ?: return null
        TerminalInputConnection.configure(outAttrs)
        return TerminalInputConnection(this, s) { text ->
            composing = text
            invalidate()
        }.also { inputConnection = it }
    }

    /** A key that reaches the view directly must not overtake text the IME is still composing. */
    private fun commitComposingBeforeKey() {
        if (inputConnection?.commitComposing() == true) {
            context.getSystemService(InputMethodManager::class.java)?.restartInput(this)
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val s = sink ?: return super.onKeyDown(keyCode, event)
        if (event.isCtrlPressed && event.isShiftPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_C -> return copySelection().let { true }
                KeyEvent.KEYCODE_V -> return pasteClipboard().let { true }
            }
        }
        val key = KeyEventMapper.map(event) ?: return super.onKeyDown(keyCode, event)
        clearSelection()
        commitComposingBeforeKey()
        s.sendKey(key)
        return true
    }

    override fun onKeyMultiple(
        keyCode: Int,
        repeatCount: Int,
        event: KeyEvent,
    ): Boolean {
        val s = sink ?: return false
        val key = KeyEventMapper.map(event) ?: return super.onKeyMultiple(keyCode, repeatCount, event)
        commitComposingBeforeKey()
        s.sendKey(key)
        return true
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        a11y.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        controller?.setFocused(gainFocus && hasWindowFocus())
        invalidate()
    }

    /** Screen lock, a dialog or another window on top: the program sees FOCUS 0 (owner only). */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        controller?.setFocused(hasWindowFocus && isFocused)
        invalidate()
    }

    /** Hide the soft keyboard (the view keeps focus, so a hardware keyboard still types). */
    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    /** Focus the terminal and show the soft keyboard. */
    fun showKeyboard() {
        requestFocus()
        if (!showKeyboardOnTap) return
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
    }

    // ---------------------------------------------------------------- gestures

    private val gestures =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    scroller.forceFinished(true)
                    return true
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (selection.isActive) clearSelection() else showKeyboard()
                    performClick()
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (e2.pointerCount > 1) return false
                    if (abs(distanceX) > abs(distanceY)) {
                        panX += distanceX
                        invalidate()
                    } else {
                        scrollPixels(distanceY)
                    }
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    if (!smooth.canScroll) return false
                    flingLastY = 0
                    scroller.fling(0, 0, 0, (-velocityY).toInt(), 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
                    postInvalidateOnAnimation()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    val cell = cellAt(e.x, e.y, clamp = false) ?: return
                    if (!selectWord(cell)) return
                    selection.beginDrag(startHandle = false)
                    selecting = true
                    dragDx = 0f
                    dragDy = 0f
                    actionMode?.finish()
                    performHapticFeedback(HAPTIC_LONG_PRESS)
                }
            },
        )

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    scaled = true
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    // While pinching only the canvas scale changes (row display lists are replayed).
                    pinchScale = (pinchScale * detector.scaleFactor).coerceIn(0.4f, 3f)
                    invalidate()
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    val factor = pinchScale
                    pinchScale = 1f
                    if (controller?.isOwner?.value != false) {
                        // Re-measure cols x rows once, at gesture end.
                        fontSizeSp *= factor
                        onFontSizeChange(fontSizeSp)
                    } else {
                        userZoom = (userZoom * factor).coerceIn(1f, 4f)
                        invalidate()
                    }
                }
            },
        )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastTouchX = event.x
        lastTouchY = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scaled = false
                smooth.interacting = true
                updateRowAboveWanted()
                val h = handleAt(event.x, event.y)
                if (h != HANDLE_NONE) {
                    // Keep the offset between finger and hotspot so the handle does not jump under it.
                    val start = h == HANDLE_START
                    dragHandle = h
                    dragDx = handleX(start) - event.x
                    dragDy = handleY(start) - renderer.cellHeight * scale / 2 - event.y
                    selection.beginDrag(start)
                    actionMode?.finish()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) twoFingerDownAt = SystemClock.uptimeMillis()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2 && !scaled &&
                    SystemClock.uptimeMillis() - twoFingerDownAt < TWO_FINGER_TAP_MS
                ) {
                    pasteClipboard()
                }
            }
        }
        if (dragHandle != HANDLE_NONE || selecting) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    dragSelectionTo(event.x, event.y)
                    startAutoScrollIfNeeded()
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragHandle = HANDLE_NONE
                    selecting = false
                    smooth.interacting = false
                    updateRowAboveWanted()
                    if (selection.isActive) startSelectionActionMode()
                    return true
                }
            }
            if (dragHandle != HANDLE_NONE) return true
        }
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            smooth.interacting = !scroller.isFinished
            updateRowAboveWanted()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val y = scroller.currY
            val dy = y - flingLastY
            scrollPixels(dy.toFloat())
            flingLastY = y
            if (dy != 0 && smooth.atEdge) scroller.forceFinished(true) else postInvalidateOnAnimation()
            if (scroller.isFinished) {
                smooth.interacting = false
                updateRowAboveWanted()
            }
        }
    }

    /** Positive = toward newer output. Moves [smooth] and asks the engine for the whole row under it. */
    private fun scrollPixels(dy: Float) {
        val rowPx = renderer.cellHeight * scale
        if (rowPx <= 0f) return
        scrollRows(dy / rowPx.toDouble())
    }

    private fun scrollRows(rows: Double) {
        if (!smooth.canScroll) return
        val request = smooth.move(rows)
        if (request != null) {
            // Live means "follow the bottom": the bottom row id seen in the last frame may be stale.
            if (smooth.live) controller?.scrollToBottom() else controller?.scrollToRow(request)
        }
        actionMode?.invalidateContentRect()
        invalidate()
    }

    /** Auto-scroll while a handle or long-press drag sits in the top or bottom edge zone. */
    private val autoScroll =
        object : Runnable {
            override fun run() {
                val speed = edgeSpeed()
                if ((dragHandle == HANDLE_NONE && !selecting) || speed == 0.0 || !smooth.canScroll) {
                    autoScrolling = false
                    return
                }
                scrollRows(speed)
                dragSelectionTo(lastTouchX, lastTouchY)
                postOnAnimation(this)
            }
        }

    private fun startAutoScrollIfNeeded() {
        if (autoScrolling || edgeSpeed() == 0.0) return
        autoScrolling = true
        postOnAnimation(autoScroll)
    }

    /** Rows per frame toward newer (+) or older (-) output; faster closer to the edge. */
    private fun edgeSpeed(): Double {
        val zone = max(renderer.cellHeight * scale, EDGE_ZONE_DP * density)
        val y = lastTouchY + dragDy
        return when {
            y < zone -> -MAX_AUTO_SCROLL_ROWS * ((zone - y) / zone).coerceIn(0.1f, 1f)
            y > height - zone -> MAX_AUTO_SCROLL_ROWS * ((y - (height - zone)) / zone).coerceIn(0.1f, 1f)
            else -> 0.0
        }
    }

    /**
     * The cell under ([x], [y]) in view coordinates, as a row id. [clamp]: points outside the grid snap
     * to its edge (handle drags); otherwise they return null.
     */
    private fun cellAt(
        x: Float,
        y: Float,
        clamp: Boolean,
        round: Boolean = false,
    ): CellPos? {
        if (scale <= 0f || render.cols <= 0) return null
        val cx = (x - tx) / scale / renderer.cellWidth
        val cy = ((y - ty) / scale - shiftPx) / renderer.cellHeight
        var col = if (round) cx.roundToInt() else floor(cx).toInt()
        var row = floor(cy).toInt()
        val first = if (shiftPx > 0f) -1 else 0
        if (clamp) {
            col = col.coerceIn(0, render.cols - 1)
            row = row.coerceIn(first, render.rows - 1)
        } else if (col !in 0 until render.cols || row !in first until render.rows) {
            return null
        }
        return CellPos(smooth.frameTop + row, col)
    }

    private fun dragSelectionTo(
        x: Float,
        y: Float,
    ) {
        if (!selection.isActive) return
        val target: CellPos =
            if (dragHandle == HANDLE_NONE) {
                cellAt(x, y, clamp = true) ?: return
            } else {
                // The hotspot sits on a cell boundary: round to the nearest one.
                val p = cellAt(x + dragDx, y + dragDy, clamp = true, round = true) ?: return
                val boundary = p.col
                val draggingStart = selection.draggingStart
                if (draggingStart) {
                    p.copy(col = boundary.coerceIn(0, render.cols - 1))
                } else {
                    p.copy(col = (boundary - 1).coerceIn(0, render.cols - 1))
                }
            }
        val r = visibleRow(target.row)
        val adjusted =
            if (r != OFF_SCREEN && target.col > 0 && flagsAt(r, target.col) and CellFlags.WIDE_SPACER != 0) {
                target.copy(col = target.col - 1)
            } else {
                target
            }
        selection.dragTo(adjusted)
        invalidate()
    }

    // --------------------------------------------------------------- selection

    /** Selects the word at [cell]; false (nothing selected) for off-screen or blank space past the row's text. */
    private fun selectWord(cell: CellPos): Boolean {
        val r = visibleRow(cell.row)
        if (r == OFF_SCREEN) return false
        val snap = if (r == -1) above.cells else render
        val base = if (r == -1) 0 else r * render.cols
        val packed = TerminalSelection.wordAt(snap.codepoints, snap.flags, base, snap.cols, cell.col)
        if (packed == TerminalSelection.NONE) return false
        selection.set(
            CellPos(cell.row, TerminalSelection.firstCol(packed)),
            CellPos(cell.row, TerminalSelection.lastCol(packed)),
        )
        onSelectionChanged()
        return true
    }

    private fun applyPendingSelection() {
        val cols = render.cols
        if (cols <= 0) return
        val top = smooth.frameTop
        selection.set(
            CellPos(top + pendingSelStart / cols, pendingSelStart % cols),
            CellPos(top + pendingSelEnd / cols, pendingSelEnd % cols),
        )
        pendingSelStart = -1
        pendingSelEnd = -1
    }

    /** Text of the selected cells that are on screen (rows joined with newlines, trailing blanks trimmed). */
    fun selectedText(): String? {
        if (pendingSelStart >= 0) applyPendingSelection()
        return selectedText(selection.start ?: return null, selection.end ?: return null)
    }

    private fun selectedText(
        s: CellPos,
        e: CellPos,
    ): String {
        val top = smooth.frameTop
        return render.textRange((s.row - top).toInt(), s.col, (e.row - top).toInt(), e.col)
    }

    /** Select cells [start]..[end] (row-major indexes in the visible grid). */
    fun select(
        start: Int,
        end: Int,
    ) {
        pendingSelStart = min(start, end).coerceAtLeast(0)
        pendingSelEnd = max(start, end).coerceAtLeast(0)
        invalidate()
    }

    fun clearSelection() {
        pendingSelStart = -1
        pendingSelEnd = -1
        if (!selection.isActive) return
        selection.clear()
        selecting = false
        dragHandle = HANDLE_NONE
        actionMode?.finish()
        onSelectionChanged()
    }

    private fun selectAll() {
        val cols = render.cols
        selection.set(
            CellPos(render.historyBase, 0),
            CellPos(render.historyBase + render.scrollbackRows + render.rows - 1, cols - 1),
        )
        onSelectionChanged()
    }

    private fun onSelectionChanged() {
        invalidate()
        actionMode?.invalidateContentRect()
        a11y.invalidateRoot()
    }

    /**
     * Copy the selection (including rows scrolled off screen: the engine reads them) and clear it.
     * Returns false when nothing is selected.
     */
    fun copySelection(): Boolean {
        if (pendingSelStart >= 0) applyPendingSelection()
        val s = selection.start ?: return false
        val e = selection.end ?: return false
        val c = controller
        val evicted = selection.isEvicted(render.historyBase)
        val alt = render.altScreen
        clearSelection()
        if (evicted) {
            Toast.makeText(context, R.string.terminal_selection_evicted, Toast.LENGTH_SHORT).show()
        } else if (c == null) {
            setClipboard(selectedText(s, e))
        } else {
            uiScope.launch {
                val text =
                    try {
                        c.selectionText(s.row, s.col, e.row, e.col, alt)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        null // engine closed or failed: copy nothing rather than crash
                    }
                if (text == null) {
                    Toast.makeText(context, R.string.terminal_selection_evicted, Toast.LENGTH_SHORT).show()
                } else {
                    setClipboard(text)
                }
            }
        }
        return true
    }

    /** Never overwrites the clipboard with nothing. */
    private fun setClipboard(text: String) {
        if (text.isEmpty()) return
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    fun pasteClipboard(): Boolean {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return false
        if (clip.itemCount == 0) return false
        val text =
            clip
                .getItemAt(0)
                .coerceToText(context)
                ?.toString()
                .orEmpty()
        if (text.isEmpty()) return false
        return sink?.paste(text) ?: false
    }

    private fun startSelectionActionMode() {
        actionMode?.finish()
        actionMode =
            startActionMode(
                object : ActionMode.Callback2() {
                    override fun onCreateActionMode(
                        mode: ActionMode,
                        menu: Menu,
                    ): Boolean {
                        menu.add(Menu.NONE, MENU_COPY, 0, android.R.string.copy)
                        if (sink != null) menu.add(Menu.NONE, MENU_PASTE, 1, android.R.string.paste)
                        menu.add(Menu.NONE, MENU_SELECT_ALL, 2, android.R.string.selectAll)
                        return true
                    }

                    override fun onPrepareActionMode(
                        mode: ActionMode,
                        menu: Menu,
                    ) = false

                    override fun onActionItemClicked(
                        mode: ActionMode,
                        item: MenuItem,
                    ): Boolean {
                        when (item.itemId) {
                            MENU_COPY -> {
                                copySelection()
                            }

                            MENU_PASTE -> {
                                pasteClipboard()
                                clearSelection()
                            }

                            MENU_SELECT_ALL -> {
                                selectAll()
                                return true // keep the toolbar
                            }
                        }
                        mode.finish()
                        return true
                    }

                    override fun onDestroyActionMode(mode: ActionMode) {
                        if (actionMode === mode) actionMode = null
                    }

                    override fun onGetContentRect(
                        mode: ActionMode,
                        view: View,
                        outRect: Rect,
                    ) {
                        selectionBounds(outRect)
                    }
                },
                ActionMode.TYPE_FLOATING,
            )
    }

    /** On-screen bounds of the selection (the view's top edge when it is scrolled off). */
    private fun selectionBounds(out: Rect) {
        val s = selection.start
        val e = selection.end
        if (s == null || e == null) {
            out.set(0, 0, width, 0)
            return
        }
        val first = if (shiftPx > 0f) -1 else 0
        val r0 = (s.row - smooth.frameTop).coerceIn(first.toLong(), render.rows.toLong()).toInt()
        val r1 = (e.row - smooth.frameTop).coerceIn((first - 1).toLong(), render.rows.toLong() - 1).toInt()
        val cw = renderer.cellWidth * scale
        val ch = renderer.cellHeight * scale
        val oneRow = r0 == r1
        val left = if (oneRow) tx + s.col * cw else tx
        val right = if (oneRow) tx + (e.col + 1) * cw else tx + render.cols * cw
        val top = ty + shiftPx * scale + r0 * ch
        val bottom = ty + shiftPx * scale + (r1 + 1) * ch
        out.set(
            left.toInt().coerceIn(0, width),
            top.toInt().coerceIn(0, height),
            right.toInt().coerceIn(0, width),
            max(top, bottom).toInt().coerceIn(0, height),
        )
    }

    // ---------------------------------------------------------- accessibility

    /** Selection handles as virtual views (TalkBack focus, hover, custom move actions). */
    private inner class HandleAccessibility : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(
            x: Float,
            y: Float,
        ): Int = handleAt(x, y).takeIf { it != HANDLE_NONE } ?: INVALID_ID

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            if (!selection.isActive) return
            if (!handleX(true).isNaN()) ids += HANDLE_START
            if (!handleX(false).isNaN()) ids += HANDLE_END
        }

        override fun onPopulateNodeForVirtualView(
            id: Int,
            node: AccessibilityNodeInfoCompat,
        ) {
            val start = id == HANDLE_START
            node.contentDescription =
                context.getString(
                    if (start) R.string.terminal_selection_start_handle else R.string.terminal_selection_end_handle,
                )
            val x = handleX(start)
            val y = handleY(start)
            val d = 2 * handleRadius
            if (x.isNaN() || y.isNaN()) {
                a11yBounds.set(0, 0, 1, 1) // off screen: not listed as visible, but bounds are mandatory
            } else {
                val left = if (start) x - d else x
                a11yBounds.set(left.toInt(), y.toInt(), (left + d).toInt(), (y + d).toInt())
            }
            @Suppress("DEPRECATION")
            node.setBoundsInParent(a11yBounds)
            for (i in HANDLE_ACTIONS.indices) {
                node.addAction(AccessibilityActionCompat(HANDLE_ACTIONS[i], context.getString(HANDLE_ACTION_LABELS[i])))
            }
        }

        override fun onPerformActionForVirtualView(
            id: Int,
            action: Int,
            arguments: Bundle?,
        ): Boolean {
            val i = HANDLE_ACTIONS.indexOf(action)
            if (i < 0 || !selection.isActive) return false
            moveHandle(
                start = id == HANDLE_START,
                words =
                    if (i <
                        2
                    ) {
                        (if (i == 0) -1 else 1)
                    } else {
                        0
                    },
                lines = if (i >= 2) (if (i == 2) -1 else 1) else 0,
            )
            invalidateVirtualView(id)
            return true
        }
    }

    private val a11yBounds = Rect()
    private val a11y = HandleAccessibility().also { ViewCompat.setAccessibilityDelegate(this, it) }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /** Move a handle by whole words (on visible rows) or lines (accessibility actions). */
    private fun moveHandle(
        start: Boolean,
        words: Int,
        lines: Int,
    ) {
        val p = (if (start) selection.start else selection.end) ?: return
        val oldest = render.historyBase
        val newest = render.historyBase + render.scrollbackRows + render.rows - 1
        var row = (p.row + lines).coerceIn(oldest, newest)
        var col = p.col
        if (words != 0) {
            val r = visibleRow(row)
            if (r == OFF_SCREEN || r < 0) {
                col = (col + words).coerceIn(0, render.cols - 1)
            } else {
                col =
                    TerminalSelection.nextWordEdge(
                        render.codepoints,
                        r * render.cols,
                        render.cols,
                        col,
                        words > 0,
                        start,
                    )
            }
        }
        selection.beginDrag(start)
        selection.dragTo(CellPos(row, col))
        onSelectionChanged()
    }

    companion object {
        const val DEFAULT_FONT_SP = 13f
        const val MIN_FONT_SP = 6f
        const val MAX_FONT_SP = 40f
        private const val PADDING_DP = 4f
        private const val TWO_FINGER_TAP_MS = 300L
        private const val MENU_COPY = 1
        private const val MENU_PASTE = 2
        private const val MENU_SELECT_ALL = 3
        private const val HAPTIC_LONG_PRESS = android.view.HapticFeedbackConstants.LONG_PRESS
        private const val OFF_SCREEN = Int.MIN_VALUE
        private const val HANDLE_NONE = 0
        private const val HANDLE_START = 1
        private const val HANDLE_END = 2
        private const val HANDLE_RADIUS_DP = 11f
        private const val HANDLE_TOUCH_DP = 28f
        private const val EDGE_ZONE_DP = 32f
        private const val MAX_AUTO_SCROLL_ROWS = 0.6
        private val BOOLEANS = booleanArrayOf(true, false)
        private val HANDLE_ACTIONS =
            intArrayOf(
                R.id.terminal_action_handle_previous_word,
                R.id.terminal_action_handle_next_word,
                R.id.terminal_action_handle_previous_line,
                R.id.terminal_action_handle_next_line,
            )
        private val HANDLE_ACTION_LABELS =
            intArrayOf(
                R.string.terminal_handle_previous_word,
                R.string.terminal_handle_next_word,
                R.string.terminal_handle_previous_line,
                R.string.terminal_handle_next_line,
            )
    }
}
