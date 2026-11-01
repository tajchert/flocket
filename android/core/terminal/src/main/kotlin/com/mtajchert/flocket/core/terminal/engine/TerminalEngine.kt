package com.mtajchert.flocket.core.terminal.engine

/**
 * A VT terminal emulator: parses output bytes into screen state and encodes user input.
 *
 * Production: `core:terminal-ghostty` (libghostty-vt over JNI, ADR-0003). Tests, previews and
 * screenshots: `FakeTerminalEngine`. Everything above this interface (renderer, input, session
 * glue) must work with either.
 *
 * Threading: NOT thread-safe. The owner confines all calls to one thread/dispatcher
 * (e.g. `Dispatchers.Default.limitedParallelism(1)` per terminal). [close] releases native memory;
 * using an engine after [close] is a bug.
 *
 * The core already answers terminal queries (DA, color queries, XTVERSION…) on the viewer's behalf
 * (protocol/SPEC.md §7.3): engines must NOT write replies back to the host. They are dropped.
 */
interface TerminalEngine : AutoCloseable {
    val cols: Int
    val rows: Int

    /** Parse output bytes (arbitrary chunking: split UTF-8 and escape sequences are fine). */
    fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    )

    fun resize(
        cols: Int,
        rows: Int,
    )

    /** Colors used to resolve default/palette colors into ARGB in snapshots. */
    fun setTheme(theme: TerminalTheme)

    /**
     * Copy the visible viewport into [target] (reused between frames to avoid allocation; it is
     * resized if dimensions changed) and return it. Clears the engine's dirty tracking.
     */
    fun snapshot(target: ScreenSnapshot? = null): ScreenSnapshot

    /** Scroll the viewport into scrollback: negative = up (older), positive = down. */
    fun scrollViewport(deltaRows: Int)

    fun scrollToBottom()

    /**
     * Put screen row [screenRow] ([readRow] space) at the top of the viewport, clamped to the
     * scrollback; a row at or past the active area's top means the bottom (which follows output).
     * Absolute: it does not depend on how stale the caller's last snapshot is.
     */
    fun scrollViewportTo(screenRow: Int)

    /** [ScreenSnapshot.historyBase] as of now (rows may have been evicted since the last snapshot). */
    fun historyBase(): Long

    /** Encode a key press for the program's current modes (app-cursor, kitty keyboard, …). */
    fun encodeKey(event: KeyInput): ByteArray

    /** Encode pasted text (bracketed paste when the program enabled it). */
    fun encodePaste(text: String): ByteArray

    /**
     * Encode a vertical swipe of [lines] rows (negative = toward older output) for the program, at
     * viewport cell ([col], [row]), following the current [ScreenSnapshot.scrollInput]: one mouse
     * wheel event per row, or one Up/Down key per row. Empty for [ScrollInput.Scrollback] and
     * [ScrollInput.None].
     */
    fun encodeScroll(
        lines: Int,
        col: Int,
        row: Int,
    ): ByteArray = ByteArray(0)

    /** Side effects produced since the last call (title, bell, clipboard, …). */
    fun drainEvents(): List<TerminalEvent>

    /** Plain text of the current selection-free viewport + [historyLines] of scrollback (copy). */
    fun text(historyLines: Int = 0): String

    /**
     * Copy one row of the screen buffer into row 0 of [target] (resized to cols x 1), resolved like
     * [snapshot]. [screenRow] counts from the oldest retained scrollback row (0) to
     * `scrollbackRows + rows - 1`; the viewport's top row is `scrollbackRows - viewportOffset`. Does
     * not move the viewport or touch [snapshot]'s dirty tracking. Returns false (target untouched)
     * when the row does not exist. Used for the row above the viewport while scrolling smoothly,
     * not for whole frames.
     */
    fun readRow(
        screenRow: Int,
        target: ScreenSnapshot,
    ): Boolean = false

    /**
     * Plain text from ([startRow], [startCol]) to ([endRow], [endCol]) inclusive, rows in the
     * [readRow] space (selection copy). Soft-wrapped rows are joined without a newline, trailing
     * blanks of each line are trimmed, a wide character or grapheme cluster appears once.
     */
    fun textRange(
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int,
    ): String = ""
}

/** ARGB colors (0xAARRGGBB). [palette] holds the 16 ANSI colors. */
data class TerminalTheme(
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    val palette: IntArray,
) {
    init {
        require(palette.size == 16) { "palette must have 16 entries" }
    }

    override fun equals(other: Any?): Boolean =
        other is TerminalTheme &&
            foreground == other.foreground &&
            background == other.background &&
            cursor == other.cursor &&
            palette.contentEquals(other.palette)

    override fun hashCode(): Int = ((foreground * 31 + background) * 31 + cursor) * 31 + palette.contentHashCode()
}

/**
 * The visible screen as flat, row-major primitive arrays (index = row * cols + col), resolved
 * against the theme so the renderer needs no VT knowledge.
 */
class ScreenSnapshot(
    cols: Int,
    rows: Int,
) {
    var cols: Int = cols
        private set
    var rows: Int = rows
        private set

    /** First code point of each cell's grapheme; 0 = empty. */
    var codepoints: IntArray = IntArray(cols * rows)
        private set

    /** Full text for cells whose grapheme has more than one code point (emoji, combining marks). */
    val graphemes: MutableMap<Int, String> = HashMap()

    /** Resolved ARGB colors (inverse/dim already applied). */
    var foreground: IntArray = IntArray(cols * rows)
        private set
    var background: IntArray = IntArray(cols * rows)
        private set

    /** Bit set of [CellFlags]. */
    var flags: IntArray = IntArray(cols * rows)
        private set

    /** Rows changed since the previous snapshot into this instance. */
    var dirtyRows: BooleanArray = BooleanArray(rows)
        private set

    var cursorCol: Int = 0
    var cursorRow: Int = 0
    var cursorVisible: Boolean = true
    var cursorStyle: CursorStyle = CursorStyle.Block

    /** Rows of scrollback above the viewport, and how far the viewport is scrolled up into it. */
    var scrollbackRows: Int = 0
    var viewportOffset: Int = 0

    /**
     * Rows that have left the top of the screen buffer since the engine was created (scrollback
     * eviction, clear scrollback, reset); never decreases. `historyBase + screen row` (see
     * [TerminalEngine.readRow]) is a row id that stays on the same content while output arrives.
     */
    var historyBase: Long = 0

    /** The alternate screen is active (no scrollback; row ids are not comparable across a switch). */
    var altScreen: Boolean = false

    /** What a vertical swipe scrolls right now (see [TerminalEngine.encodeScroll]). */
    var scrollInput: ScrollInput = ScrollInput.Scrollback

    var title: String = ""

    fun resize(
        cols: Int,
        rows: Int,
    ) {
        if (cols == this.cols && rows == this.rows) return
        this.cols = cols
        this.rows = rows
        codepoints = IntArray(cols * rows)
        foreground = IntArray(cols * rows)
        background = IntArray(cols * rows)
        flags = IntArray(cols * rows)
        dirtyRows = BooleanArray(rows) { true }
        graphemes.clear()
    }

    fun index(
        col: Int,
        row: Int,
    ): Int = row * cols + col

    fun textAt(index: Int): String =
        graphemes[index] ?: codepoints[index].let { if (it == 0) " " else String(Character.toChars(it)) }
}

object CellFlags {
    const val BOLD = 1
    const val ITALIC = 1 shl 1
    const val UNDERLINE = 1 shl 2
    const val STRIKETHROUGH = 1 shl 3
    const val FAINT = 1 shl 4
    const val INVISIBLE = 1 shl 5
    const val BLINK = 1 shl 6

    /** First half of a double-width character. */
    const val WIDE = 1 shl 7

    /** Second half of a double-width character: draw nothing. */
    const val WIDE_SPACER = 1 shl 8

    /** Hyperlink (OSC 8) present. */
    const val HYPERLINK = 1 shl 9
}

enum class CursorStyle { Block, Underline, Bar }

/**
 * Where a vertical swipe goes, like a desktop terminal's mouse wheel: the program gets wheel events
 * while it tracks the mouse (any screen), Up/Down keys on the alternate screen with alternate scroll
 * mode (?1007, on by default), otherwise the view scrolls the scrollback.
 */
enum class ScrollInput {
    Scrollback,
    MouseWheel,
    ArrowKeys,

    /** Alternate screen with alternate scroll mode off: swipes do nothing. */
    None,
}

/** A key press from IME, hardware keyboard or the extra-keys row. */
data class KeyInput(
    val key: Key,
    /** Text produced by the key (for printable keys), e.g. "a", "A", "é". */
    val text: String? = null,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
)

/** Physical/logical keys the encoder understands. Printable characters use [Character] + text. */
enum class Key {
    Character,
    Enter,
    Tab,
    Backspace,
    Escape,
    Space,
    Up,
    Down,
    Left,
    Right,
    Home,
    End,
    PageUp,
    PageDown,
    Insert,
    Delete,
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,
}

sealed interface TerminalEvent {
    data class TitleChanged(
        val title: String,
    ) : TerminalEvent

    data object Bell : TerminalEvent

    /** OSC 52 clipboard write requested by the program. The UI decides whether to honor it. */
    data class ClipboardWrite(
        val text: String,
    ) : TerminalEvent

    /** OSC 7 working directory. */
    data class WorkingDirectoryChanged(
        val uri: String,
    ) : TerminalEvent

    /** OSC 9/777 desktop notification. */
    data class Notification(
        val title: String,
        val body: String,
    ) : TerminalEvent
}
