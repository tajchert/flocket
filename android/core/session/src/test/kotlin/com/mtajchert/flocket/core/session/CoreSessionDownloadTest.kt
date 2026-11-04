package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.model.MAX_DOWNLOAD_BYTES
import com.mtajchert.flocket.core.protocol.testing.FakeCore
import com.mtajchert.flocket.core.protocol.testing.FakeCoreConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

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

    private companion object {
        const val MIB = 1024 * 1024
        const val STAT_SCRIPT = "stat -c \"%s %Y %F\" -- \"\$1\" 2>/dev/null || stat -f \"%z %m %HT\" -- \"\$1\""
    }
}
