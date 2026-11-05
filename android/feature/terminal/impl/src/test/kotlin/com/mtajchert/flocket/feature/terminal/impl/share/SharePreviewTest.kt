package com.mtajchert.flocket.feature.terminal.impl.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SharePreviewTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `classifies by mime and content`() {
        val text = "héllo".toByteArray()
        assertEquals(PreviewKind.Image, SharePreview.classify("a.png", "image/png", ByteArray(3), 3))
        assertEquals(
            PreviewKind.Text,
            SharePreview.classify("notes.md", "application/octet-stream", text, text.size.toLong()),
        )
        assertEquals(PreviewKind.Text, SharePreview.classify("x", "text/plain", text, text.size.toLong()))
        assertEquals(
            PreviewKind.Other,
            SharePreview.classify("a.txt", "text/plain", byteArrayOf(0xC3.toByte(), 0x28), 2),
        )
        assertEquals(
            PreviewKind.Other,
            SharePreview.classify("a.bin", "application/octet-stream", text, text.size.toLong()),
        )
    }

    @Test
    fun `image over 32 MiB is not previewed`() {
        val max = SharePreview.MAX_IMAGE_BYTES
        assertEquals(PreviewKind.Image, SharePreview.classify("a.png", "image/png", ByteArray(8), max))
        assertEquals(PreviewKind.Other, SharePreview.classify("a.png", "image/png", ByteArray(8), max + 1))
    }

    @Test
    fun `a character cut by the prefix does not hide a text preview`() {
        val prefix = ByteArray(SharePreview.PREFIX_BYTES - 1) { 'a'.code.toByte() } + "é".toByteArray().copyOf(1)
        assertEquals(PreviewKind.Text, SharePreview.classify("a.txt", "text/plain", prefix, 10L * prefix.size))
    }

    @Test
    fun `short text keeps every line and the last partial line`() {
        assertEquals(listOf("a", "", "b"), SharePreview.textLines("a\n\nb".toByteArray()).first)
        assertEquals(listOf("a", "b"), SharePreview.textLines("a\r\nb\n".toByteArray()).first)
        assertFalse(SharePreview.textLines("abc".toByteArray()).second)
    }

    @Test
    fun `one megabyte of lines splits without losing a line`() {
        val line = "0123456789012345678901234567890123456789012345678\n" // 50 bytes
        val count = 20_000
        val text = (0 until count).joinToString("") { line } + "tail"
        val bytes = text.toByteArray()
        assertTrue(bytes.size < SharePreview.MAX_TEXT_BYTES)
        val (lines, cut) = SharePreview.textLines(bytes)
        assertFalse(cut)
        assertEquals(count + 1, lines.size)
        assertEquals("tail", lines.last())
        assertEquals(line.trimEnd(), lines.first())
    }

    @Test
    fun `text over a megabyte is cut before a split character`() {
        // "é" is two bytes; make the 1 MiB boundary fall between them.
        val bytes = ByteArray(SharePreview.MAX_TEXT_BYTES - 1) { 'a'.code.toByte() } + "éé".toByteArray()
        assertTrue(
            SharePreview.classify(
                "a.txt",
                "text/plain",
                bytes.copyOf(SharePreview.PREFIX_BYTES),
                bytes.size.toLong(),
            ) ==
                PreviewKind.Text,
        )
        val (lines, cut) = SharePreview.textLines(bytes)
        assertTrue(cut)
        assertEquals(SharePreview.MAX_TEXT_BYTES - 1, lines.single().length)
        assertFalse('\uFFFD' in lines.single())
    }

    @Test
    fun `image target keeps the long side within 2048`() {
        assertEquals(1000 to 500, SharePreview.targetSize(1000, 500))
        assertEquals(2048 to 1024, SharePreview.targetSize(4096, 2048))
        assertEquals(512 to 2048, SharePreview.targetSize(1000, 4000))
    }

    @Test
    fun `cache files are named safely`() {
        val root = tmp.root.resolve("shares")
        val file = ShareFiles.target(root, "id/../1", null, "/x/../evil.txt")
        assertEquals(root.canonicalPath, file.parentFile.parentFile.parentFile.canonicalPath)
        assertEquals("evil.txt", file.name)
        assertEquals("evil.txt.part", ShareFiles.partOf(file).name)
    }
}
