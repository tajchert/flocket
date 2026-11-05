package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.logging.Logger
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.common.time.Clock
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.MAX_DOWNLOAD_BYTES
import com.mtajchert.flocket.core.protocol.dto.ExecResult
import com.mtajchert.flocket.core.protocol.testing.FakeCore
import com.mtajchert.flocket.core.protocol.testing.FakeCoreConfig
import com.mtajchert.flocket.core.protocol.transport.DirectFileException
import com.mtajchert.flocket.core.protocol.transport.DirectFileSource
import com.mtajchert.flocket.core.protocol.transport.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CoreSessionDownloadTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val files = FakeRemoteFiles()
    private val path = "/srv/app-debug.apk"

    /** Replaces the fake core's reply to one `dd` (argv) when it returns non-null. */
    @Volatile
    private var ddHook: suspend (FakeCore, List<String>) -> JsonObject? = { _, _ -> null }

    private suspend fun TestScope.eventually(
        what: String = "condition",
        cond: () -> Boolean,
    ) {
        repeat(2_000) {
            if (cond()) return
            delay(1)
        }
        fail("timed out waiting for $what")
    }

    private fun TestScope.host() = FakeHost(backgroundScope).apply { configure = { it.serveFiles() } }

    private val sftp = FakeDirectFileSource(files)

    /** A host whose main transport also reads files over (fake) SFTP. */
    private fun TestScope.sftpHost() = host().apply { wrapTransport = sftp::over }

    private suspend fun TestScope.online(
        host: FakeHost,
        usableSpace: (File) -> Long = File::getUsableSpace,
    ): CoreSession =
        host.session(this, usableSpace = usableSpace).also {
            it.start()
            it.awaitOnline()
        }

    private fun put(
        size: Int,
        at: String = path,
    ): ByteArray = Random(size).nextBytes(size).also { files[at] = FakeRemoteFile(it) }

    private fun target() = File(tmp.root, "shares/s1/app-debug.apk.part")

    private fun exec(
        stdout: ByteArray,
        code: Int? = 0,
        truncated: Boolean = false,
        timedOut: Boolean = false,
    ) = buildJsonObject {
        put("stdout", Base64.getEncoder().encodeToString(stdout))
        put("stderr", "")
        put("truncated", truncated)
        put("timed_out", timedOut)
        put("ms", 1)
        if (code != null) put("code", code)
    }

    private fun JsonObject.argv() = this["argv"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun List<String>.arg(name: String) = single { it.startsWith("$name=") }.substringAfter('=')

    /** Answers the download `stat` script (GNU output) and `dd` slices over [files]. */
    private fun FakeCore.serveFiles() {
        handlers["machine.exec"] = { p ->
            val argv = p.argv()
            when (argv.first()) {
                "sh" -> {
                    assertEquals(STAT_SCRIPT, argv[2])
                    val f = files[argv[4]]
                    when {
                        f == null -> {
                            exec(ByteArray(0), code = 1)
                        }

                        f.isRegularFile -> {
                            exec(
                                "${f.statSize ?: f.bytes.size} ${f.mtimeSeconds} regular file\n".toByteArray(),
                            )
                        }

                        else -> {
                            exec("4096 ${f.mtimeSeconds} directory\n".toByteArray())
                        }
                    }
                }

                "dd" -> {
                    ddHook(this, argv) ?: run {
                        val f = files[argv.arg("if")] ?: return@run exec(ByteArray(0), code = 1)
                        val bs = argv.arg("bs").toLong()
                        val from = (argv.arg("skip").toLong() * bs).coerceAtMost(f.bytes.size.toLong()).toInt()
                        val to =
                            ((argv.arg("skip").toLong() + argv.arg("count").toLong()) * bs)
                                .coerceAtMost(f.bytes.size.toLong())
                                .toInt()
                        exec(f.bytes.copyOfRange(from, to))
                    }
                }

                else -> {
                    error("unexpected exec ${argv.first()}")
                }
            }
        }
    }

    private fun FakeHost.execs() = cores.flatMap { c -> c.callsOf("machine.exec").map { it.params } }

    private fun FakeHost.dds() = execs().filter { it.argv().first() == "dd" }

    private fun FakeHost.stats() = execs().filter { it.argv().first() == "sh" }

    private fun List<DownloadEvent>.started() = filterIsInstance<DownloadEvent.Started>().single()

    // ---- chunked ---------------------------------------------------------------------------------

    @Test
    fun `local chunked uses 4 MiB chunks`() =
        runTest {
            val bytes = put(10 * MIB)
            val host = host()
            val s = online(host)
            val events = s.download(null, path, target()).toList()
            val started = events.started()
            assertEquals(DownloadMethod.CHUNKED, started.method)
            assertEquals(DownloadResume(null, path, bytes.size.toLong(), 1_700_000_000), started.resume)
            assertEquals(0L, started.resumedFrom)
            assertEquals(DownloadEvent.Done(bytes.size.toLong(), DownloadMethod.CHUNKED), events.last())
            assertEquals(DownloadEvent.Progress(bytes.size.toLong(), bytes.size.toLong()), events[events.size - 2])
            // Exactly one final Progress, even though the last chunk crosses the 1 MiB step.
            assertEquals(1, events.count { it == DownloadEvent.Progress(bytes.size.toLong(), bytes.size.toLong()) })
            assertArrayEquals(bytes, target().readBytes())
            val dds = host.dds()
            assertEquals(listOf("0", "64", "128"), dds.map { it.argv().arg("skip") })
            assertTrue(dds.all { it.argv().arg("count") == "64" && it.argv().arg("bs") == "65536" })
            assertTrue(host.execs().all { it["on"] == null })
            // Stat at the start and at the end, all on the transfer connection.
            assertEquals(2, host.stats().size)
            assertTrue(host.cores[0].callsOf("machine.exec").isEmpty())
            s.stop()
        }

    @Test
    fun `federated machine uses chunked with 1 MiB chunks and on`() =
        runTest {
            val bytes = put(3 * MIB + 100)
            val host = host()
            val s = online(host)
            val events = s.download("rohan", path, target()).toList()
            assertEquals(DownloadEvent.Done(bytes.size.toLong(), DownloadMethod.CHUNKED), events.last())
            assertEquals("rohan", events.started().resume.machine)
            assertArrayEquals(bytes, target().readBytes())
            val dds = host.dds()
            assertEquals(listOf("0", "16", "32", "48"), dds.map { it.argv().arg("skip") })
            assertTrue(dds.all { it.argv().arg("count") == "16" })
            assertTrue(host.execs().all { it["on"]!!.jsonPrimitive.content == "rohan" })
            s.stop()
        }

    @Test
    fun `odd path is passed verbatim to stat and dd`() =
        runTest {
            val odd = "/tmp/my file 'q\" -x.apk"
            val bytes = put(1000, at = odd)
            val host = host()
            val s = online(host)
            val events = s.download(null, odd, target()).toList()
            assertEquals(DownloadEvent.Done(1000, DownloadMethod.CHUNKED), events.last())
            assertArrayEquals(bytes, target().readBytes())
            val stat = host.stats().first().argv()
            assertEquals(listOf("sh", "-c", STAT_SCRIPT, "sh", odd), stat)
            assertEquals(odd, stat[4])
            assertEquals("if=$odd", host.dds().single().argv()[1])
            s.stop()
        }

    @Test
    fun `too large fails before any data call`() =
        runTest {
            files[path] = FakeRemoteFile(ByteArray(10), statSize = MAX_DOWNLOAD_BYTES + 1)
            val host = host()
            val s = online(host)
            val events = s.download(null, path, target()).toList()
            assertEquals(listOf(DownloadEvent.Failed(AppError.FileTooLarge(MAX_DOWNLOAD_BYTES), null)), events)
            assertTrue(host.dds().isEmpty())
            s.stop()
        }

    @Test
    fun `not a regular file fails with InvalidInput`() =
        runTest {
            files[path] = FakeRemoteFile(ByteArray(0), isRegularFile = false)
            val host = host()
            val s = online(host)
            val events = s.download(null, path, target()).toList()
            assertEquals(
                listOf(DownloadEvent.Failed(AppError.InvalidInput("file", "not a regular file"), null)),
                events,
            )
            assertTrue(host.dds().isEmpty())
            s.stop()
        }

    @Test
    fun `not enough free space fails before any chunk`() =
        runTest {
            put(MIB)
            val host = host()
            val asked = CopyOnWriteArrayList<File>()
            // 1 MiB file + 32 MiB margin needs 33 MiB.
            val s = online(host) { dir -> (33L * MIB - 1).also { asked += dir } }
            val events = s.download(null, path, target()).toList()
            assertEquals(listOf(DownloadEvent.Failed(AppError.Storage("not enough space"), null)), events)
            assertEquals(listOf(target().absoluteFile.parentFile), asked)
            assertTrue(host.dds().isEmpty())
            s.stop()
        }

    @Test
    fun `resume with a matching token continues from the partial file`() =
        runTest {
            val bytes = put(10 * MIB)
            target().parentFile.mkdirs()
            target().writeBytes(bytes.copyOf(5 * MIB + 1000))
            val host = host()
            val s = online(host)
            val token = DownloadResume(null, path, bytes.size.toLong(), 1_700_000_000)
            val events = s.download(null, path, target(), token).toList()
            // Chunked continues at a 64 KiB boundary.
            assertEquals(5L * MIB, events.started().resumedFrom)
            assertEquals(DownloadEvent.Done(bytes.size.toLong(), DownloadMethod.CHUNKED), events.last())
            assertEquals(listOf("80", "144"), host.dds().map { it.argv().arg("skip") })
            assertArrayEquals(bytes, target().readBytes())
            s.stop()
        }

    @Test
    fun `resume of a complete file is Done without data calls`() =
        runTest {
            val bytes = put(2 * MIB)
            target().parentFile.mkdirs()
            target().writeBytes(bytes)
            val host = host()
            val s = online(host)
            val size = bytes.size.toLong()
            val token = DownloadResume(null, path, size, 1_700_000_000)
            val events = s.download(null, path, target(), token).toList()
            assertEquals(
                listOf(
                    DownloadEvent.Started(size, DownloadMethod.CHUNKED, token, size),
                    DownloadEvent.Progress(size, size),
                    DownloadEvent.Done(size, DownloadMethod.CHUNKED),
                ),
                events,
            )
            assertTrue(host.dds().isEmpty())
            assertEquals(1, host.stats().size)
            assertArrayEquals(bytes, target().readBytes())
            s.stop()
        }

    @Test
    fun `resume with a stale token starts over`() =
        runTest {
            val bytes = put(5 * MIB)
            target().parentFile.mkdirs()
            target().writeBytes(ByteArray(3 * MIB) { 7 })
            val host = host()
            val s = online(host)
            val stale = DownloadResume(null, path, bytes.size.toLong(), 1_600_000_000)
            val events = s.download(null, path, target(), stale).toList()
            assertEquals(0L, events.started().resumedFrom)
            assertEquals(1_700_000_000L, events.started().resume.mtimeSeconds)
            assertEquals(
                "0",
                host
                    .dds()
                    .first()
                    .argv()
                    .arg("skip"),
            )
            assertEquals(DownloadEvent.Done(bytes.size.toLong(), DownloadMethod.CHUNKED), events.last())
            assertArrayEquals(bytes, target().readBytes())
            s.stop()
        }

    @Test
    fun `file changed (mtime) between start and end fails with FileChanged`() =
        runTest {
            put(5 * MIB)
            ddHook = { _, argv ->
                if (argv.arg("skip") == "64") files[path]!!.mtimeSeconds += 1
                null
            }
            val host = host()
            val s = online(host)
            val events = s.download(null, path, target()).toList()
            assertEquals(DownloadEvent.Failed(AppError.FileChanged(path), null), events.last())
            assertTrue(events.none { it is DownloadEvent.Done })
            s.stop()
        }

    @Test
    fun `file changed (size) between start and end fails with FileChanged`() =
        runTest {
            val bytes = put(2 * MIB)
            ddHook = { _, _ ->
                val f = files[path]!!
                val reply = exec(f.bytes)
                f.bytes = bytes + ByteArray(10)
                reply
            }
            val host = host()
            val s = online(host)
            val events = s.download(null, path, target()).toList()
            assertEquals(DownloadEvent.Failed(AppError.FileChanged(path), null), events.last())
            s.stop()
        }

    @Test
    fun `short non-final chunk fails with FileChanged`() =
        runTest {
            val bytes = put(10 * MIB)
            ddHook = { _, argv -> if (argv.arg("skip") == "0") exec(bytes.copyOf(MIB)) else null }
            val host = host()
            val s = online(host)
            val events = s.download(null, path, target()).toList()
            assertEquals(DownloadEvent.Failed(AppError.FileChanged(path), null), events.last())
            assertEquals(1, host.dds().size)
            s.stop()
        }

    @Test
    fun `truncated chunk fails with Protocol`() =
        runTest {
            val bytes = put(10 * MIB)
            ddHook =
                { _, argv -> if (argv.arg("skip") == "64") exec(bytes.copyOf(4 * MIB), truncated = true) else null }
            val host = host()
            val s = online(host)
            val events = s.download(null, path, target()).toList()
            val token = events.started().resume
            assertEquals(DownloadEvent.Failed(AppError.Protocol("chunk truncated"), token), events.last())
            assertEquals(2, host.dds().size)
            s.stop()
        }

    @Test
    fun `timed out chunk fails with Timeout and a resume token`() =
        runTest {
            put(10 * MIB)
            ddHook =
                { _, argv -> if (argv.arg("skip") == "64") exec(ByteArray(0), code = null, timedOut = true) else null }
            val host = host()
            val s = online(host)
            val events = s.download(null, path, target()).toList()
            val token = events.started().resume
            assertEquals(DownloadEvent.Failed(AppError.Timeout("machine.exec"), token), events.last())
            assertEquals(4L * MIB, target().length())
            s.stop()
        }

    @Test
    fun `link drop mid-file fails with a resume token and resume completes the file`() =
        runTest {
            val bytes = put(10 * MIB)
            val drops = AtomicInteger()
            ddHook = { core, argv ->
                if (argv.arg("skip") == "64" && drops.getAndIncrement() < 2) {
                    core.dropConnection()
                    awaitCancellation()
                }
                null
            }
            val host = host()
            val s = online(host)
            val first = s.download(null, path, target()).toList()
            val token = first.started().resume
            assertEquals(DownloadEvent.Failed(AppError.ConnectionLost, token), first.last())
            assertEquals(2, drops.get()) // the chunk was retried once
            assertEquals(4L * MIB, target().length())

            val second = s.download(null, path, target(), token).toList()
            assertEquals(4L * MIB, second.started().resumedFrom)
            assertEquals(DownloadEvent.Done(bytes.size.toLong(), DownloadMethod.CHUNKED), second.last())
            assertArrayEquals(bytes, target().readBytes())
            s.stop()
        }

    @Test
    fun `cancel stops further calls and emits no Failed`() =
        runTest {
            put(10 * MIB)
            val held = CompletableDeferred<Unit>()
            ddHook = { _, argv ->
                if (argv.arg("skip") == "64") held.await()
                null
            }
            val host = host()
            val s = online(host)
            val events = CopyOnWriteArrayList<DownloadEvent>()
            val job = launch { s.download(null, path, target()).collect { events += it } }
            eventually("second chunk asked") { host.dds().size == 2 }
            job.cancelAndJoin()
            held.complete(Unit)
            advanceTimeBy(5.seconds)
            runCurrent()
            assertEquals(2, host.dds().size)
            assertTrue(events.none { it is DownloadEvent.Failed || it is DownloadEvent.Done })
            assertEquals(4L * MIB, target().length()) // left as it is
            s.stop()
        }

    @Test
    fun `missing on the core fails with FileMissing`() =
        runTest {
            val host = host()
            val s = online(host)
            val events = s.download(null, "/srv/gone.apk", target()).toList()
            assertEquals(listOf(DownloadEvent.Failed(AppError.FileMissing("/srv/gone.apk"), null)), events)
            s.stop()
        }

    @Test
    fun `not online fails with ConnectionLost`() =
        runTest {
            put(10)
            val s = host().session(this)
            val events = s.download(null, path, target()).toList()
            assertEquals(listOf(DownloadEvent.Failed(AppError.ConnectionLost, null)), events)
        }

    @Test
    fun `core without machine exec and no SFTP is Unsupported`() =
        runTest {
            put(10)
            val host = host()
            host.coreConfig = FakeCoreConfig(methods = FakeCoreConfig.DEFAULT_METHODS - "machine.exec")
            val s = online(host)
            val events = s.download(null, path, target()).toList()
            assertEquals(listOf(DownloadEvent.Failed(AppError.Unsupported("machine.exec"), null)), events)
            s.stop()
        }

    // ---- SFTP ------------------------------------------------------------------------------------

    @Test
    fun `sftp happy path writes the file and reports SFTP`() =
        runTest {
            val bytes = put(3 * MIB + 5)
            val host = sftpHost()
            val s = online(host)
            val size = bytes.size.toLong()
            val events = s.download(null, path, target()).toList()
            val token = DownloadResume(null, path, size, 1_700_000_000)
            assertEquals(DownloadEvent.Started(size, DownloadMethod.SFTP, token, 0), events.first())
            val progress = events.filterIsInstance<DownloadEvent.Progress>()
            assertTrue(progress.size >= 2)
            assertEquals(DownloadEvent.Progress(size, size), events[events.size - 2])
            assertEquals(DownloadEvent.Done(size, DownloadMethod.SFTP), events.last())
            assertArrayEquals(bytes, target().readBytes())
            assertTrue(host.execs().isEmpty()) // no stat, no dd on any core
            assertEquals(listOf(path, path), sftp.stats) // start and end
            assertEquals(listOf(path to 0L), sftp.copies)
            assertEquals(1, sftp.opens.get())
            assertEquals(1, sftp.closes.get())
            s.stop()
        }

    @Test
    fun `sftp unavailable falls back to chunked and is remembered`() =
        runTest {
            val bytes = put(MIB)
            sftp.openFailure = DirectFileException(DirectFileException.Reason.UNAVAILABLE, "no subsystem")
            val host = sftpHost()
            val s = online(host)
            assertEquals(
                DownloadEvent.Done(MIB.toLong(), DownloadMethod.CHUNKED),
                s.download(null, path, target()).toList().last(),
            )
            assertArrayEquals(bytes, target().readBytes())
            assertEquals(
                DownloadEvent.Done(MIB.toLong(), DownloadMethod.CHUNKED),
                s.download(null, path, target()).toList().last(),
            )
            assertEquals(1, sftp.opens.get())
            // A new link tries SFTP again.
            host.cores[0].dropConnection()
            s.awaitConnection { it !is ConnectionState.Online }
            s.awaitOnline()
            sftp.openFailure = null
            assertEquals(
                DownloadEvent.Done(MIB.toLong(), DownloadMethod.SFTP),
                s.download(null, path, target()).toList().last(),
            )
            assertEquals(2, sftp.opens.get())
            s.stop()
        }

    @Test
    fun `sftp denied falls back to chunked`() =
        runTest {
            val bytes = put(MIB)
            sftp.denied += path
            val host = sftpHost()
            val s = online(host)
            val events = s.download(null, path, target()).toList()
            assertEquals(DownloadMethod.CHUNKED, events.started().method)
            assertEquals(DownloadEvent.Done(MIB.toLong(), DownloadMethod.CHUNKED), events.last())
            assertArrayEquals(bytes, target().readBytes())
            assertEquals(1, sftp.closes.get())
            assertTrue(sftp.copies.isEmpty())
            s.stop()
        }

    @Test
    fun `missing in sftp but present in core falls back to chunked`() =
        runTest {
            val bytes = put(MIB)
            sftp.hidden += path
            val host = sftpHost()
            val s = online(host)
            val events = s.download(null, path, target()).toList()
            assertEquals(DownloadEvent.Done(MIB.toLong(), DownloadMethod.CHUNKED), events.last())
            assertArrayEquals(bytes, target().readBytes())
            assertEquals(1, host.dds().size)
            s.stop()
        }

    @Test
    fun `missing everywhere fails with FileMissing`() =
        runTest {
            val host = sftpHost()
            val s = online(host)
            val events = s.download(null, "/srv/gone.apk", target()).toList()
            assertEquals(listOf(DownloadEvent.Failed(AppError.FileMissing("/srv/gone.apk"), null)), events)
            assertEquals(listOf("/srv/gone.apk"), sftp.stats)
            assertEquals(1, host.stats().size)
            assertEquals(1, sftp.closes.get())
            s.stop()
        }

    @Test
    fun `federated machine never uses sftp`() =
        runTest {
            put(MIB)
            val host = sftpHost()
            val s = online(host)
            val events = s.download("rohan", path, target()).toList()
            assertEquals(DownloadEvent.Done(MIB.toLong(), DownloadMethod.CHUNKED), events.last())
            assertEquals(0, sftp.opens.get())
            s.stop()
        }

    @Test
    fun `sftp resume continues at the exact partial length`() =
        runTest {
            val bytes = put(3 * MIB)
            target().parentFile.mkdirs()
            target().writeBytes(bytes.copyOf(MIB + 1000))
            val host = sftpHost()
            val s = online(host)
            val token = DownloadResume(null, path, bytes.size.toLong(), 1_700_000_000)
            val events = s.download(null, path, target(), token).toList()
            assertEquals(MIB + 1000L, events.started().resumedFrom)
            assertEquals(listOf(path to MIB + 1000L), sftp.copies)
            assertEquals(DownloadEvent.Done(bytes.size.toLong(), DownloadMethod.SFTP), events.last())
            assertArrayEquals(bytes, target().readBytes())
            s.stop()
        }

    @Test
    fun `sftp resume of a complete file is Done without data calls`() =
        runTest {
            val bytes = put(MIB)
            target().parentFile.mkdirs()
            target().writeBytes(bytes)
            val host = sftpHost()
            val s = online(host)
            val size = bytes.size.toLong()
            val token = DownloadResume(null, path, size, 1_700_000_000)
            val events = s.download(null, path, target(), token).toList()
            assertEquals(
                listOf(
                    DownloadEvent.Started(size, DownloadMethod.SFTP, token, size),
                    DownloadEvent.Progress(size, size),
                    DownloadEvent.Done(size, DownloadMethod.SFTP),
                ),
                events,
            )
            assertTrue(sftp.copies.isEmpty())
            assertEquals(1, sftp.stats.size)
            assertEquals(1, sftp.closes.get())
            s.stop()
        }

    @Test
    fun `sftp file changed during the copy fails with FileChanged`() =
        runTest {
            put(2 * MIB)
            sftp.afterBlock = { copied -> if (copied == MIB.toLong()) files[path]!!.mtimeSeconds += 1 }
            val host = sftpHost()
            val s = online(host)
            val events = s.download(null, path, target()).toList()
            assertEquals(DownloadEvent.Failed(AppError.FileChanged(path), null), events.last())
            s.stop()
        }

    @Test
    fun `sftp link drop mid-file fails with a resume token`() =
        runTest {
            put(2 * MIB)
            sftp.afterBlock = { copied -> if (copied == MIB.toLong()) throw TransportException("link gone") }
            val host = sftpHost()
            val s = online(host)
            val events = s.download(null, path, target()).toList()
            assertEquals(DownloadEvent.Failed(AppError.ConnectionLost, events.started().resume), events.last())
            assertEquals(MIB.toLong(), target().length())
            assertEquals(1, sftp.closes.get())
            s.stop()
        }

    @Test
    fun `progress is throttled`() =
        runTest {
            val bytes = put(40 * MIB)
            sftp.blockSize = 4096
            val host = sftpHost()
            val s = online(host)
            val events = s.download(null, path, target()).toList()
            val progress = events.filterIsInstance<DownloadEvent.Progress>()
            assertTrue("${progress.size} progress events", progress.size <= 40 + 2)
            assertEquals(DownloadEvent.Progress(bytes.size.toLong(), bytes.size.toLong()), progress.last())
            assertEquals(DownloadEvent.Done(bytes.size.toLong(), DownloadMethod.SFTP), events.last())
            s.stop()
        }

    @Test
    fun `sftp copy of a growing file stops at the size and fails with FileChanged`() =
        runTest {
            // SFTP's stat says 2 MiB, but copyTo delivers 3 MiB (the file grew).
            files[path] = FakeRemoteFile(Random(1).nextBytes(3 * MIB), statSize = 2L * MIB)
            val host = sftpHost()
            val s = online(host)
            val events = s.download(null, path, target()).toList()
            assertEquals(DownloadEvent.Failed(AppError.FileChanged(path), null), events.last())
            assertTrue("target ${target().length()} bytes", target().length() <= 2L * MIB)
            assertEquals(1, sftp.closes.get())
            s.stop()
        }

    @Test
    fun `sftp read error on the host is not ConnectionLost and keeps the token`() =
        runTest {
            put(2 * MIB)
            val ioError = DirectFileException(DirectFileException.Reason.IO, "read failed")
            sftp.afterBlock = { copied -> if (copied == MIB.toLong()) throw ioError }
            val host = sftpHost()
            val s = online(host)
            val events = s.download(null, path, target()).toList()
            assertEquals(DownloadEvent.Failed(AppError.Unexpected(ioError), events.started().resume), events.last())
            s.stop()
        }

    @Test
    fun `the download runs on the io context, never on the collector's thread`() {
        val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()
        val io = Executors.newSingleThreadExecutor { Thread(it, "fake-io") }.asCoroutineDispatcher()
        val bytes = Random(3).nextBytes(5 * MIB)
        val threads = CopyOnWriteArrayList<String>()
        val host =
            object : DownloadHost {
                override fun unavailable(): AppError? = null

                override fun directFileSource() = null

                override fun sftpRefused(source: DirectFileSource) = Unit

                override suspend fun exec(
                    argv: List<String>,
                    on: String?,
                    timeout: Duration,
                ): AppResult<ExecResult> {
                    // The reply is decoded and written where this returns to.
                    threads += Thread.currentThread().name
                    val out =
                        if (argv.first() == "sh") {
                            "${bytes.size} 1700000000 regular file".toByteArray()
                        } else {
                            val from = argv.arg("skip").toInt() * 65_536
                            bytes.copyOfRange(from, minOf(bytes.size, from + 4 * MIB))
                        }
                    return AppResult.Success(ExecResult(stdout = Base64.getEncoder().encodeToString(out), code = 0))
                }
            }
        val downloader = Downloader(host, Clock.System, Logger.None, 15.seconds, io, File::getUsableSpace)
        try {
            val events = runBlocking(main) { downloader.download(null, path, target(), null).toList() }
            assertEquals(DownloadEvent.Done(bytes.size.toLong(), DownloadMethod.CHUNKED), events.last())
            assertArrayEquals(bytes, target().readBytes())
            assertEquals(4, threads.size) // stat, 2 chunks, stat
            assertTrue("$threads", threads.all { it.startsWith("fake-io") })
        } finally {
            main.close()
            io.close()
        }
    }

    @Test
    fun `sftp cancel closes the reader and emits no Failed`() =
        runTest {
            put(2 * MIB)
            val held = CompletableDeferred<Unit>()
            sftp.afterBlock = { copied -> if (copied == MIB.toLong()) held.await() }
            val host = sftpHost()
            val s = online(host)
            val events = CopyOnWriteArrayList<DownloadEvent>()
            val job = launch { s.download(null, path, target()).collect { events += it } }
            eventually("half copied") { target().length() == MIB.toLong() }
            job.cancelAndJoin()
            assertEquals(1, sftp.closes.get())
            assertTrue(events.none { it is DownloadEvent.Failed || it is DownloadEvent.Done })
            assertEquals(MIB.toLong(), target().length())
            s.stop()
        }

    private companion object {
        const val MIB = 1024 * 1024
        const val STAT_SCRIPT = "stat -c \"%s %Y %F\" -- \"\$1\" 2>/dev/null || stat -f \"%z %m %HT\" -- \"\$1\""
    }
}
