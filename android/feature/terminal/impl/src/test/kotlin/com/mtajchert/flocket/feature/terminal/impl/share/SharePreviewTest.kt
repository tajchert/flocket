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
    fun `text is cut at one megabyte`() {
        val (short, cutShort) = SharePreview.text("abc".toByteArray())
        assertEquals("abc", short)
        assertFalse(cutShort)
        val (long, cut) = SharePreview.text(ByteArray(SharePreview.MAX_TEXT_BYTES + 5) { 'a'.code.toByte() })
        assertEquals(SharePreview.MAX_TEXT_BYTES, long.length)
        assertTrue(cut)
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
