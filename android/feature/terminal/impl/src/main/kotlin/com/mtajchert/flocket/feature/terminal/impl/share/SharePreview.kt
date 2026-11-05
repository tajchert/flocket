package com.mtajchert.flocket.feature.terminal.impl.share

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.max

/** How the viewer shows a downloaded file. */
enum class PreviewKind { Image, Text, Other }

object SharePreview {
    const val MAX_IMAGE_SIDE = 2048
    const val MAX_TEXT_BYTES = 1024 * 1024
    const val PREFIX_BYTES = 64 * 1024
    const val MAX_IMAGE_BYTES = 32L * 1024 * 1024

    private val textExtensions =
        setOf(
            "txt",
            "md",
            "markdown",
            "log",
            "json",
            "yaml",
            "yml",
            "toml",
            "xml",
            "csv",
            "tsv",
            "ini",
            "cfg",
            "conf",
            "kt",
            "kts",
            "java",
            "py",
            "rs",
            "go",
            "js",
            "ts",
            "tsx",
            "jsx",
            "c",
            "h",
            "cpp",
            "hpp",
            "sh",
            "swift",
            "html",
            "css",
            "sql",
            "diff",
            "patch",
            "gradle",
            "properties",
        )

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    /**
     * Image by MIME type up to [MAX_IMAGE_BYTES]; text by MIME type or extension, and only when the [prefix] (the
     * first [PREFIX_BYTES] of a file of [size] bytes) is valid UTF-8.
     */
    fun classify(
        name: String,
        mime: String,
        prefix: ByteArray,
        size: Long,
    ): PreviewKind =
        when {
            mime.startsWith("image/") -> {
                if (size <= MAX_IMAGE_BYTES) PreviewKind.Image else PreviewKind.Other
            }

            (mime.startsWith("text/") || extension(name) in textExtensions) &&
                isUtf8(whole(prefix, size)) -> {
                PreviewKind.Text
            }

            else -> {
                PreviewKind.Other
            }
        }

    /** [prefix] without a character cut by the end of the prefix, unless it already is the whole file. */
    private fun whole(
        prefix: ByteArray,
        size: Long,
    ): ByteArray {
        if (size <= prefix.size) return prefix
        var end = prefix.size
        var back = 0
        while (end > 0 && back < 4 && (prefix[end - 1].toInt() and 0xC0) == 0x80) {
            end--
            back++
        }
        // `end - 1` is now a lead byte (or ASCII): drop it too when its character is incomplete.
        if (end > 0 && (prefix[end - 1].toInt() and 0x80) != 0) end--
        return prefix.copyOf(end)
    }

    fun isUtf8(bytes: ByteArray): Boolean =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: java.nio.charset.CharacterCodingException) {
            false
        }

    /** The part of [bytes] shown as text: at most [MAX_TEXT_BYTES], cut before a split multibyte character. */
    fun textPrefix(bytes: ByteArray): ByteArray {
        if (bytes.size <= MAX_TEXT_BYTES) return bytes
        var end = MAX_TEXT_BYTES
        // `end` is the first excluded byte: if it continues a character, that character is dropped whole.
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOf(end)
    }

    /** The shown text of [bytes] as lines (no line lost, a last partial line kept), and whether it was cut. */
    fun textLines(bytes: ByteArray): Pair<List<String>, Boolean> {
        val text = String(textPrefix(bytes), Charsets.UTF_8)
        val lines = text.split('\n').map { it.trimEnd('\r') }
        // A final newline ends the last line; it does not start another.
        return (if (text.endsWith('\n')) lines.dropLast(1) else lines) to (bytes.size > MAX_TEXT_BYTES)
    }

    /** Decode size that keeps the long side within [MAX_IMAGE_SIDE]; the original size when it already fits. */
    fun targetSize(
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= MAX_IMAGE_SIDE) return width to height
        val scale = MAX_IMAGE_SIDE.toDouble() / long
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }
}
