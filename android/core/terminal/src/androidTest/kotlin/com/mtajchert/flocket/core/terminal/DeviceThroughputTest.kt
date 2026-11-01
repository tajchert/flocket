package com.mtajchert.flocket.core.terminal

import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.CoreEndpoint
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.protocol.CoreConnection
import com.mtajchert.flocket.core.protocol.testing.CountingTransport
import com.mtajchert.flocket.core.protocol.testing.FloodProgram
import com.mtajchert.flocket.core.protocol.testing.FloodResult
import com.mtajchert.flocket.core.protocol.testing.MarkerScanner
import com.mtajchert.flocket.core.protocol.testing.drainFlood
import com.mtajchert.flocket.core.protocol.transport.ByteTransport
import com.mtajchert.flocket.core.session.AttachMode
import com.mtajchert.flocket.core.session.AttachRequest
import com.mtajchert.flocket.core.session.CoreSessionImpl
import com.mtajchert.flocket.core.session.RunRequest
import com.mtajchert.flocket.core.session.TerminalStream
import com.mtajchert.flocket.core.ssh.HcLocator
import com.mtajchert.flocket.core.ssh.HostKeyDecision
import com.mtajchert.flocket.core.ssh.SshConnector
import com.mtajchert.flocket.core.ssh.SshCredentials
import com.mtajchert.flocket.core.ssh.SshSession
import com.mtajchert.flocket.core.ssh.SshTarget
import com.mtajchert.flocket.core.terminal.controller.TerminalController
import com.mtajchert.flocket.core.terminal.engine.TerminalEngineFactory
import com.mtajchert.flocket.core.terminal.ghostty.GhosttyTerminalEngine
import com.mtajchert.flocket.core.terminal.ui.TerminalSurface
import com.mtajchert.flocket.core.terminal.ui.TerminalThemes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * Layers 2-3 of the throughput bisection ON A DEVICE (docs/TERMINAL_RENDERING.md, Performance):
 * a flood drained over SSHJ by [CoreConnection] alone, then through CoreSession + TerminalStream +
 * [TerminalController] with the real Ghostty engine and the Choreographer frame clock.
 *
 * Needs a reachable sshd that execs `hc` (scripts/dev-sshd.sh on the host, 10.0.2.2 from the
 * emulator) and instrumentation args `tpPort`, `tpUser`, `tpKey` (base64 of an OpenSSH private
 * key), `tpHc` (absolute hc path), `tpSession` (an isolated `flocket-test-*` session the caller
 * stops afterwards); optional `tpHost` (default 10.0.2.2), `tpKind` (base64|color), `tpBytes`.
 * Skipped without them.
 */
@RunWith(AndroidJUnit4::class)
class DeviceThroughputTest {
    private val args = InstrumentationRegistry.getArguments()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val kind = args.getString("tpKind") ?: "base64"
    private val bytes = args.getString("tpBytes")?.toLongOrNull() ?: 5_000_000L
    private val cols = args.getString("tpCols")?.toIntOrNull() ?: 200
    private val rows = args.getString("tpRows")?.toIntOrNull() ?: 50

    /** `tpShell=1`: run the host's login shell and type the flood into it, like the E2E journey. */
    private val shell = args.getString("tpShell") == "1"

    /** `tpUi=1`: show the controller in a [TerminalSurface] (real View rendering + vsync). */
    private val ui = args.getString("tpUi") == "1"

    @After
    fun tearDown() = scope.cancel()

    private fun requireArgs(): Triple<SshTarget, SshCredentials, List<String>> {
        val port = args.getString("tpPort")?.toIntOrNull()
        val user = args.getString("tpUser")
        val key = args.getString("tpKey")
        val hc = args.getString("tpHc")
        val session = args.getString("tpSession")
        assumeTrue(
            "tp* instrumentation args not set",
            port != null && user != null && key != null && hc != null && session != null,
        )
        check(session!!.startsWith("flocket-test-")) { "only isolated sessions" }
        val pem = String(Base64.decode(key, Base64.DEFAULT)).toCharArray()
        return Triple(
            SshTarget(args.getString("tpHost") ?: "10.0.2.2", port!!, user!!),
            SshCredentials.PrivateKey(pem, null),
            HcLocator.coreCommand(hc!!, session),
        )
    }

    private suspend fun ssh(
        target: SshTarget,
        creds: SshCredentials,
    ): SshSession =
        (SshConnector().connect(target, creds, { _, _, _, _ -> HostKeyDecision.Trusted }) as AppResult.Success).value

    private fun FloodResult.log(): FloodResult = also { Log.i("THROUGHPUT", line()) }

    @Test
    fun protocolOverSsh() =
        runBlocking<Unit> {
            val (target, creds, command) = requireArgs()
            withTimeout(300.seconds) {
                val session = ssh(target, creds)
                val counting = CountingTransport(session.exec(command))
                val conn = CoreConnection.open(counting, scope)
                try {
                    val r =
                        drainFlood(
                            conn,
                            counting,
                            FloodProgram(null, bytes, kind),
                            "device protocol+ssh",
                            scope,
                        ).log()
                    assertTrue(r.programSeconds > 0)
                } finally {
                    conn.close()
                    session.close()
                }
            }
        }

    @Test
    fun sessionAndControllerWithGhostty() =
        runBlocking<Unit> {
            val (target, creds, command) = requireArgs()
            withTimeout(300.seconds) {
                var counting: CountingTransport? = null
                var sshSession: SshSession? = null
                val core =
                    CoreSessionImpl(
                        CoreEndpoint(HostId("tp"), command.last()),
                        {
                            val s = ssh(target, creds).also { sshSession = it }
                            AppResult.Success(
                                CountingTransport(s.exec(command)).also { counting = it } as ByteTransport,
                            )
                        },
                        scope,
                    )
                core.start()
                core.state.first { it.connection is ConnectionState.Online }
                val flood = FloodProgram(null, bytes, kind)
                val record =
                    (
                        core.run(
                            RunRequest(
                                argv = if (shell) emptyList() else flood.argv,
                                cwd = "/tmp",
                                name = "tp-${flood.tag}",
                            ),
                        ) as AppResult.Success
                    ).value
                val stream =
                    (
                        core.attach(
                            record.address,
                            AttachRequest(cols, rows, AttachMode.Owner),
                        ) as AppResult.Success
                    ).value
                val scanner = MarkerScanner()
                val done = CompletableDeferred<Unit>()
                val controller =
                    TerminalController(
                        TerminalEngineFactory { c, r -> GhosttyTerminalEngine(c, r) },
                        TerminalThemes.Dark,
                        initialCols = cols,
                        initialRows = rows,
                    )
                val scenario =
                    if (ui) {
                        ThroughputTestActivity.controller = controller
                        ActivityScenario.launch(ThroughputTestActivity::class.java)
                    } else {
                        null
                    }
                controller.bind(
                    ScanningStream(stream) { if (scanner.feed(it)) done.complete(Unit) },
                    AttachMode.Owner,
                )
                stream.connected.first { it }
                kotlinx.coroutines.delay(500) // first paint
                val c = checkNotNull(counting)
                val credit0 = c.creditFrames.get()
                val creditBytes0 = c.creditBytes.get()
                val fed0 = controller.bytesFed
                val frames0 = controller.publishedFrames
                val t0 = System.nanoTime()
                stream.write(if (shell) (flood.shellLine + "\r").toByteArray() else flood.goInput)
                done.await()
                val seconds = (System.nanoTime() - t0) / 1e9
                FloodResult(
                    "device ${if (ui) "ui+" else ""}session+controller+ghostty ${cols}x$rows${if (shell) " shell" else ""}, $kind",
                    controller.bytesFed - fed0,
                    seconds,
                    scanner.programSeconds ?: -1.0,
                    c.creditFrames.get() - credit0,
                    c.creditBytes.get() - creditBytes0,
                ).log()
                Log.i("THROUGHPUT", "frames published ${controller.publishedFrames - frames0}")
                scenario?.close()
                controller.close()
                stream.detach()
                core.kill(TerminalAddress(record.address.value))
                core.stop()
                sshSession?.close()
            }
        }

    /** Passes the stream through, scanning each chunk before the controller feeds it. */
    private class ScanningStream(
        private val inner: TerminalStream,
        private val onChunk: (ByteArray) -> Unit,
    ) : TerminalStream by inner {
        override val output: Flow<ByteArray> = inner.output.onEach { onChunk(it) }
    }
}

/** Hosts [controller] in a full-screen [TerminalSurface] for DeviceThroughputTest (`tpUi=1`). */
class ThroughputTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = checkNotNull(controller)
        setContent { TerminalSurface(c, TerminalThemes.Dark, Modifier.fillMaxSize()) }
    }

    companion object {
        @Volatile var controller: TerminalController? = null
    }
}
