package com.mtajchert.flocket.core.terminal.ghostty

/**
 * Raw JNI surface of `libflocket_ghostty.so` (src/main/cpp/flocket_ghostty.cpp). Methods are bound
 * with RegisterNatives in `JNI_OnLoad`. `handle` is an opaque native `Engine*`.
 *
 * Not thread-safe; callers ([GhosttyTerminalEngine]) confine a handle to one thread.
 */
internal object GhosttyNative {
    @Volatile private var loadError: Throwable? = null

    @Volatile private var loaded = false

    /** Loads libghostty-vt and the JNI shim once; throws [UnsatisfiedLinkError] with a clear message. */
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loadError?.let {
            throw UnsatisfiedLinkError(
                "Ghostty terminal engine unavailable: ${it.message}",
            ).initCause(it)
        }
        try {
            System.loadLibrary("ghostty-vt")
            System.loadLibrary("flocket_ghostty")
            loaded = true
        } catch (t: Throwable) {
            loadError = t
            throw UnsatisfiedLinkError(
                "Ghostty terminal engine unavailable (libghostty-vt / libflocket_ghostty not loadable " +
                    "for this ABI): ${t.message}",
            ).initCause(t)
        }
    }

    @JvmStatic external fun nativeCreate(
        cols: Int,
        rows: Int,
        scrollbackLines: Int,
    ): Long

    @JvmStatic external fun nativeFree(handle: Long)

    @JvmStatic external fun nativeFeed(
        handle: Long,
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )

    @JvmStatic external fun nativeResize(
        handle: Long,
        cols: Int,
        rows: Int,
    )

    @JvmStatic external fun nativeSetTheme(
        handle: Long,
        fg: Int,
        bg: Int,
        cursor: Int,
        palette16: IntArray,
    )

    /** Header layout: see `nSnapshot` in the C++ source and [SnapshotHeader]. */
    @JvmStatic external fun nativeSnapshot(
        handle: Long,
        full: Boolean,
        cols: Int,
        rows: Int,
        codepoints: IntArray,
        foreground: IntArray,
        background: IntArray,
        flags: IntArray,
        dirty: IntArray,
        header: IntArray,
    )

    @JvmStatic external fun nativeFetchGraphemes(
        handle: Long,
        out: IntArray,
    )

    @JvmStatic external fun nativeScroll(
        handle: Long,
        delta: Int,
    )

    @JvmStatic external fun nativeScrollToBottom(handle: Long)

    @JvmStatic external fun nativeEncodeKey(
        handle: Long,
        key: Int,
        mods: Int,
        text: ByteArray?,
        textLen: Int,
    ): ByteArray

    @JvmStatic external fun nativeEncodePaste(
        handle: Long,
        text: ByteArray,
    ): ByteArray

    @JvmStatic external fun nativeDrainEvents(handle: Long): ByteArray?

    @JvmStatic external fun nativeTitle(handle: Long): ByteArray

    @JvmStatic external fun nativeText(
        handle: Long,
        historyLines: Int,
    ): ByteArray

    // ---- Smooth scrolling and selection (readRow / historyBase / textRange) ----

    /** Returns the grapheme ints needed (retry with a bigger [graphemes] if more than its size), -1 = no row. */
    @JvmStatic external fun nativeReadRow(
        handle: Long,
        screenRow: Int,
        cols: Int,
        codepoints: IntArray,
        foreground: IntArray,
        background: IntArray,
        flags: IntArray,
        graphemes: IntArray,
    ): Int

    @JvmStatic external fun nativeHistoryBase(handle: Long): Long

    @JvmStatic external fun nativeScrollToRow(
        handle: Long,
        screenRow: Int,
    )

    @JvmStatic external fun nativeTextRange(
        handle: Long,
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int,
    ): ByteArray
}

/** Indices into the header IntArray filled by `nativeSnapshot`. */
internal object SnapshotHeader {
    const val SIZE = 16
    const val CURSOR_COL = 0
    const val CURSOR_ROW = 1
    const val CURSOR_VISIBLE = 2
    const val CURSOR_STYLE = 3
    const val SCROLLBACK_ROWS = 4
    const val VIEWPORT_OFFSET = 5
    const val GRAPHEME_INTS = 6
    const val ALT_SCREEN = 9
    const val TITLE_GENERATION = 11
}
