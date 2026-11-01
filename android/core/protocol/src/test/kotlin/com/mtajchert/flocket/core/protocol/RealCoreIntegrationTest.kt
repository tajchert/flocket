package com.mtajchert.flocket.core.protocol

import com.mtajchert.flocket.core.protocol.dto.DisplayDto
import com.mtajchert.flocket.core.protocol.testing.ProcessTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the client against a real `hc` core in an ISOLATED session (`flocket-test-<random>`).
 * Skipped when `hc` is not on PATH. Teardown stops that session's server and deletes its state
 * directory; it never touches the default session.
 */
class RealCoreIntegrationTest {
    private val session = "flocket-test-" + Random.nextInt(0x10000, 0xFFFFF).toString(16)
    private val env = mapOf("HC_SESSION" to session, "TMPDIR" to "/tmp")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var workDir: File

    private fun hcOnPath(): Boolean =
        runCatching {
            ProcessBuilder(
                "hc",
                "--version",
            ).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() ==
                0
        }.getOrDefault(false)

    @Before
    fun setUp() {
        assumeTrue("hc not on PATH: skipping real core test", hcOnPath())
        check(session.startsWith("flocket-test-"))
        workDir = Files.createTempDirectory("flocket-it").toFile()
    }

    @After
    fun tearDown() {
        scope.cancel()
        if (!::workDir.isInitialized) return
        runCatching {
            ProcessBuilder("hc", "server", "stop")
                .apply {
                    environment().putAll(env)
                }.redirectErrorStream(true)
                .start()
                .also { it.inputStream.readBytes() }
                .waitFor()
        }
        File(System.getProperty("user.home"), ".local/state/herdr-core/$session")
            .takeIf {
                it.name.startsWith("flocket-test-")
            }?.deleteRecursively()
        workDir.deleteRecursively()
    }

    /** SPEC §7.5 / §13: on hc 0.1.16 FACTS from a passive viewer does not take size ownership. */
    @Test
    fun `FACTS from a passive viewer does not take size ownership on 0_1_16`() =
        runBlocking {
            withTimeout(40.seconds) {
                println("RealCoreIntegrationTest: FACTS test against isolated session $session")

                fun transport() = ProcessTransport("hc", "connect", "--session", session, environment = env)
                val a = CoreConnection.open(transport(), scope)
                val b = CoreConnection.open(transport(), scope)
                try {
                    assertTrue("hc ${a.info.version} accepted", !CoreConnection.isOlderThanMinimum(a.info.version))
                    val address =
                        a
                            .terminalRun(
                                RunParams(argv = listOf("sh"), name = "it-facts", cwd = workDir.canonicalPath),
                            ).terminal.address
                    val owner = a.attach(AttachParams(address, 80, 24))
                    val ownerDrain = scope.launch { owner.output.collect { } }
                    assertEquals(80 to 24, a.terminalGet(address).let { it.cols to it.rows })

                    // Passive viewer: attach resumed (not active), different size, then FACTS.
                    val passive = b.attach(AttachParams(address, 120, 40, resumed = true))
                    val passiveDrain = scope.launch { passive.output.collect { } }
                    assertEquals(80 to 24, b.terminalGet(address).let { it.cols to it.rows })
                    passive.facts(DisplayDto(fg = "#ffffff", bg = "#101010", cursor = "#ffffff", cell = listOf(9, 18)))
                    kotlinx.coroutines.delay(750)
                    assertEquals(
                        "FACTS must not make a passive viewer the size owner",
                        80 to 24,
                        a.terminalGet(address).let { it.cols to it.rows },
                    )

                    // Positive control: a resize does take ownership, so the check above can fail.
                    passive.resize(120, 40)
                    var size = 0 to 0
                    var tries = 0
                    while (size != 120 to 40 && tries++ < 50) {
                        kotlinx.coroutines.delay(100)
                        size = a.terminalGet(address).let { it.cols to it.rows }
                    }
                    assertEquals("RESIZE makes the viewer active", 120 to 40, size)

                    passive.close()
                    owner.close()
                    ownerDrain.cancel()
                    passiveDrain.cancel()
                    a.terminalKill(address)
                    Unit
                } finally {
                    b.close()
                    a.close()
                }
            }
        }

    @Test
    fun `full journey against a real core run attach type resize detach kill rm`() =
        runBlocking {
            withTimeout(30.seconds) {
                println("RealCoreIntegrationTest: RUNNING against isolated session $session")
                val transport = ProcessTransport("hc", "connect", "--session", session, environment = env)
                val conn = CoreConnection.open(transport, scope)
                try {
                    assertEquals(1, conn.info.generation)
                    val sub = conn.subscribeEvents()
                    assertTrue("isolated session starts empty", sub.terminals.isEmpty())

                    val run =
                        conn.terminalRun(
                            RunParams(
                                argv = listOf("sh"),
                                name = "it-sh",
                                cwd = workDir.canonicalPath,
                                env =
                                    mapOf("TERM" to "xterm-256color", "PS1" to "$ "),
                            ),
                        )
                    val address = run.terminal.address
                    assertEquals("live", run.terminal.state)

                    val att =
                        conn.attach(
                            AttachParams(
                                address,
                                80,
                                24,
                                display = DisplayDto(fg = "#ffffff", bg = "#000000", cell = listOf(9, 18)),
                            ),
                        )
                    val seen = StringBuilder()
                    val seenHi = kotlinx.coroutines.CompletableDeferred<Unit>()
                    val collector =
                        scope.launch {
                            att.output.collect { chunk ->
                                seen.append(chunk.decodeToString())
                                if (Regex(
                                        "(?m)^hi\\s*$",
                                    ).containsMatchIn(seen.toString().replace("\r", ""))
                                ) {
                                    seenHi.complete(Unit)
                                }
                            }
                        }
                    att.send("echo hi\r".toByteArray())
                    seenHi.await()
                    att.resize(100, 30)
                    att.focus(true)
                    att.close()
                    assertEquals(ChannelEnd.LocalDetach, att.closeReason.value)
                    collector.join()

                    val killed = conn.terminalKill(address)
                    assertEquals("finished", killed.state)
                    assertEquals(address, conn.terminalRm(address))
                    sub.close()
                } finally {
                    conn.close()
                }
            }
        }
}
