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

    @Test
    fun `prune walks the three folder levels and removes empty ones`() {
        val old = file("a/0123456789ab", "old.apk", ageMillis = 30 * hour)
        val fresh = file("a/ba9876543210", "fresh.apk", ageMillis = 2 * hour)
        ShareFiles.prune(tmp.root, now)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertFalse(File(tmp.root, "a/0123456789ab").exists())
    }

    @Test
    fun `prune never deletes a part modified in the last ten minutes`() {
        val running = file("a/k1", "x.apk.part", size = 50, ageMillis = 5 * 60_000)
        val stalePart = file("a/k2", "y.apk.part", size = 90, ageMillis = 30 * 60_000)
        val other = file("a/k3", "z.apk", size = 40, ageMillis = 20 * 60_000)
        ShareFiles.prune(tmp.root, now, maxTotalBytes = 100)
        assertTrue("a running download", running.exists())
        assertFalse(stalePart.exists())
        assertTrue(other.exists())
    }

    @Test
    fun `same name in one share gets separate folders per machine and path`() {
        val a = ShareFiles.target(tmp.root, "s", null, "/a/app.apk")
        val b = ShareFiles.target(tmp.root, "s", null, "/b/app.apk")
        val m = ShareFiles.target(tmp.root, "s", "rohan", "/a/app.apk")
        assertEquals(listOf("app.apk"), listOf(a, b, m).map { it.name }.distinct())
        assertEquals(3, setOf(a.parentFile, b.parentFile, m.parentFile).size)
        assertEquals(a, ShareFiles.target(tmp.root, "s", null, "/a/app.apk"))
    }
}
