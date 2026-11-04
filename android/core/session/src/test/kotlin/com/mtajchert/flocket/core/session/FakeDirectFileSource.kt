package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.protocol.transport.ByteTransport
import com.mtajchert.flocket.core.protocol.transport.DirectFileException
import com.mtajchert.flocket.core.protocol.transport.DirectFileReader
import com.mtajchert.flocket.core.protocol.transport.DirectFileSource
import com.mtajchert.flocket.core.protocol.transport.RemoteFileStat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A file on the fake host's disk, as both the fake core (`stat`/`dd`) and [FakeDirectFileSource] see it. */
class FakeRemoteFile(
    @Volatile var bytes: ByteArray,
    @Volatile var mtimeSeconds: Long = 1_700_000_000,
    val isRegularFile: Boolean = true,
    /** Size `stat` reports instead of the real one (a 200 MiB+ file without allocating it). */
    val statSize: Long? = null,
)

/** The fake host's files by path; shared by the fake core and the fake SFTP reader. */
class FakeRemoteFiles {
    val files = ConcurrentHashMap<String, FakeRemoteFile>()

    operator fun get(path: String): FakeRemoteFile? = files[path]

    operator fun set(
        path: String,
        file: FakeRemoteFile,
    ) {
        files[path] = file
    }
}

/**
 * Test-only SFTP: serves [files] through [DirectFileSource], with scripted failures. [over] wraps the main
 * transport so the session sees it as a [DirectFileSource], like the SSH transport in production.
 */
class FakeDirectFileSource(
    private val files: FakeRemoteFiles,
) : DirectFileSource {
    /** Thrown by every [openFileReader] when set (e.g. `UNAVAILABLE`: no SFTP subsystem). */
    @Volatile var openFailure: DirectFileException? = null

    /** Paths whose `stat` and `copyTo` are `DENIED` (macOS privacy rules for `sftp-server`). */
    val denied: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Paths SFTP cannot see (its view differs from the core's). */
    val hidden: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** `copyTo` writes blocks of this size. */
    @Volatile var blockSize = 64 * 1024

    /** Called after each block with the bytes copied so far in this `copyTo` (to change the file or cancel). */
    @Volatile var afterBlock: suspend (Long) -> Unit = {}

    val opens = AtomicInteger()
    val closes = AtomicInteger()
    val stats = CopyOnWriteArrayList<String>()

    /** Every `copyTo` as (path, offset). */
    val copies = CopyOnWriteArrayList<Pair<String, Long>>()

    fun over(transport: ByteTransport): ByteTransport = Wrapped(transport, this)

    override suspend fun openFileReader(): DirectFileReader {
        opens.incrementAndGet()
        openFailure?.let { throw it }
        return Reader()
    }

    private inner class Reader : DirectFileReader {
        private var closed = false

        override suspend fun stat(path: String): RemoteFileStat? {
            check(!closed) { "reader closed" }
            stats += path
            if (path in denied) throw DirectFileException(DirectFileException.Reason.DENIED, "denied")
            if (path in hidden) return null
            val f = files[path] ?: return null
            return RemoteFileStat(f.statSize ?: f.bytes.size.toLong(), f.mtimeSeconds, f.isRegularFile)
        }

        override suspend fun copyTo(
            path: String,
            offset: Long,
            out: OutputStream,
            onBytes: (Int) -> Unit,
        ): Long {
            check(!closed) { "reader closed" }
            copies += path to offset
            if (path in denied) throw DirectFileException(DirectFileException.Reason.DENIED, "denied")
            val f = files[path]
            if (f == null || path in hidden) throw DirectFileException(DirectFileException.Reason.MISSING, "missing")
            val bytes = f.bytes
            var at = offset.toInt()
            while (at < bytes.size) {
                currentCoroutineContext().ensureActive()
                val n = minOf(blockSize, bytes.size - at)
                out.write(bytes, at, n)
                at += n
                onBytes(n)
                afterBlock(at - offset)
            }
            return at - offset
        }

        override suspend fun close() {
            if (closed) return
            closed = true
            closes.incrementAndGet()
        }
    }

    private class Wrapped(
        transport: ByteTransport,
        source: DirectFileSource,
    ) : ByteTransport by transport,
        DirectFileSource by source
}
