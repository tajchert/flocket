package com.mtajchert.flocket.core.terminal.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderNode
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
import com.mtajchert.flocket.core.terminal.controller.TerminalController
import com.mtajchert.flocket.core.terminal.controller.TerminalFrameSource
import com.mtajchert.flocket.core.terminal.controller.TerminalViewport
import com.mtajchert.flocket.core.terminal.engine.CellFlags
import com.mtajchert.flocket.core.terminal.engine.CursorStyle
import com.mtajchert.flocket.core.terminal.engine.ScreenSnapshot
import com.mtajchert.flocket.core.terminal.engine.TerminalTheme
import com.mtajchert.flocket.core.terminal.fake.FakeTerminalEngine
import com.mtajchert.flocket.core.terminal.input.KeyEventMapper
import com.mtajchert.flocket.core.terminal.input.TerminalInputConnection
import com.mtajchert.flocket.core.terminal.input.TerminalInputSink
import com.mtajchert.flocket.core.terminal.input.asInputSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The terminal surface as a hardware-accelerated [View] (hosted by `TerminalSurface` via
 * `AndroidView`). Why a View: per-row [RenderNode]s (re-record only dirty rows, replay the rest),
 * a direct [InputConnection] for the IME, and no recomposition on the frame path.
 *
 * Frame path: the controller's frame version changes -> [invalidate] -> [onDraw] pulls changed
 * rows into [render] (`acquireFrame`), re-records those rows' display lists and replays all rows,
 * then draws selection, cursor and IME composing text on top. Software canvases (layoutlib
 * screenshots, Robolectric) draw rows directly.
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

    private val padPx = (PADDING_DP * density)
    private var composing: String? = null

    // Layout transform (content -> view): x' = tx + x * scale.
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var userZoom = 1f
    private var pinchScale = 1f
    private var panX = 0f

    // Selection (cell indexes in the visible grid, inclusive), -1 = none.
    private var selAnchor = -1
    private var selEnd = -1
    private var selecting = false
    private var actionMode: ActionMode? = null

    private var scrollAccum = 0f
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
        if (src.acquireFrame(render)) onFrameAcquired()
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

        val save = canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)
        val hw = canvas.isHardwareAccelerated
        if (hw) ensureNodes(rows)
        val g = hasGrapheme
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
        canvas.restoreToCount(outer)
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
        if (selAnchor >= n || selEnd >= n) clearSelection()
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

    private fun drawSelection(
        canvas: Canvas,
        cw: Float,
        ch: Int,
    ) {
        if (selAnchor < 0 || selEnd < 0) return
        val a = min(selAnchor, selEnd)
        val b = max(selAnchor, selEnd)
        val cols = render.cols
        overlayPaint.color = (theme.cursor and 0x00FFFFFF) or 0x66000000
        for (r in a / cols..b / cols) {
            val c0 = if (r == a / cols) a % cols else 0
            val c1 = if (r == b / cols) b % cols else cols - 1
            canvas.drawRect(c0 * cw, (r * ch).toFloat(), (c1 + 1) * cw, ((r + 1) * ch).toFloat(), overlayPaint)
        }
    }

    // --------------------------------------------------------------- keyboard

    override fun onCheckIsTextEditor(): Boolean = sink != null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val s = sink ?: return null
        TerminalInputConnection.configure(outAttrs)
        return TerminalInputConnection(this, s) { text ->
            composing = text
            invalidate()
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
        s.sendKey(key)
        return true
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
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
                    scrollAccum = 0f
                    return true
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (selAnchor >= 0) clearSelection() else showKeyboard()
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
                    flingLastY = 0
                    scroller.fling(0, 0, 0, (-velocityY).toInt(), 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
                    postInvalidateOnAnimation()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    val cell = cellAt(e.x, e.y)
                    if (cell < 0) return
                    selectWord(cell)
                    selecting = true
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
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scaled = false
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
        if (selecting) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val cell = cellAt(event.x, event.y)
                    if (cell >= 0 && cell != selEnd) {
                        selEnd = cell
                        invalidate()
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    selecting = false
                    startSelectionActionMode()
                    return true
                }
            }
        }
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val y = scroller.currY
            scrollPixels((y - flingLastY).toFloat())
            flingLastY = y
            postInvalidateOnAnimation()
        }
    }

    /** Positive = toward newer output. Whole rows are sent; the remainder is kept for the next move. */
    private fun scrollPixels(dy: Float) {
        val rowPx = renderer.cellHeight * scale
        if (rowPx <= 0f) return
        scrollAccum += dy / rowPx
        val rows = scrollAccum.toInt()
        if (rows != 0) {
            scrollAccum -= rows
            controller?.scrollBy(rows)
        }
    }

    private fun cellAt(
        x: Float,
        y: Float,
    ): Int {
        if (scale <= 0f) return -1
        val col = ((x - tx) / scale / renderer.cellWidth).toInt()
        val row = ((y - ty) / scale / renderer.cellHeight).toInt()
        if (col !in 0 until render.cols || row !in 0 until render.rows) return -1
        return row * render.cols + col
    }

    // --------------------------------------------------------------- selection

    private fun selectWord(cell: Int) {
        val cols = render.cols
        val rowStart = cell / cols * cols

        fun isWord(i: Int): Boolean {
            val cp = render.codepoints[i]
            return cp != 0 && cp != ' '.code &&
                (Character.isLetterOrDigit(cp) || cp.toChar() in WORD_CHARS || cp > 0x7F)
        }
        var a = cell
        var b = cell
        if (isWord(cell)) {
            while (a > rowStart && isWord(a - 1)) a--
            while (b < rowStart + cols - 1 && isWord(b + 1)) b++
        }
        selAnchor = a
        selEnd = b
        invalidate()
    }

    /** Text of the current selection (rows joined with newlines, trailing blanks trimmed). */
    fun selectedText(): String? {
        if (selAnchor < 0 || selEnd < 0) return null
        val a = min(selAnchor, selEnd)
        val b = max(selAnchor, selEnd)
        val cols = render.cols
        val sb = StringBuilder()
        val line = StringBuilder()
        for (r in a / cols..b / cols) {
            line.setLength(0)
            val c0 = if (r == a / cols) a % cols else 0
            val c1 = if (r == b / cols) b % cols else cols - 1
            for (c in c0..c1) {
                val i = r * cols + c
                if (render.flags[i] and CellFlags.WIDE_SPACER != 0) continue
                val cp = render.codepoints[i]
                val g = render.graphemes[i]
                when {
                    g != null -> line.append(g)
                    cp == 0 -> line.append(' ')
                    else -> line.appendCodePoint(cp)
                }
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line.trimEnd())
        }
        return sb.toString()
    }

    /** Select cells [start]..[end] (row-major indexes in the visible grid). */
    fun select(
        start: Int,
        end: Int,
    ) {
        selAnchor = start
        selEnd = end
        invalidate()
    }

    fun clearSelection() {
        if (selAnchor < 0) return
        selAnchor = -1
        selEnd = -1
        actionMode?.finish()
        invalidate()
    }

    fun copySelection(): Boolean {
        val text = selectedText() ?: return false
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("terminal", text))
        clearSelection()
        return true
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
                        menu.add(Menu.NONE, MENU_PASTE, 1, android.R.string.paste)
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
                            MENU_COPY -> copySelection()
                            MENU_PASTE -> pasteClipboard().also { clearSelection() }
                        }
                        mode.finish()
                        return true
                    }

                    override fun onDestroyActionMode(mode: ActionMode) {
                        actionMode = null
                    }

                    override fun onGetContentRect(
                        mode: ActionMode,
                        view: View,
                        outRect: Rect,
                    ) {
                        val cols = render.cols.coerceAtLeast(1)
                        val a = min(selAnchor, selEnd).coerceAtLeast(0)
                        val b = max(selAnchor, selEnd).coerceAtLeast(0)
                        val cw = renderer.cellWidth * scale
                        val ch = renderer.cellHeight * scale
                        outRect.set(
                            (tx + (a % cols) * cw).toInt(),
                            (ty + (a / cols) * ch).toInt(),
                            (tx + ((b % cols) + 1) * cw).toInt(),
                            (ty + (b / cols + 1) * ch).toInt(),
                        )
                    }
                },
                ActionMode.TYPE_FLOATING,
            )
    }

    companion object {
        const val DEFAULT_FONT_SP = 13f
        const val MIN_FONT_SP = 6f
        const val MAX_FONT_SP = 40f
        private const val PADDING_DP = 4f
        private const val TWO_FINGER_TAP_MS = 300L
        private const val MENU_COPY = 1
        private const val MENU_PASTE = 2
        private const val HAPTIC_LONG_PRESS = android.view.HapticFeedbackConstants.LONG_PRESS
        private const val WORD_CHARS = "-_./~:@%+=#"
    }
}
