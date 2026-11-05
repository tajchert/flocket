package com.mtajchert.flocket.feature.terminal.impl.share

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Files handed to other apps live in `cacheDir/shares/<share id>/<path key>/<name>` (the FileProvider's `shares`
 * path). The path key tells apart two files of one share that have the same name.
 */
object ShareFiles {
    const val DIRECTORY = "shares"
    const val PART_SUFFIX = ".part"
    val MAX_AGE_MILLIS: Long = TimeUnit.HOURS.toMillis(24)
    const val MAX_TOTAL_BYTES: Long = 1L shl 30

    /** A `.part` touched this recently may belong to a download running on another screen. */
    val ACTIVE_PART_MILLIS: Long = TimeUnit.MINUTES.toMillis(10)

    /** Where a share's file [path] on [machine] ends up: its own folder, so same-named files never collide. */
    fun target(
        root: File,
        shareId: String,
        machine: String?,
        path: String,
    ): File = File(File(File(root, safeSegment(shareId)), pathKey(machine, path)), safeSegment(baseName(path)))

    /** First 12 hex digits of SHA-256 over machine and path (`t7` and `rohan/t7` stay distinct). */
    private fun pathKey(
        machine: String?,
        path: String,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("${machine ?: ""}\u0000$path".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)

    fun partOf(target: File): File = File(target.parentFile, target.name + PART_SUFFIX)

    /**
     * Deletes files (also `.part`) older than [MAX_AGE_MILLIS], then the oldest first until the total is at most
     * [maxTotalBytes], never a file in [keep] nor a `.part` modified in the last [ACTIVE_PART_MILLIS]; then empty
     * folders. Blocking.
     */
    fun prune(
        root: File,
        nowMillis: Long,
        keep: Set<File> = emptySet(),
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
    ) {
        val kept = keep.map { it.absoluteFile }.toSet()
        val all = root.walkBottomUp().filter { it != root }.toList()
        val dirs = all.filter { it.isDirectory }
        val files = all.filter { it.isFile }

        fun File.inUse() =
            absoluteFile in kept || (name.endsWith(PART_SUFFIX) && nowMillis - lastModified() < ACTIVE_PART_MILLIS)
        val (old, fresh) = files.partition { nowMillis - it.lastModified() > MAX_AGE_MILLIS && !it.inUse() }
        old.forEach { it.delete() }
        var total = fresh.sumOf { it.length() }
        for (f in fresh.sortedBy { it.lastModified() }) {
            if (total <= maxTotalBytes) break
            if (f.inUse()) continue
            val size = f.length()
            if (f.delete()) total -= size
        }
        dirs.forEach { if (it.listFiles().isNullOrEmpty()) it.delete() } // children first
    }

    /** A single safe path segment: no separators, never `.` or `..`, never empty. */
    internal fun safeSegment(value: String): String {
        val cleaned = value.map { if (it.isLetterOrDigit() || it in "._- ") it else '_' }.joinToString("").trim()
        return if (cleaned.isEmpty() || cleaned.all { it == '.' }) "file" else cleaned
    }
}
