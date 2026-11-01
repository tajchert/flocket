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
            mime.startsWith("image/") -> PreviewKind.Image
            (mime.startsWith("text/") || extension(name) in textExtensions) && isUtf8(bytes) -> PreviewKind.Text
            else -> PreviewKind.Other
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

    /** The first [MAX_TEXT_BYTES] of [bytes] as text, and whether it was cut. */
    fun text(bytes: ByteArray): Pair<String, Boolean> {
        val cut = bytes.size > MAX_TEXT_BYTES
        return String(bytes, 0, minOf(bytes.size, MAX_TEXT_BYTES), Charsets.UTF_8) to cut
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
