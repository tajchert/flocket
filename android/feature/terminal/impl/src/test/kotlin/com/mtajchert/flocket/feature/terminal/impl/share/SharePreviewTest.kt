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
        assertEquals(PreviewKind.Image, SharePreview.classify("a.png", "image/png", ByteArray(3)))
        assertEquals(PreviewKind.Text, SharePreview.classify("notes.md", "application/octet-stream", text))
        assertEquals(PreviewKind.Text, SharePreview.classify("x", "text/plain", text))
        assertEquals(PreviewKind.Other, SharePreview.classify("a.txt", "text/plain", byteArrayOf(0xC3.toByte(), 0x28)))
        assertEquals(PreviewKind.Other, SharePreview.classify("a.bin", "application/octet-stream", text))
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
        assertTrue(SharePreview.classify("a.txt", "text/plain", bytes) == PreviewKind.Text)
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
    fun `cache files are named safely and old ones pruned`() {
        val file = ShareFiles.write(tmp.root, "id/../1", "../evil.txt", byteArrayOf(1))
        assertEquals(tmp.root.resolve("shares").canonicalPath, file.parentFile.parentFile.canonicalPath)
        assertEquals(".._evil.txt", file.name)
        val old = ShareFiles.write(tmp.root, "old", "o.txt", byteArrayOf(1))
        old.setLastModified(1_000)
        ShareFiles.prune(tmp.root, nowMillis = 1_000 + ShareFiles.MAX_AGE_MILLIS + 1)
        assertFalse(old.exists())
        assertFalse("empty folder removed", File(tmp.root, "shares/old").exists())
        assertTrue("fresh file kept", file.exists())
    }
}
