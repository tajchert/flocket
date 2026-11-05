package com.mtajchert.flocket.feature.terminal.impl.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShareFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 10L * 24 * 3_600_000

    private fun file(
        dir: String,
        name: String,
        size: Int = 10,
        ageMillis: Long = 0,
    ): File {
        val d = File(tmp.root, dir).apply { mkdirs() }
        return File(d, name).apply {
            writeBytes(ByteArray(size))
            setLastModified(now - ageMillis)
        }
    }

    private val hour = 3_600_000L

    @Test
    fun `prune deletes old files and parts`() {
        val old = file("a", "old.txt", ageMillis = 25 * hour)
        val oldPart = file("b", "x.bin.part", ageMillis = 30 * hour)
        val fresh = file("a", "fresh.txt", ageMillis = 2 * hour)
        ShareFiles.prune(tmp.root, now)
        assertFalse(old.exists())
        assertFalse(oldPart.exists())
        assertTrue(fresh.exists())
        assertFalse("empty folder removed", File(tmp.root, "b").exists())
    }

    @Test
    fun `prune keeps the total under the cap oldest first`() {
        val a = file("a", "1", size = 40, ageMillis = 5 * hour)
        val b = file("a", "2", size = 40, ageMillis = 3 * hour)
        val c = file("b", "3", size = 40, ageMillis = 1 * hour)
        ShareFiles.prune(tmp.root, now, maxTotalBytes = 100)
        assertFalse(a.exists())
        assertTrue(b.exists())
        assertTrue(c.exists())
        assertEquals(80, listOf(b, c).sumOf { it.length() }.toInt())
    }

    @Test
    fun `prune never deletes kept files`() {
        val old = file("a", "old", ageMillis = 48 * hour)
        val big = file("a", "big", size = 90, ageMillis = 5 * hour)
        val other = file("a", "other", size = 40, ageMillis = 1 * hour)
        ShareFiles.prune(tmp.root, now, keep = setOf(old, big), maxTotalBytes = 100)
        assertTrue(old.exists())
        assertTrue(big.exists())
        assertFalse(other.exists())
    }
}
