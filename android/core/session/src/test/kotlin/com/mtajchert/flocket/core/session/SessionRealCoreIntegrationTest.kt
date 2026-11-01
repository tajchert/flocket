package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.common.result.getOrThrow
import com.mtajchert.flocket.core.model.CoreEndpoint
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.protocol.CoreConnection
import com.mtajchert.flocket.core.protocol.docSet
import com.mtajchert.flocket.core.protocol.testing.ProcessTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The session against a real `hc` core in an ISOLATED session (`flocket-test-<random>`, `TMPDIR=/tmp`,
 * see docs/TESTING.md). Skipped when `hc` is not on PATH. Teardown stops that server and deletes its
 * state; the default session's state directory must be unchanged.
 */
class SessionRealCoreIntegrationTest {
    private val session = "flocket-test-" + Random.nextInt(0x10000, 0xFFFFF).toString(16)
    private val env = mapOf("HC_SESSION" to session, "TMPDIR" to "/tmp")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateRoot = File(System.getProperty("user.home"), ".local/state/herdr-core")
    private lateinit var workDir: File
    private var defaultEntriesBefore: List<String>? = null

    private fun hc(vararg args: String): Int =
        ProcessBuilder("hc", *args)
            .apply { environment().putAll(env) }
            .redirectErrorStream(true)
            .start()
            .also { it.inputStream.readBytes() }
            .waitFor()

    private fun defaultEntries() = File(stateRoot, "default").list()?.sorted()

    @Before
    fun setUp() {
        assumeTrue("hc not on PATH: skipping real core test", runCatching { hc("--version") == 0 }.getOrDefault(false))
        check(session.startsWith("flocket-test-"))
        workDir = Files.createTempDirectory("flocket-session-it").toFile()
        defaultEntriesBefore = defaultEntries()
    }

    @After
    fun tearDown() {
        scope.cancel()
        if (!::workDir.isInitialized) return
        runCatching { hc("server", "stop") }
        File(stateRoot, session).takeIf { it.name.startsWith("flocket-test-") }?.deleteRecursively()
        workDir.deleteRecursively()
        assertEquals("default session state untouched", defaultEntriesBefore, defaultEntries())
    }

    @Test
    fun `run with tilde cwd expands to the home folder of the core host`() =
        runBlocking {
            withTimeout(60.seconds) {
                val s =
                    CoreSessionImpl(
                        CoreEndpoint(HostId("it"), session),
                        {
                            AppResult.Success(
                                ProcessTransport("hc", "connect", "--session", session, environment = env),
                            )
                        },
                        scope,
                        config = SessionConfig(backoffBase = 0.2.seconds),
                    )
                s.start()
                s.awaitOnline()
                val home = System.getenv("HOME") ?: System.getProperty("user.home")
                val rec = s.run(RunRequest(argv = listOf("sh"), cwd = "~", name = "it-tilde")).getOrThrow()
                assertTrue("absolute cwd: ${rec.cwd}", rec.cwd.startsWith("/"))
                assertEquals(File(home).canonicalPath, File(rec.cwd).canonicalPath)
                assertEquals(home, s.homeDirectory().getOrThrow())
                s.state.first { st -> st.terminal(rec.address) != null }
                s.stop()
            }
        }

    @Test
    fun `run attach type, survive a dropped transport with a fresh paint, stop`() =
        runBlocking {
            withTimeout(60.seconds) {
                println("SessionRealCoreIntegrationTest: RUNNING against isolated session $session")
                var current: ProcessTransport? = null
                val s =
                    CoreSessionImpl(
                        CoreEndpoint(HostId("it"), session),
                        {
                            AppResult.Success(
                                ProcessTransport("hc", "connect", "--session", session, environment = env).also {
                                    current =
                                        it
                                },
                            )
                        },
                        scope,
                        config = SessionConfig(backoffBase = 0.2.seconds),
                    )
                s.start()
                s.awaitOnline()
                assertTrue(
                    "isolated session starts empty",
                    s.state.value.terminals
                        .isEmpty(),
                )

                val rec =
                    s
                        .run(
                            RunRequest(argv = listOf("sh"), cwd = workDir.canonicalPath, name = "it-sh"),
                        ).getOrThrow()
                s.state.first { st -> st.terminal(rec.address) != null }

                val stream = s.attach(rec.address, AttachRequest(80, 24, AttachMode.Passive)).getOrThrow()
                val out = StringBuffer()
                val collector = scope.launch { stream.output.collect { out.append(it.decodeToString()) } }
                assertTrue(stream.write("echo hello\r".toByteArray()) is AppResult.Success)
                waitFor { Regex("(?m)^hello\\s*$").containsMatchIn(out.toString().replace("\r", "")) }
                val paintsBefore = paints(out)

                current!!.close() // simulate a dropped link
                waitFor { !stream.connected.value }
                s.awaitOnline()
                waitFor { stream.connected.value }
                waitFor { paints(out) > paintsBefore }
                assertTrue(stream.write("echo again\r".toByteArray()) is AppResult.Success)
                waitFor { Regex("(?m)^again\\s*$").containsMatchIn(out.toString().replace("\r", "")) }
                assertNull(stream.end.value)

                s.stop()
                collector.cancel()
                assertNull("stop parks the stream; the program keeps running", stream.end.value)
            }
        }

    @Test
    fun `a herdr-app document written by another client shows up as the desktop layout`() =
        runBlocking {
            withTimeout(60.seconds) {
                val s =
                    CoreSessionImpl(
                        CoreEndpoint(HostId("it"), session),
                        {
                            AppResult.Success(
                                ProcessTransport("hc", "connect", "--session", session, environment = env),
                            )
                        },
                        scope,
                        config = SessionConfig(backoffBase = 0.2.seconds),
                    )
                s.start()
                s.awaitOnline()
                assertNull("isolated session has no herdr-app document", s.state.value.desktopLayout)
                // Play the desktop app: a raw doc.set on a second connection to the isolated session.
                val writer =
                    CoreConnection.open(
                        ProcessTransport("hc", "connect", "--session", session, environment = env),
                        scope,
                    )
                val doc =
                    Json
                        .parseToJsonElement(
                            File(System.getProperty("flocket.fixtures.dir"), "herdr-app-layout.json")
                                .readText(),
                        ).jsonObject["value"]!!
                writer.docSet(HerdrAppLayoutParser.DOCUMENT, doc, version = 0)
                val layout = s.state.mapNotNull { it.desktopLayout }.first()
                assertEquals(listOf("sp1a2b", "sp3c4d"), layout.spaces.map { it.id })
                assertEquals(
                    listOf("t7", "t9"),
                    layout.spaces[0]
                        .tabs[0]
                        .panes
                        .map { it.value },
                )
                writer.close()
                s.stop()
            }
        }

    /** First paints start with DECSTR (0.1.15) or CAN (0.1.16). */
    private fun paints(out: StringBuffer) = Regex("\u0018|\u001b\\[!p").findAll(out).count()

    private suspend fun waitFor(cond: () -> Boolean) {
        while (!cond()) delay(20)
    }
}
