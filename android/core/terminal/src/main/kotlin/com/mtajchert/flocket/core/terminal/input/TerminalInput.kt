package com.mtajchert.flocket.core.terminal.input

import android.text.Editable
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import com.mtajchert.flocket.core.terminal.controller.TerminalController
import com.mtajchert.flocket.core.terminal.engine.Key
import com.mtajchert.flocket.core.terminal.engine.KeyInput

/** Where keyboard input goes. [TerminalController] is the production sink. */
interface TerminalInputSink {
    fun sendKey(key: KeyInput): Boolean

    fun sendText(text: String): Boolean

    fun paste(text: String): Boolean
}

/** Adapts a [TerminalController] to [TerminalInputSink]. */
fun TerminalController.asInputSink(): TerminalInputSink =
    object : TerminalInputSink {
        override fun sendKey(key: KeyInput) = this@asInputSink.sendKey(key)

        override fun sendText(text: String) = this@asInputSink.sendText(text)

        override fun paste(text: String) = this@asInputSink.paste(text)
    }

/** Maps Android hardware/IME [KeyEvent]s to [KeyInput]. */
object KeyEventMapper {
    /** Returns null for keys the terminal does not handle (Back, volume, bare modifiers, …). */
    fun map(event: KeyEvent): KeyInput? {
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        val shift = event.isShiftPressed
        val meta = event.isMetaPressed
        val special =
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    Key.Enter
                }

                KeyEvent.KEYCODE_TAB -> {
                    Key.Tab
                }

                KeyEvent.KEYCODE_DEL -> {
                    Key.Backspace
                }

                KeyEvent.KEYCODE_FORWARD_DEL -> {
                    Key.Delete
                }

                KeyEvent.KEYCODE_ESCAPE -> {
                    Key.Escape
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    Key.Up
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    Key.Down
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    Key.Left
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    Key.Right
                }

                KeyEvent.KEYCODE_MOVE_HOME -> {
                    Key.Home
                }

                KeyEvent.KEYCODE_MOVE_END -> {
                    Key.End
                }

                KeyEvent.KEYCODE_PAGE_UP -> {
                    Key.PageUp
                }

                KeyEvent.KEYCODE_PAGE_DOWN -> {
                    Key.PageDown
                }

                KeyEvent.KEYCODE_INSERT -> {
                    Key.Insert
                }

                in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> {
                    Key.entries[
                        Key.F1.ordinal + event.keyCode -
                            KeyEvent.KEYCODE_F1,
                    ]
                }

                else -> {
                    null
                }
            }
        if (special != null) return KeyInput(special, ctrl = ctrl, alt = alt, shift = shift, meta = meta)
        if (event.keyCode == KeyEvent.KEYCODE_SPACE) {
            return KeyInput(Key.Space, ctrl = ctrl, alt = alt, shift = shift, meta = meta)
        }
        if (event.action == KeyEvent.ACTION_MULTIPLE && event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            val chars = event.characters ?: return null
            return KeyInput(Key.Character, text = chars)
        }
        val state = event.metaState
        // AltGr (right Alt) composes characters on many layouts (e.g. Polish ą): keep it as text.
        if (state and KeyEvent.META_ALT_RIGHT_ON != 0 && !ctrl) {
            val altGr = event.getUnicodeChar(state)
            if (altGr != 0 && altGr != event.getUnicodeChar(state and ALT_MASK.inv())) {
                return KeyInput(Key.Character, text = codepoint(altGr), shift = shift)
            }
        }
        val uc = event.getUnicodeChar(state and (ALT_MASK or CTRL_MASK or META_MASK).inv())
        if (uc == 0 || uc and KeyCharacterMapDeadBit != 0) return null
        return KeyInput(Key.Character, text = codepoint(uc), ctrl = ctrl, alt = alt, shift = shift, meta = meta)
    }

    private fun codepoint(cp: Int) = String(Character.toChars(cp))

    private const val KeyCharacterMapDeadBit = 0x80000000.toInt()
    private const val ALT_MASK = KeyEvent.META_ALT_MASK
    private const val CTRL_MASK = KeyEvent.META_CTRL_MASK
    private const val META_MASK = KeyEvent.META_META_MASK
}

/**
 * IME bridge. Committed text goes straight to the terminal; composing text (CJK, swipe typing,
 * Gboard word composition) stays in a private editable and is shown as an overlay until it is
 * committed or finished. Deletes with nothing composed become Backspace/Delete keys.
 */
class TerminalInputConnection(
    view: View,
    private val sink: TerminalInputSink,
    private val onComposingChanged: (String?) -> Unit = {},
) : BaseInputConnection(view, true) {
    override fun commitText(
        text: CharSequence?,
        newCursorPosition: Int,
    ): Boolean {
        super.commitText(text, newCursorPosition)
        flush()
        return true
    }

    override fun setComposingText(
        text: CharSequence?,
        newCursorPosition: Int,
    ): Boolean {
        super.setComposingText(text, newCursorPosition)
        onComposingChanged(editable?.toString()?.ifEmpty { null })
        return true
    }

    override fun finishComposingText(): Boolean {
        super.finishComposingText()
        flush()
        return true
    }

    override fun deleteSurroundingText(
        beforeLength: Int,
        afterLength: Int,
    ): Boolean {
        val e = editable
        if (e != null && e.isNotEmpty()) {
            super.deleteSurroundingText(beforeLength, afterLength)
            onComposingChanged(e.toString().ifEmpty { null })
            return true
        }
        repeat(beforeLength) { sink.sendKey(KeyInput(Key.Backspace)) }
        repeat(afterLength) { sink.sendKey(KeyInput(Key.Delete)) }
        return true
    }

    override fun deleteSurroundingTextInCodePoints(
        beforeLength: Int,
        afterLength: Int,
    ): Boolean = deleteSurroundingText(beforeLength, afterLength)

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_MULTIPLE) {
            // A key while composing commits the composition first (e.g. Enter after CJK input).
            flush()
            KeyEventMapper.map(event)?.let { sink.sendKey(it) }
        }
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        flush()
        sink.sendKey(KeyInput(Key.Enter))
        return true
    }

    /**
     * Send a pending composition now, before a key that bypasses this connection (a hardware or
     * injected key reaching the view, an extra key). Without it the key would overtake the
     * composed text: `echo` typed as e + [ch composing] + o arrived as `eoch`. Returns true when
     * something was sent; the caller should then restart input so the IME drops its composition.
     */
    fun commitComposing(): Boolean {
        val pending = editable?.isNotEmpty() == true
        flush()
        return pending
    }

    private fun flush() {
        val e: Editable = editable ?: return
        if (e.isNotEmpty()) {
            val text = e.toString()
            e.clear()
            sink.sendText(text)
        }
        BaseInputConnection.removeComposingSpans(e)
        onComposingChanged(null)
    }

    companion object {
        /**
         * A normal text keyboard (suggestions, swipe, voice, learning) without autocorrect or
         * auto-capitals: no AUTO_CORRECT, MULTI_LINE or CAP_* flags, so `ls` is sent as typed.
         * No fullscreen extract UI.
         */
        fun configure(outAttrs: EditorInfo) {
            outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT
            outAttrs.imeOptions =
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_ACTION_NONE
            outAttrs.initialSelStart = 0
            outAttrs.initialSelEnd = 0
        }
    }
}
