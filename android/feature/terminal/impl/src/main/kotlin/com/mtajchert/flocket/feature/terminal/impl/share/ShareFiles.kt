package com.mtajchert.flocket.feature.terminal.impl.share

import java.io.File
import java.util.concurrent.TimeUnit

/** Files handed to other apps live in `cacheDir/shares/<share id>/<name>` (the FileProvider's `shares` path). */
object ShareFiles {
    const val DIRECTORY = "shares"
    val MAX_AGE_MILLIS: Long = TimeUnit.HOURS.toMillis(24)

    /** Writes [bytes] for sharing out and returns the file. Blocking: call on an IO dispatcher. */
    fun write(
        cacheDir: File,
        shareId: String,
        name: String,
        bytes: ByteArray,
    ): File {
        val dir = File(File(cacheDir, DIRECTORY), safeSegment(shareId)).apply { mkdirs() }
        return File(dir, safeSegment(name)).apply { writeBytes(bytes) }
    }

    /** Deletes cached files older than [MAX_AGE_MILLIS], then empty folders. Blocking. */
    fun prune(
        cacheDir: File,
        nowMillis: Long,
    ) {
        val root = File(cacheDir, DIRECTORY)
        root.listFiles()?.forEach { dir ->
            dir.listFiles()?.forEach { f -> if (nowMillis - f.lastModified() > MAX_AGE_MILLIS) f.delete() }
            if (dir.listFiles().isNullOrEmpty()) dir.delete()
        }
    }

    /** A single safe path segment: no separators, never `.` or `..`, never empty. */
    internal fun safeSegment(value: String): String {
        val cleaned = value.map { if (it.isLetterOrDigit() || it in "._- ") it else '_' }.joinToString("").trim()
        return if (cleaned.isEmpty() || cleaned.all { it == '.' }) "file" else cleaned
    }
}
