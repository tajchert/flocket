package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.logging.Logger
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.common.time.Clock
import com.mtajchert.flocket.core.model.MAX_DOWNLOAD_BYTES
import com.mtajchert.flocket.core.protocol.CallTimeouts
import com.mtajchert.flocket.core.protocol.dto.ExecResult
import com.mtajchert.flocket.core.protocol.toAppError
import com.mtajchert.flocket.core.protocol.transport.DirectFileException
import com.mtajchert.flocket.core.protocol.transport.DirectFileReader
import com.mtajchert.flocket.core.protocol.transport.DirectFileSource
import com.mtajchert.flocket.core.protocol.transport.RemoteFileStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Base64
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/** What [Downloader] needs from its session. */
internal interface DownloadHost {
    /** Why a download cannot start now (not Online, or stale), or null. */
    fun unavailable(): AppError?

    /** The link's SFTP, unless it is not an SSH link or this link already refused the subsystem. */
    fun directFileSource(): DirectFileSource?

    /** [source] refused SFTP: use chunks until the session reconnects. */
    fun sftpRefused(source: DirectFileSource)

    /** `machine.exec` on the transfer connection, retried once on a fresh one after a drop. */
    suspend fun exec(
        argv: List<String>,
        on: String?,
        timeout: Duration,
    ): AppResult<ExecResult>
}

/**
 * [CoreSession.download] (design 2026-11-05 §4.4, ADR-0012): stats the remote file, streams it into the
 * target over SFTP on the host's own machine when the link offers it, else with chunked `machine.exec`
 * reads (`dd`), and checks with a second stat that it did not change.
 */
internal class Downloader(
    private val host: DownloadHost,
    private val clock: Clock,
    private val logger: Logger,
    private val statTimeout: Duration,
    private val io: CoroutineContext,
    private val usableSpace: (File) -> Long,
) {
    fun download(
        machine: String?,
        path: String,
        target: File,
        resume: DownloadResume?,
    ): Flow<DownloadEvent> =
        channelFlow {
            var started: DownloadResume? = null
            val end =
                try {
                    run(machine, path, target, resume) { started = it }
                } catch (e: DownloadFailure) {
                    DownloadEvent.Failed(e.error, started.takeIf { e.error.resumable() })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Never throws to the collector (e.g. a local file the app may not read).
                    DownloadEvent.Failed(AppError.Unexpected(e), started)
                }
            send(end)
        }.flowOn(io) // the whole download (base64 decode, file writes) runs off the collector's thread

    private suspend fun ProducerScope<DownloadEvent>.run(
        machine: String?,
        path: String,
        target: File,
        resume: DownloadResume?,
        onStarted: (DownloadResume) -> Unit,
    ): DownloadEvent.Done {
        host.unavailable()?.let { fail(it) }
        val (chosen, found) = choose(machine, path)
        var remote = chosen
        try {
            val first = found ?: fail(AppError.FileMissing(path))
            if (!first.isRegularFile) fail(AppError.InvalidInput("file", "not a regular file"))
            if (first.size > MAX_DOWNLOAD_BYTES) fail(AppError.FileTooLarge(MAX_DOWNLOAD_BYTES))
            val size = first.size
            val method = remote.method
            val token = DownloadResume(machine, path, size, first.mtimeSeconds)
            val existing = if (resume == token && target.isFile) target.length() else 0L
            if (resume == token && existing == size) {
                onStarted(token)
                send(DownloadEvent.Started(size, method, token, size))
                send(DownloadEvent.Progress(size, size))
                return DownloadEvent.Done(size, method)
            }
            var offset = remote.alignOffset(if (existing > size) 0 else existing)
            var out = open(target, offset, size)
            val begin = clock.nowMillis()
            try {
                onStarted(token)
                send(DownloadEvent.Started(size, method, token, offset))
                var progress = ProgressThrottle(this@run, offset, size)
                try {
                    try {
                        remote.copy(offset, size, out, progress::advance)
                    } catch (e: DirectFileException) {
                        // SFTP stat can work where open is denied (e.g. macOS privacy folders): `hc` may still
                        // read the file, so continue with chunks, if nothing was written yet.
                        if (remote !is Sftp || out.count != 0L || !e.isNotReadable()) throw e
                        logger.debug(TAG) { "sftp cannot open the file (${e.reason}), using chunks" }
                        val chunked = Chunked(machine, path)
                        val same = chunked.stat()
                        if (same == null || !same.isRegularFile || same.size != size ||
                            same.mtimeSeconds != first.mtimeSeconds
                        ) {
                            fail(AppError.FileMissing(path))
                        }
                        withContext(NonCancellable) { remote.close() }
                        remote = chunked
                        out.closeQuietly()
                        offset = chunked.alignOffset(offset)
                        out = open(target, offset, size)
                        progress = ProgressThrottle(this@run, offset, size)
                        chunked.copy(offset, size, out, progress::advance)
                    }
                    out.close()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The file grew past its size (SFTP copies to EOF): the final check would fail anyway.
                    if (out.overflowed) fail(AppError.FileChanged(path))
                    if (out.error != null) fail(AppError.Storage(WRITE_FAILED))
                    if (e is DownloadFailure) throw e
                    fail(remote.errorOf(e))
                }
                val last = remote.stat()
                if (last == null || last.size != size || last.mtimeSeconds != first.mtimeSeconds ||
                    offset + out.count != size
                ) {
                    fail(AppError.FileChanged(path))
                }
                logger.debug(
                    TAG,
                ) { "download ${remote.method}: $size bytes from $offset in ${clock.nowMillis() - begin} ms" }
                send(DownloadEvent.Progress(size, size))
                return DownloadEvent.Done(size, remote.method)
            } finally {
                out.closeQuietly()
            }
        } finally {
            withContext(NonCancellable) { remote.close() }
        }
    }

    /** Method choice (design §4.4): SFTP when it can see [path], else chunks; the stat is null when missing. */
    private suspend fun choose(
        machine: String?,
        path: String,
    ): Pair<Remote, RemoteFileStat?> {
        val source = if (machine == null) host.directFileSource() else null
        source?.let { sftp(it, path) }?.let { return it }
        val chunked = Chunked(machine, path)
        return chunked to chunked.stat()
    }

    /** An SFTP reader that sees [path] and its stat, or null to fall back to chunks (refused, denied, not seen). */
    private suspend fun sftp(
        source: DirectFileSource,
        path: String,
    ): Pair<Remote, RemoteFileStat>? {
        val reader =
            try {
                source.openFileReader()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is DirectFileException && e.reason == DirectFileException.Reason.UNAVAILABLE) {
                    host.sftpRefused(source)
                }
                logger.debug(TAG) { "sftp not available (${e.javaClass.simpleName}), using chunks" }
                return null
            }
        val remote = Sftp(reader, path)
        var keep = false
        try {
            val stat = reader.stat(path)
            if (stat == null) logger.debug(TAG) { "sftp does not see the file, using chunks" }
            return stat?.let { remote to it }.also { keep = it != null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug(TAG) { "sftp stat failed (${e.javaClass.simpleName}), using chunks" }
            return null
        } finally {
            if (!keep) withContext(NonCancellable) { remote.close() }
        }
    }

    /**
     * Checks free space, truncates [target] to [offset] and opens it for append, taking at most `size - offset`
     * bytes. Blocking on purpose (the flow runs on `io`): no suspension point between opening the stream and
     * the caller's `finally`, so a cancellation can never leak it.
     */
    private fun open(
        target: File,
        offset: Long,
        size: Long,
    ): LocalOutput {
        val dir = target.absoluteFile.parentFile
        dir.mkdirs()
        if (usableSpace(dir) < size - offset + FREE_SPACE_MARGIN) fail(AppError.Storage("not enough space"))
        return try {
            RandomAccessFile(target, "rw").use { it.setLength(offset) }
            LocalOutput(FileOutputStream(target, true), limit = size - offset)
        } catch (e: IOException) {
            fail(AppError.Storage(WRITE_FAILED))
        }
    }

    /** One way of reading the remote file. */
    private interface Remote {
        val method: DownloadMethod

        /** null when the file does not exist. Throws [DownloadFailure]. */
        suspend fun stat(): RemoteFileStat?

        /** Where a resume at [existing] bytes really continues. */
        fun alignOffset(existing: Long): Long = existing

        /** Copies bytes [offset] until [size] into [out], reporting each block's size to [onBytes]. */
        suspend fun copy(
            offset: Long,
            size: Long,
            out: OutputStream,
            onBytes: (Int) -> Unit,
        )

        /** The failure for an exception [copy] threw. */
        fun errorOf(e: Exception): AppError = e.toAppError("download")

        suspend fun close() {}
    }

    /** One SFTP channel on the host link, for this download only. */
    private class Sftp(
        private val reader: DirectFileReader,
        private val path: String,
    ) : Remote {
        override val method = DownloadMethod.SFTP

        override suspend fun stat(): RemoteFileStat? =
            try {
                reader.stat(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw DownloadFailure(errorOf(e))
            }

        override suspend fun copy(
            offset: Long,
            size: Long,
            out: OutputStream,
            onBytes: (Int) -> Unit,
        ) {
            reader.copyTo(path, offset, out, onBytes)
        }

        override fun errorOf(e: Exception): AppError =
            when {
                e !is DirectFileException -> {
                    e.toAppError("sftp")
                }

                e.reason == DirectFileException.Reason.DENIED || e.reason == DirectFileException.Reason.MISSING -> {
                    AppError.FileMissing(path)
                }

                // A server-side read error while the link is up: not a lost connection (still resumable).
                e.reason == DirectFileException.Reason.IO -> {
                    AppError.Unexpected(e)
                }

                else -> {
                    AppError.ConnectionLost
                }
            }

        override suspend fun close() {
            try {
                reader.close()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** `stat` and `dd` slices over `machine.exec` (SPEC §4.20), one call at a time. */
    private inner class Chunked(
        private val machine: String?,
        private val path: String,
    ) : Remote {
        override val method = DownloadMethod.CHUNKED

        private val chunk = if (machine == null) LOCAL_CHUNK else FEDERATED_CHUNK

        override suspend fun stat(): RemoteFileStat? {
            val r = exec(listOf("sh", "-c", STAT, "sh", path), statTimeout)
            if (r.timedOut) fail(AppError.Timeout("machine.exec"))
            if (r.code != 0) return null
            return decode(r.stdout)?.let { parseStat(it.toString(Charsets.UTF_8)) }
                ?: fail(AppError.Protocol("unexpected stat reply"))
        }

        override fun alignOffset(existing: Long) = existing - existing % BLOCK

        override suspend fun copy(
            offset: Long,
            size: Long,
            out: OutputStream,
            onBytes: (Int) -> Unit,
        ) {
            var at = offset
            while (at < size) {
                val argv = listOf("dd", "if=$path", "bs=$BLOCK", "skip=${at / BLOCK}", "count=${chunk / BLOCK}")
                val r = exec(argv, CallTimeouts.transfer(chunk))
                if (r.timedOut) fail(AppError.Timeout("machine.exec"))
                if (r.truncated) fail(AppError.Protocol("chunk truncated"))
                if (r.code != 0) fail(AppError.FileMissing(path))
                val bytes = decode(r.stdout) ?: fail(AppError.Protocol("bad file data"))
                if (bytes.size.toLong() != minOf(chunk, size - at)) fail(AppError.FileChanged(path))
                out.write(bytes)
                at += bytes.size
                onBytes(bytes.size)
            }
        }

        private suspend fun exec(
            argv: List<String>,
            timeout: Duration,
        ): ExecResult =
            when (val r = host.exec(argv, machine, timeout)) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> fail(r.error)
            }
    }

    /** Emits [DownloadEvent.Progress] at most every [PROGRESS_INTERVAL_MS] or [PROGRESS_STEP] bytes. */
    private inner class ProgressThrottle(
        private val events: ProducerScope<DownloadEvent>,
        private var bytes: Long,
        private val size: Long,
    ) {
        private var sentBytes = bytes
        private var sentAt = clock.nowMillis()

        fun advance(n: Int) {
            bytes += n
            if (bytes >= size) return // the caller always sends the final Progress(size, size) itself
            val now = clock.nowMillis()
            if (bytes - sentBytes < PROGRESS_STEP && now - sentAt < PROGRESS_INTERVAL_MS) return
            sentBytes = bytes
            sentAt = now
            // Lossy on purpose: a slow collector misses intermediate steps, never the final one (sent by the caller).
            events.trySend(DownloadEvent.Progress(bytes, size))
        }
    }

    /**
     * The target file: counts what was written, remembers a local write failure, and refuses bytes past [limit]
     * (a file that grows during the download), setting [overflowed].
     */
    private class LocalOutput(
        private val out: OutputStream,
        private val limit: Long,
    ) : OutputStream() {
        var count = 0L
            private set
        var error: IOException? = null
            private set
        var overflowed = false
            private set

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            if (count + len > limit) {
                overflowed = true
                throw IOException("more bytes than the file had")
            }
            try {
                out.write(b, off, len)
            } catch (e: IOException) {
                error = e
                throw e
            }
            count += len
        }

        override fun close() {
            try {
                out.close()
            } catch (e: IOException) {
                error = e
                throw e
            }
        }

        fun closeQuietly() {
            try {
                out.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun DirectFileException.isNotReadable() =
        reason == DirectFileException.Reason.DENIED || reason == DirectFileException.Reason.MISSING

    /** A known [AppError] that ends the download. */
    private class DownloadFailure(
        val error: AppError,
    ) : Exception(null, null, false, false)

    private fun fail(error: AppError): Nothing = throw DownloadFailure(error)

    private fun decode(s: String): ByteArray? =
        try {
            Base64.getDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            null
        }

    /** Failures that mean "start over" (or give up) carry no resume token. */
    private fun AppError.resumable() =
        this !is AppError.FileChanged &&
            this !is AppError.FileTooLarge &&
            this !is AppError.InvalidInput &&
            this !is AppError.Storage

    companion object {
        private const val TAG = "Downloader"

        /** GNU/busybox `stat`, then BSD/macOS: prints `<size> <mtime> <type words>` for `$1`. */
        const val STAT = "stat -L -c \"%s %Y %F\" -- \"\$1\" 2>/dev/null || stat -L -f \"%z %m %HT\" -- \"\$1\""

        /** `dd` block size; chunked offsets are multiples of it. */
        const val BLOCK = 65_536L
        const val LOCAL_CHUNK = 4L * 1024 * 1024
        const val FEDERATED_CHUNK = 1L * 1024 * 1024
        const val FREE_SPACE_MARGIN = 32L * 1024 * 1024
        const val PROGRESS_STEP = 1L * 1024 * 1024
        const val PROGRESS_INTERVAL_MS = 100L
        private const val WRITE_FAILED = "could not write the file"

        /** `"<size> <mtime> <type words>"`; null when it does not parse. */
        fun parseStat(reply: String): RemoteFileStat? {
            val parts = reply.trim().split(Regex("\\s+"), limit = 3)
            if (parts.size < 3) return null
            val size = parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val mtime = parts[1].toLongOrNull() ?: return null
            return RemoteFileStat(size, mtime, parts[2].lowercase().startsWith("regular"))
        }
    }
}
