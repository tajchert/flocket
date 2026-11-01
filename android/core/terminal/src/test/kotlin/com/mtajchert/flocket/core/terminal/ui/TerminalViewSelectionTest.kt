package com.mtajchert.flocket.core.terminal.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import com.mtajchert.flocket.core.terminal.R
import com.mtajchert.flocket.core.terminal.controller.StaticFrameSource
import com.mtajchert.flocket.core.terminal.controller.TerminalFrameSource
import com.mtajchert.flocket.core.terminal.controller.copyRows
import com.mtajchert.flocket.core.terminal.engine.ScreenSnapshot
import com.mtajchert.flocket.core.terminal.fake.FakeTerminalEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

// Native graphics: real font metrics, so cells have their real size.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class TerminalViewSelectionTest {
    private lateinit var view: TerminalView
    private var downTime = 0L

    @Before
    fun setUp() {
        val engine = FakeTerminalEngine(30, 4)
        engine.feed("cd ~/src/flocket now\r\nsecond line here\r\nthird".encodeToByteArray())
        view = TerminalView(ApplicationProvider.getApplicationContext())
        view.bindSource(StaticFrameSource(engine.snapshot()))
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1200, 400)
        draw()
    }

    private fun draw() = view.draw(Canvas(Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)))

    private fun touch(
        action: Int,
        p: FloatArray,
    ) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, p[0], p[1], 0)
        view.onTouchEvent(e)
        e.recycle()
    }

    private fun longPress(p: FloatArray) {
        touch(MotionEvent.ACTION_DOWN, p)
        ShadowLooper.idleMainLooper(
            ViewConfiguration.getLongPressTimeout() + 100L,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        touch(MotionEvent.ACTION_UP, p)
        draw()
    }

    @Test
    fun `long press selects the word under the finger`() {
        longPress(view.cellCenter(col = 6, row = 0))
        assertEquals("~/src/flocket", view.selectedText())
    }

    @Test
    fun `long press on blank space past the text selects nothing`() {
        longPress(view.cellCenter(col = 25, row = 0))
        assertNull(view.selectedText())
        longPress(view.cellCenter(col = 5, row = 3))
        assertNull(view.selectedText())
    }

    @Test
    fun `dragging the end handle extends the selection across rows`() {
        longPress(view.cellCenter(col = 0, row = 0))
        assertEquals("cd", view.selectedText())
        val handle = view.handleCenter(startHandle = false)
        touch(MotionEvent.ACTION_DOWN, handle)
        // Move the hotspot to the end of "second" one row down.
        val target = view.cellCenter(col = 6, row = 1)
        val from = view.cellCenter(col = 2, row = 0)
        touch(MotionEvent.ACTION_MOVE, floatArrayOf(handle[0] + target[0] - from[0], handle[1] + target[1] - from[1]))
        touch(MotionEvent.ACTION_UP, floatArrayOf(handle[0] + target[0] - from[0], handle[1] + target[1] - from[1]))
        draw()
        assertEquals("cd ~/src/flocket now\nsecond", view.selectedText())
    }

    @Test
    fun `a tap elsewhere clears the selection`() {
        longPress(view.cellCenter(col = 6, row = 0))
        val p = view.cellCenter(col = 20, row = 3)
        touch(MotionEvent.ACTION_DOWN, p)
        touch(MotionEvent.ACTION_UP, p)
        assertNull(view.selectedText())
    }

    /** A frame source whose screen the test replaces. */
    private class MutableSource(
        var snapshot: ScreenSnapshot,
    ) : TerminalFrameSource {
        private var delivered: ScreenSnapshot? = null

        override fun acquireFrame(target: ScreenSnapshot): Boolean {
            if (delivered === snapshot) return false
            delivered = snapshot
            copyRows(snapshot, BooleanArray(snapshot.rows) { true }, target, force = true)
            return true
        }
    }

    private fun swapScreen(
        source: MutableSource,
        cols: Int,
        rows: Int,
        bytes: String,
    ) {
        source.snapshot = FakeTerminalEngine(cols, rows).apply { feed(bytes.encodeToByteArray()) }.snapshot()
        draw()
    }

    @Test
    fun `switching to the alternate screen or changing width clears the selection, height does not`() {
        val text = "cd ~/src/flocket now"
        val source = MutableSource(FakeTerminalEngine(30, 4).apply { feed(text.encodeToByteArray()) }.snapshot())
        view.bindSource(source)
        draw()
        longPress(view.cellCenter(col = 6, row = 0))
        swapScreen(source, 30, 3, text) // IME shown: fewer rows, same ids
        assertEquals("~/src/flocket", view.selectedText())
        swapScreen(source, 30, 3, "\u001b[?1049hvim")
        assertNull(view.selectedText())
        longPress(view.cellCenter(col = 0, row = 0))
        swapScreen(source, 40, 3, "\u001b[?1049hvim")
        assertNull(view.selectedText())
    }

    @Test
    fun `accessibility actions move a handle by word and line`() {
        longPress(view.cellCenter(col = 0, row = 0))
        val provider = view.accessibilityNodeProvider!!
        val end = 2 // HANDLE_END virtual view id
        assertTrue(provider.performAction(end, R.id.terminal_action_handle_next_word, null))
        draw()
        assertEquals("cd ~/src/flocket", view.selectedText())
        assertTrue(provider.performAction(end, R.id.terminal_action_handle_next_line, null))
        draw()
        assertEquals("cd ~/src/flocket now\nsecond line here", view.selectedText())
        val node = provider.createAccessibilityNodeInfo(end)!!
        assertEquals("Selection end handle", node.contentDescription)
    }
}
