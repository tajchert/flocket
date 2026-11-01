package com.mtajchert.flocket.core.protocol.testing

import com.mtajchert.flocket.core.protocol.AttachParams
import com.mtajchert.flocket.core.protocol.CoreConnection
import com.mtajchert.flocket.core.protocol.RunParams
import com.mtajchert.flocket.core.protocol.terminalRead
import com.mtajchert.flocket.core.protocol.terminalRun
import com.mtajchert.flocket.core.protocol.transport.ByteTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Throughput benchmarks against a real `hc` (the `*ThroughputIntegrationTest`s). Everything runs in
 * an ISOLATED session `flocket-test-tp<hex>`; [close] stops that server and deletes its state dir.
 * Skip the test when [available] is false. The flood size comes from env `FLOCKET_FLOOD_BYTES`
 * (default 5 000 000 random bytes, base64-encoded by the program: about 6.8 MB on the wire).
 */
class IsolatedHc : AutoCloseable {
    val session = "flocket-test-tp" + Random.nextInt(0x10000, 0xFFFFF).toString(16)
    val env = mapOf("HC_SESSION" to session, "TMPDIR" to "/tmp")
    val workDir: File = Files.createTempDirectory("flocket-tp").toFile()

    init {
        check(session.startsWith("flocket-test-"))
    }

    /** `hc connect` by absolute path, so a non-login SSH exec finds it too. */
    fun connectCommand(): List<String> = listOf(hcPath, "connect", "--session", session)

    fun transport(): ProcessTransport = ProcessTransport(*connectCommand().toTypedArray(), environment = env)

    override fun close() {
        runCatching {
            ProcessBuilder("hc", "server", "stop")
                .apply { environment().putAll(env) }
                .redirectErrorStream(true)
                .start()
                .also { it.inputStream.readBytes() }
                .waitFor()
        }
        File(System.getProperty("user.home"), ".local/state/herdr-core/$session")
            .takeIf { it.name.startsWith("flocket-test-") }
            ?.deleteRecursively()
        workDir.deleteRecursively()
    }

    companion object {
        fun available(): Boolean =
            runCatching {
                ProcessBuilder("hc", "--version")
                    .redirectErrorStream(true)
                    .start()
                    .also { it.inputStream.readBytes() }
                    .waitFor() == 0
            }.getOrDefault(false)

        /** Absolute path of `hc` (falls back to the bare name). */
        val hcPath: String by lazy {
            runCatching {
                ProcessBuilder("sh", "-c", "command -v hc")
                    .start()
                    .inputStream
                    .readBytes()
                    .decodeToString()
                    .trim()
            }.getOrNull()?.takeIf { it.startsWith("/") } ?: "hc"
        }

        val floodBytes: Long get() = System.getenv("FLOCKET_FLOOD_BYTES")?.toLongOrNull() ?: 5_000_000L
    }
}

/**
 * A terminal program that waits for a go signal, floods [bytes] of output ([kind]: `base64` of random
 * bytes, or `color`, the SGR-coloured `yes` of the E2E smoke test), then prints
 * `FLOOD-DONE <seconds>` with its own duration. That duration is what a user feels: with a slow
 * viewer the PTY backs up and the program itself slows down.
 *
 * Go signal: with [dir] the program polls for a file there ([go]); without it (a remote client
 * that cannot create files on the core's host) it reads one line from the terminal ([goInput]).
 */
class FloodProgram(
    private val dir: File?,
    val bytes: Long = IsolatedHc.floodBytes,
    /** `base64` (default) or `color` (env `FLOCKET_FLOOD_KIND`). */
    val kind: String = System.getenv("FLOCKET_FLOOD_KIND") ?: "base64",
    val tag: String = "f" + Random.nextInt(0x1000, 0xFFFF).toString(16),
) {
    private val goFile = dir?.let { File(it, "$tag.go") }

    private fun floodCommand(): String =
        when (kind) {
            "color" -> "yes \"\$(printf '\\033[3%dmflocket\\033[0m ' 1 2 3 4 5 6)\" | head -c $bytes; "
            else -> "head -c $bytes /dev/urandom | base64; "
        }

    private val now = "perl -MTime::HiRes=time -e 'printf q(%.3f), time'"

    /** The flood as one shell line: what a user would type into an interactive shell. */
    val shellLine: String =
        "s=\$($now); " + floodCommand() + "e=\$($now); " +
            "printf '\\n%s-%s %s\\n' FLOOD DONE \"\$(perl -e \"printf q(%.3f), \$e - \$s\")\""

    val argv: List<String> =
        listOf(
            "sh",
            "-c",
            (if (goFile != null) "while [ ! -f '${goFile.name}' ]; do sleep 0.02; done; " else "read -r _; ") +
                shellLine + "; read -r _",
        )

    suspend fun run(conn: CoreConnection): String =
        conn
            .terminalRun(
                RunParams(
                    argv = argv,
                    name = "tp-$tag",
                    cwd = dir?.canonicalPath ?: "/tmp",
                    env = mapOf("TERM" to "xterm-256color"),
                ),
            ).terminal.address

    val hasGoFile: Boolean get() = goFile != null

    /** File go signal (requires [dir]). */
    fun go() {
        checkNotNull(goFile).createNewFile()
    }

    /** Keystrokes that start a program created without [dir] (and end one that finished). */
    val goInput: ByteArray get() = "\r".toByteArray()

    companion object {
        /** Printed once at the end (assembled at runtime, so the command line never contains it). */
        const val MARKER = "FLOOD-DONE"
    }
}

/** Finds [FloodProgram.MARKER] in a byte stream split at arbitrary points. */
class MarkerScanner(
    marker: String = FloodProgram.MARKER,
) {
    private val m = marker.toByteArray()
    private var matched = 0

    private val rest = StringBuilder()

    @Volatile var found = false
        private set

    /** The program's own duration printed after the marker, once the line is complete. */
    @Volatile var programSeconds: Double? = null
        private set

    /** True once the marker line (with the program's duration) was seen completely. */
    fun feed(chunk: ByteArray): Boolean {
        if (programSeconds != null) return true
        for (b in chunk) {
            if (found) {
                val c = b.toInt().toChar()
                if (c == '\r' || c == '\n') {
                    programSeconds = rest.toString().trim().toDoubleOrNull() ?: -1.0
                    return true
                }
                if (rest.length < 32) rest.append(c)
                continue
            }
            matched =
                if (b == m[matched]) {
                    matched + 1
                } else if (b == m[0]) {
                    1
                } else {
                    0
                }
            if (matched == m.size) found = true
        }
        return false
    }
}

/** Counts what goes over a transport, in particular outgoing CREDIT frames (one frame per write). */
class CountingTransport(
    private val delegate: ByteTransport,
) : ByteTransport {
    val readBytes = AtomicLong()
    val reads = AtomicLong()
    val writes = AtomicLong()
    val creditFrames = AtomicLong()
    val creditBytes = AtomicLong()

    override suspend fun read(buffer: ByteArray): Int =
        delegate.read(buffer).also {
            if (it > 0) {
                readBytes.addAndGet(it.toLong())
                reads.incrementAndGet()
            }
        }

    override suspend fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        writes.incrementAndGet()
        // A CREDIT frame: 9-byte header, kind 1, 4-byte LE payload. Writes may batch several frames.
        var p = offset
        val end = offset + length
        while (p + 9 <= end) {
            val len = le32(bytes, p + 5)
            if (bytes[p + 4].toInt() == 1 && len == 4 && p + 13 <= end) {
                creditFrames.incrementAndGet()
                creditBytes.addAndGet(le32(bytes, p + 9).toLong() and 0xFFFFFFFFL)
            }
            if (len < 0 || p + 9 + len > end) break
            p += 9 + len
        }
        delegate.write(bytes, offset, length)
    }

    override fun diagnostics(): String = delegate.diagnostics()

    override suspend fun close() = delegate.close()

    private fun le32(
        b: ByteArray,
        i: Int,
    ): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
}

/** One measurement, appended to `build/throughput/results.txt` of the running module. */
data class FloodResult(
    val layer: String,
    val receivedBytes: Long,
    val seconds: Double,
    val programSeconds: Double,
    val creditFrames: Long = -1,
    val creditBytes: Long = -1,
) {
    val mbPerSecond: Double get() = receivedBytes / seconds / 1_000_000.0

    fun line(): String =
        "%-28s received %.2f MB in %.2f s = %.2f MB/s; program %.2f s; credit frames %d (avg %d B)".format(
            layer,
            receivedBytes / 1e6,
            seconds,
            mbPerSecond,
            programSeconds,
            creditFrames,
            if (creditFrames > 0) creditBytes / creditFrames else 0,
        )

    fun report(): FloodResult {
        println("THROUGHPUT ${line()}")
        runCatching {
            File("build/throughput").apply { mkdirs() }.resolve("results.txt").appendText(line() + "\n")
        }
        return this
    }
}

/**
 * Runs [flood] with no viewer and returns the program's own duration (polls `terminal.read`).
 * The program must use a file go signal.
 */
suspend fun headlessFloodSeconds(
    conn: CoreConnection,
    flood: FloodProgram,
): Double {
    val address = flood.run(conn)
    flood.go()
    val re = Regex("DONE ([0-9.]+)") // terminal.read may cut the line start
    while (true) {
        val text = conn.terminalRead(address, 0).text
        re.find(text)?.let { return it.groupValues[1].toDouble() }
        delay(50)
    }
}

/**
 * Attaches to [flood] as an owner (200x50, FOCUS 1) and drains the output as fast as possible on
 * [scope]; returns the time from the go signal to the marker. [counting] wraps the connection's
 * transport, for the CREDIT statistics.
 */
suspend fun drainFlood(
    conn: CoreConnection,
    counting: CountingTransport,
    flood: FloodProgram,
    layer: String,
    scope: CoroutineScope,
): FloodResult {
    val address = flood.run(conn)
    val att = conn.attach(AttachParams(address, 200, 50))
    att.focus(true)
    val scanner = MarkerScanner()
    val done = CompletableDeferred<Unit>()
    val received = AtomicLong()
    val job =
        scope.launch {
            att.output.collect { chunk ->
                received.addAndGet(chunk.size.toLong())
                if (scanner.feed(chunk)) done.complete(Unit)
            }
        }
    val credit0 = counting.creditFrames.get()
    val creditBytes0 = counting.creditBytes.get()
    val t0 = System.nanoTime()
    if (flood.hasGoFile) flood.go() else att.send(flood.goInput)
    done.await()
    val seconds = (System.nanoTime() - t0) / 1e9
    val r =
        FloodResult(
            "$layer, ${flood.kind}",
            received.get(),
            seconds,
            scanner.programSeconds ?: -1.0,
            counting.creditFrames.get() - credit0,
            counting.creditBytes.get() - creditBytes0,
        )
    att.close()
    job.cancel()
    return r
}
