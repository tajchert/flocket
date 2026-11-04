package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.result.getOrThrow
import com.mtajchert.flocket.core.model.AgentShare
import com.mtajchert.flocket.core.model.MAX_TRANSFER_BYTES
import com.mtajchert.flocket.core.model.TerminalAddress
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * File transfers and agent shares against a real `hc` in isolated sessions ([RealCoreHarness]). The
 * session runs on `hc connect` processes, so the transfer connection is a second `hc connect` (the
 * fallback path; on SSH it is a sibling exec channel).
 */
class TransferRealCoreIntegrationTest {
    private val hc = RealCoreHarness()

    @Before
    fun setUp() = hc.setUp()

    @After
    fun tearDown() = hc.tearDown()

    @Test
    fun `putFile stores the bytes next to the terminal at the returned path`() =
        runBlocking {
            withTimeout(60.seconds) {
                val s = online()
                val rec =
                    s
                        .run(
                            RunRequest(argv = listOf("sh"), cwd = hc.workDir.canonicalPath, name = "it-put"),
                        ).getOrThrow()
                s.state.first { it.terminal(rec.address) != null }
                val bytes = Random.nextBytes(200_000)

                val receipt = s.putFile(rec.address, "my shot.png", bytes).getOrThrow()

                assertTrue("absolute path: ${receipt.path}", receipt.path.startsWith("/"))
                assertTrue("sanitised name: ${receipt.path}", receipt.path.endsWith("/my_shot.png"))
                assertEquals(bytes.size.toLong(), receipt.bytes)
                assertArrayEquals(bytes, File(receipt.path).readBytes())
                s.stop()
            }
        }

    @Test
    fun `writeFile then readFile round trips 1 MB of random bytes`() =
        runBlocking {
            withTimeout(60.seconds) {
                val s = online()
                val path = File(hc.workDir, "blob.bin").absolutePath
                val bytes = Random.nextBytes(1024 * 1024)

                s.writeFile(null, path, bytes).getOrThrow()
                assertArrayEquals("on disk", bytes, File(path).readBytes())
                assertArrayEquals("read back", bytes, s.readFile(null, path).getOrThrow())
                s.stop()
            }
        }

    @Test
    fun `flocket-share in a local and a federated terminal shows up in state shares`() =
        runBlocking {
            withTimeout(60.seconds) {
                hc.addRemoteMachine("rohan")
                val s = online()
                val local =
                    s
                        .run(
                            RunRequest(argv = listOf("sh"), cwd = hc.workDir.canonicalPath, name = "it-share"),
                        ).getOrThrow()
                val remote =
                    s
                        .run(
                            RunRequest(
                                argv = listOf("sh"),
                                cwd = hc.workDir.canonicalPath,
                                name = "it-rshare",
                                machine = "rohan",
                            ),
                        ).getOrThrow()
                s.state.first { it.terminal(local.address) != null && it.terminal(remote.address) != null }
                val report = File(hc.workDir, "report.png").apply { writeText("png") }.absolutePath
                val notes = File(hc.workDir, "notes.md").apply { writeText("# notes") }.absolutePath

                // The helper runs as the agent would inside each terminal: HC_TERMINAL_ID is the id on its own core.
                val (rc, out) = shareFrom(hc.session, local.address.value, "-m", "build done", report)
                assertEquals(out, 0, rc)
                val mine =
                    s.state
                        .first { st -> st.shares.any { it.address == local.address } }
                        .shares
                        .single()
                assertEquals(null, mine.machine)
                assertEquals(listOf(report), mine.paths)
                assertEquals("build done", mine.note)
                assertTrue("32 hex id: ${mine.id}", Regex("[0-9a-f]{32}").matches(mine.id))

                val rohanId = remote.address.value.substringAfter('/')
                val (rc2, out2) = shareFrom(hc.remoteSession, rohanId, notes)
                assertEquals(out2, 0, rc2)
                val theirs: AgentShare =
                    s.state
                        .first { st -> st.shares.any { it.address == remote.address } }
                        .shares
                        .single { it.address == remote.address }
                assertEquals(TerminalAddress("rohan/$rohanId"), theirs.address)
                assertEquals("rohan", theirs.machine)
                assertEquals(listOf(notes), theirs.paths)
                assertNull(theirs.note)
                s.stop()
            }
        }

    /**
     * ADR-0011 §4: does an 8 MiB transfer with a federated machine stall that machine's other terminals?
     * Over a plain pipe link (`--via "sh -c"`), so it is fast and always runs; prints the measurement.
     */
    @Test
    fun `an 8 MiB transfer with a federated machine over a pipe link and the stall of its terminals`() =
        runBlocking { measureFederatedStall("pipe", via = "sh -c") }

    /**
     * The same over a link limited to 10 Mbit/s each way (a slow SSH link to rohan), so a transfer takes
     * seconds and head-of-line blocking on the core's machine link would show. Slow (about 25 s): runs
     * only with `FLOCKET_SLOW=1`.
     */
    @Test
    fun `an 8 MiB transfer with a federated machine over a 10 Mbit link and the stall of its terminals`() =
        runBlocking {
            assumeTrue("set FLOCKET_SLOW=1 to measure over a throttled link", System.getenv("FLOCKET_SLOW") == "1")
            val throttle =
                "perl -e '\$|=1; while (sysread(STDIN, \$b, 16384)) { syswrite(STDOUT, \$b); " +
                    "select(undef, undef, undef, length(\$b) / $LINK_BYTES_PER_SECOND) }'"
            val via =
                File(hc.workDir, "slow-link.sh").apply {
                    writeText("#!/bin/sh\n$throttle | sh -c \"\$1\" | $throttle\n")
                }
            measureFederatedStall("10 Mbit", via = "sh ${via.absolutePath}")
        }

    /**
     * A `rohan` terminal and a local one print a line every 20 ms; a third `rohan` terminal runs `cat` and
     * is typed into every 100 ms. Records the largest gap between the tickers' output chunks and the
     * slowest keystroke echo from rohan, with no transfer, during an 8 MiB `readFile` from rohan and an
     * 8 MiB `writeFile` to it.
     */
    private suspend fun measureFederatedStall(
        link: String,
        via: String,
    ) = withTimeout(90.seconds) {
        hc.addRemoteMachine("rohan", via)
        val s = online()
        val onRohan = s.exec("rohan", listOf("sh", "-c", "printf %s \"\$HC_SESSION\""), mutating = false).getOrThrow()
        assertEquals("machine.exec reaches rohan's core", hc.remoteSession, onRohan.stdout.decodeToString())
        val ticker = "while :; do date +%s; sleep 0.02; done"
        val cwd = hc.workDir.canonicalPath
        val remote = s.run(RunRequest(line = ticker, cwd = cwd, name = "it-rtick", machine = "rohan")).getOrThrow()
        val local = s.run(RunRequest(line = ticker, cwd = cwd, name = "it-tick")).getOrThrow()
        val echo = s.run(RunRequest(argv = listOf("cat"), cwd = cwd, name = "it-recho", machine = "rohan")).getOrThrow()
        s.state.first { st -> listOf(remote, local, echo).all { st.terminal(it.address) != null } }
        val remoteChunks = arrivals(s, remote.address).second
        val localChunks = arrivals(s, local.address).second
        val (echoStream, echoChunks) = arrivals(s, echo.address)
        waitFor { remoteChunks.size > 20 && localChunks.size > 20 && echoStream.connected.value }
        val size = MAX_TRANSFER_BYTES.toInt()
        val file = File(hc.workDir, "big.bin").apply { writeBytes(Random.nextBytes(size)) }
        val upload = Random.nextBytes(size)

        val windows =
            listOf(
                "baseline" to suspend { delay(2_000) },
                "readFile 8 MiB" to
                    suspend { assertEquals(size, s.readFile("rohan", file.absolutePath).getOrThrow().size) },
                "writeFile 8 MiB" to
                    suspend { s.writeFile("rohan", File(hc.workDir, "up.bin").absolutePath, upload).getOrThrow() },
            )
        for ((name, block) in windows) {
            val (w, echoMs) = typing(echoStream, echoChunks) { window(block) }
            println(
                "FederatedTransferStall [$link] $name ${(w.last - w.first) / 1_000_000} ms: max output gap " +
                    "rohan ${maxGapMs(remoteChunks, w)} ms, local ${maxGapMs(localChunks, w)} ms; " +
                    "slowest rohan echo $echoMs ms",
            )
        }
        assertArrayEquals(upload, File(hc.workDir, "up.bin").readBytes())
        s.stop()
    }

    /** Runs [block] while typing into [stream] every 100 ms; returns its result and the slowest echo (ms). */
    private suspend fun <T> typing(
        stream: TerminalStream,
        chunks: List<Long>,
        block: suspend () -> T,
    ): Pair<T, Long> =
        coroutineScope {
            val slowest = AtomicLong()
            val pendingSince = AtomicLong()
            val probe =
                launch {
                    while (true) {
                        val seen = chunks.size
                        pendingSince.set(System.nanoTime())
                        stream.write("x".toByteArray()).getOrThrow()
                        waitFor { chunks.size > seen }
                        slowest.accumulateAndGet(System.nanoTime() - pendingSince.getAndSet(0), ::maxOf)
                        delay(100)
                    }
                }
            val result = block()
            probe.cancelAndJoin()
            val pending = pendingSince.get().takeIf { it != 0L }?.let { System.nanoTime() - it } ?: 0
            result to maxOf(slowest.get(), pending) / 1_000_000
        }

    private suspend fun online(): CoreSessionImpl =
        hc.newSession().also {
            it.start()
            it.awaitOnline()
        }

    private fun shareFrom(
        session: String,
        terminalId: String,
        vararg args: String,
    ): Pair<Int, String> =
        hc.run(listOf("sh", helper.path, *args), mapOf("HC_SESSION" to session, "HC_TERMINAL_ID" to terminalId))

    /** Attaches to [address]; the list collects its output chunks' arrival times (ns) until teardown. */
    private suspend fun arrivals(
        s: CoreSession,
        address: TerminalAddress,
    ): Pair<TerminalStream, List<Long>> {
        val times = CopyOnWriteArrayList<Long>()
        val stream = s.attach(address, AttachRequest(80, 24, AttachMode.Passive)).getOrThrow()
        hc.scope.launch { stream.output.collect { times.add(System.nanoTime()) } }
        return stream to times
    }

    /** The nanoTime range [block] ran in. */
    private suspend fun window(block: suspend () -> Any): LongRange {
        val start = System.nanoTime()
        block()
        return start..System.nanoTime()
    }

    /** Largest gap between consecutive chunks inside [w], including the edges of [w]. */
    private fun maxGapMs(
        times: List<Long>,
        w: LongRange,
    ): Long {
        val inside = listOf(w.first) + times.filter { it in w } + w.last
        return inside.zipWithNext { a, b -> b - a }.max() / 1_000_000
    }

    private suspend fun waitFor(cond: () -> Boolean) {
        while (!cond()) delay(20)
    }

    private companion object {
        /** 10 Mbit/s. */
        const val LINK_BYTES_PER_SECOND = 1_250_000

        val helper = File(System.getProperty("flocket.fixtures.dir"), "../tools/flocket-share").canonicalFile
    }
}
