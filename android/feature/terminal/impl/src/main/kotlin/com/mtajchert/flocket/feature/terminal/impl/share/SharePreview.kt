package com.mtajchert.flocket.feature.terminal.impl.share

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.max

/** How the viewer shows a downloaded file. */
enum class PreviewKind { Image, Text, Other }

object SharePreview {
    const val MAX_IMAGE_SIDE = 2048
    const val MAX_TEXT_BYTES = 1024 * 1024

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

    /** Image by MIME type; text by MIME type or extension, and only when the whole content is valid UTF-8. */
    fun classify(
        name: String,
        mime: String,
        bytes: ByteArray,
    ): PreviewKind =
        when {
            mime.startsWith("image/") -> {
                PreviewKind.Image
            }

            (mime.startsWith("text/") || extension(name) in textExtensions) && isUtf8(textPrefix(bytes)) -> {
                PreviewKind.Text
            }

            else -> {
                PreviewKind.Other
            }
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
