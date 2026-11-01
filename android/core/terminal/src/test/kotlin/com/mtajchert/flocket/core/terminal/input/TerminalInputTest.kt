package com.mtajchert.flocket.core.terminal.input

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.mtajchert.flocket.core.terminal.engine.Key
import com.mtajchert.flocket.core.terminal.engine.KeyInput
import com.mtajchert.flocket.core.terminal.fake.FakeTerminalEngine
import com.mtajchert.flocket.core.terminal.ui.ExtraKey
import com.mtajchert.flocket.core.terminal.ui.ExtraKeysRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TerminalInputTest {
    @get:Rule
    val compose = createComposeRule()

    private class RecordingSink : TerminalInputSink {
        val log = mutableListOf<String>()

        override fun sendKey(key: KeyInput): Boolean =
            log.add("key:" + FakeTerminalEngine.encodeKeyInput(key).escaped())

        override fun sendText(text: String): Boolean = log.add("text:$text")

        override fun paste(text: String): Boolean = log.add("paste:$text")
    }

    private fun down(
        code: Int,
        meta: Int = 0,
    ) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta)

    private fun encoded(event: KeyEvent): String =
        FakeTerminalEngine.encodeKeyInput(KeyEventMapper.map(event)!!).escaped()

    @Test
    fun `hardware ctrl+c encodes to ETX`() {
        assertEquals("^C", encoded(down(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)))
    }

    @Test
    fun `hardware arrows, function keys, home, page and escape`() {
        assertEquals("ESC[A", encoded(down(KeyEvent.KEYCODE_DPAD_UP)))
        assertEquals("ESC[1;5D", encoded(down(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_CTRL_ON)))
        assertEquals("ESCOP", encoded(down(KeyEvent.KEYCODE_F1)))
        assertEquals("ESC[24~", encoded(down(KeyEvent.KEYCODE_F12)))
        assertEquals("ESC[H", encoded(down(KeyEvent.KEYCODE_MOVE_HOME)))
        assertEquals("ESC[6~", encoded(down(KeyEvent.KEYCODE_PAGE_DOWN)))
        assertEquals("ESC", encoded(down(KeyEvent.KEYCODE_ESCAPE)))
        assertEquals("^M", encoded(down(KeyEvent.KEYCODE_ENTER)))
        assertEquals("DEL", encoded(down(KeyEvent.KEYCODE_DEL)))
    }

    @Test
    fun `alt prefixes escape and shift produces uppercase`() {
        assertEquals("ESCx", encoded(down(KeyEvent.KEYCODE_X, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)))
        assertEquals("A", encoded(down(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)))
        assertEquals("7", encoded(down(KeyEvent.KEYCODE_7)))
        assertEquals(" ", encoded(down(KeyEvent.KEYCODE_SPACE)))
        assertEquals("^@", encoded(down(KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON)))
    }

    @Test
    fun `back, volume and bare modifiers are not consumed`() {
        assertNull(KeyEventMapper.map(down(KeyEvent.KEYCODE_BACK)))
        assertNull(KeyEventMapper.map(down(KeyEvent.KEYCODE_VOLUME_UP)))
        assertNull(KeyEventMapper.map(down(KeyEvent.KEYCODE_SHIFT_LEFT)))
    }

    private fun connection(
        sink: TerminalInputSink,
        composing: MutableList<String?> = mutableListOf(),
    ): TerminalInputConnection {
        val view = View(ApplicationProvider.getApplicationContext())
        return TerminalInputConnection(view, sink) { composing += it }
    }

    @Test
    fun `IME commitText sends text and newline becomes enter via the sink`() {
        val sink = RecordingSink()
        val ic = connection(sink)
        ic.commitText("ls -la", 1)
        ic.commitText("\n", 1)
        assertEquals(listOf("text:ls -la", "text:\n"), sink.log)
    }

    @Test
    fun `IME composing text is held until commit or finish`() {
        val sink = RecordingSink()
        val composing = mutableListOf<String?>()
        val ic = connection(sink, composing)
        ic.setComposingText("ni", 1)
        ic.setComposingText("nih", 1)
        assertEquals(emptyList<String>(), sink.log)
        ic.commitText("你好", 1)
        ic.setComposingText("hel", 1)
        ic.finishComposingText()
        assertEquals(listOf("text:你好", "text:hel"), sink.log)
        assertEquals(listOf("ni", "nih", null, "hel", null), composing)
    }

    @Test
    fun `a key that bypasses the IME is preceded by the pending composition`() {
        val sink = RecordingSink()
        val ic = connection(sink)
        sink.sendText("e")
        ic.setComposingText("ch", 1)
        assertTrue(ic.commitComposing())
        sink.sendText("o")
        assertEquals(listOf("text:e", "text:ch", "text:o"), sink.log)
        assertEquals(false, ic.commitComposing())
        assertEquals(3, sink.log.size)
    }

    @Test
    fun `IME deleteSurroundingText edits composition or sends backspace`() {
        val sink = RecordingSink()
        val ic = connection(sink)
        ic.setComposingText("abc", 1)
        ic.deleteSurroundingText(1, 0) // inside the composition: nothing sent yet
        assertEquals(emptyList<String>(), sink.log)
        ic.finishComposingText()
        ic.deleteSurroundingText(2, 1)
        assertTrue(sink.log.first().startsWith("text:"))
        assertEquals(listOf("key:DEL", "key:DEL", "key:ESC[3~"), sink.log.drop(1))
    }

    @Test
    fun `IME key events go through the key mapper`() {
        val sink = RecordingSink()
        val ic = connection(sink)
        ic.sendKeyEvent(down(KeyEvent.KEYCODE_DEL))
        ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0))
        ic.sendKeyEvent(down(KeyEvent.KEYCODE_ENTER))
        assertEquals(listOf("key:DEL", "key:^M"), sink.log)
    }

    @Test
    fun `editor info asks for a normal keyboard without autocorrect, capitals or fullscreen`() {
        val info = EditorInfo()
        TerminalInputConnection.configure(info)
        assertEquals(android.text.InputType.TYPE_CLASS_TEXT, info.inputType)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0)
        assertEquals(0, info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
    }

    @Test
    fun `extra keys row reports sticky modifiers and keys`() {
        val pressed = mutableListOf<ExtraKey>()
        compose.setContent { ExtraKeysRow(ctrlActive = true, altActive = false, onKey = { pressed += it }) }
        compose.onNodeWithContentDescription("Control (sticky)").performClick()
        compose.onNodeWithContentDescription("Escape").performClick()
        assertEquals(listOf(ExtraKey.Ctrl, ExtraKey.Esc), pressed)
        assertEquals(KeyInput(Key.Escape), ExtraKey.Esc.toKeyInput())
        assertNull(ExtraKey.Ctrl.toKeyInput())
    }
}

private fun String.escaped(): String =
    buildString {
        for (ch in this@escaped) {
            when {
                ch == '\u001b' -> append("ESC")
                ch == '\u007f' -> append("DEL")
                ch < ' ' -> append('^').append((ch.code + 64).toChar())
                else -> append(ch)
            }
        }
    }
