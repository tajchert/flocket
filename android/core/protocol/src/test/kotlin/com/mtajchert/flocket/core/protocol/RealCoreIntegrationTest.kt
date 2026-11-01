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
