package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.CoreEndpoint
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.protocol.testing.ProcessTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Two ISOLATED real `hc` sessions for a test (docs/TESTING.md): [session] is the core the app talks
 * to, [remoteSession] can play its federated machine (added with `--via "sh -c"`, no SSH). Names stay
 * within the Unix socket path limit; `TMPDIR=/tmp`; caller's `HC_*` variables are never inherited.
 * [setUp] skips the test when `hc` is not on PATH; [tearDown] stops both servers, deletes their
 * state and checks that the default session's state directory is unchanged.
 */
class RealCoreHarness {
    val session = "flocket-test-" + Random.nextInt(0x10000, 0xFFFFF).toString(16)
    val remoteSession = "flocket-test-r" + Random.nextInt(0x1000, 0xFFFF).toString(16)
    val env = mapOf("HC_SESSION" to session, "TMPDIR" to "/tmp")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var workDir: File
        private set

    private var remoteStarted = false
    private val stateRoot = File(System.getProperty("user.home"), ".local/state/herdr-core")
    private var defaultEntriesBefore: List<String>? = null

    fun setUp() {
        assumeTrue("hc not on PATH: skipping real core test", runCatching { hc("--version") == 0 }.getOrDefault(false))
        check(session.startsWith("flocket-test-") && remoteSession.startsWith("flocket-test-"))
        workDir = Files.createTempDirectory("flocket-session-it").toFile()
        defaultEntriesBefore = defaultEntries()
    }

    fun tearDown() {
        scope.cancel()
        if (!::workDir.isInitialized) return
        runCatching { hc("server", "stop") }
        if (remoteStarted) runCatching { hc("server", "stop", session = remoteSession) }
        for (name in listOf(session, remoteSession)) {
            File(stateRoot, name).takeIf { it.name.startsWith("flocket-test-") }?.deleteRecursively()
        }
        workDir.deleteRecursively()
        assertEquals("default session state untouched", defaultEntriesBefore, defaultEntries())
    }

    /** Runs `hc ARGS` against [session] (default: the main one); returns the exit code. */
    fun hc(
        vararg args: String,
        session: String = this.session,
    ): Int = run(listOf("hc", *args), mapOf("HC_SESSION" to session)).first

    /** Runs [command] with the isolated environment plus [extraEnv]; returns (exit code, stdout + stderr). */
    fun run(
        command: List<String>,
        extraEnv: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val p =
            ProcessBuilder(command)
                .apply {
                    environment().keys.removeIf { it.startsWith("HC_") } // never inherit the caller's herdr session
                    environment().putAll(env + extraEnv)
                }.redirectErrorStream(true)
                .start()
        val out = p.inputStream.readBytes().decodeToString()
        return p.waitFor() to out
    }

    /** Adds [remoteSession] as the federated machine [name] of [session], reached by running [via] locally. */
    fun addRemoteMachine(
        name: String,
        via: String = "sh -c",
    ) {
        remoteStarted = true
        assertEquals(0, hc("machine", "add", "--via", via, "--name", name, "--session", remoteSession))
    }

    /** A session on [session] over `hc connect` processes (the transfer connection opens a second one). */
    fun newSession(): CoreSessionImpl =
        CoreSessionImpl(
            CoreEndpoint(HostId("it"), session),
            { AppResult.Success(ProcessTransport("hc", "connect", "--session", session, environment = env)) },
            scope,
            config = SessionConfig(backoffBase = 0.2.seconds),
        )

    private fun defaultEntries() = File(stateRoot, "default").list()?.sorted()
}
