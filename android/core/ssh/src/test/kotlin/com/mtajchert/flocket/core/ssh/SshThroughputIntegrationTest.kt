package com.mtajchert.flocket.core.ssh

import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.protocol.CoreConnection
import com.mtajchert.flocket.core.protocol.testing.CountingTransport
import com.mtajchert.flocket.core.protocol.testing.FloodProgram
import com.mtajchert.flocket.core.protocol.testing.FloodResult
import com.mtajchert.flocket.core.protocol.testing.IsolatedHc
import com.mtajchert.flocket.core.protocol.testing.drainFlood
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import kotlin.time.Duration.Companion.seconds

/**
 * Layer 2 of the throughput bisection: the same flood as ProtocolThroughputIntegrationTest, but
 * `hc connect` runs behind an embedded sshd and the client is [SshConnector]/[SshExecTransport].
 * Skipped without `hc`; isolated session. The sshd runs real processes (`sh -c <command>`).
 */
class SshThroughputIntegrationTest {
    private lateinit var hc: IsolatedHc
    private lateinit var sshd: SshServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        assumeTrue("hc not on PATH: skipping throughput test", IsolatedHc.available())
        hc = IsolatedHc()
        sshd =
            SshServer.setUpDefaultServer().apply {
                port = 0
                host = "127.0.0.1"
                keyPairProvider =
                    KeyPairProvider.wrap(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair())
                passwordAuthenticator =
                    org.apache.sshd.server.auth.password
                        .PasswordAuthenticator { _, p, _ -> p == "pw" }
                commandFactory = CommandFactory { _: ChannelSession, cmd: String -> ProcessCommand(cmd, hc.env) }
                start()
            }
    }

    @After
    fun tearDown() {
        scope.cancel()
        if (::sshd.isInitialized) sshd.stop(true)
        if (::hc.isInitialized) hc.close()
    }

    @Test
    fun `flood drained through SSH exec of hc connect (embedded sshd)`() =
        runBlocking {
            val session =
                SshConnector()
                    .connect(
                        SshTarget("127.0.0.1", sshd.port, "tester"),
                        SshCredentials.Password("pw".toCharArray()),
                        { _, _, _, _ -> HostKeyDecision.Trusted },
                    ).let { (it as AppResult.Success).value }
            measure(session, "ssh (sshj+mina)")
        }

    /**
     * Same through a real OpenSSH: `scripts/dev-sshd.sh start`, authorize a test key, then run with
     * `FLOCKET_TP_SSH_PORT=2222 FLOCKET_TP_SSH_KEY=<private key file>`. Skipped otherwise.
     */
    @Test
    fun `flood drained through SSH exec of hc connect (OpenSSH harness)`() =
        runBlocking {
            val port = System.getenv("FLOCKET_TP_SSH_PORT")?.toIntOrNull()
            val key = System.getenv("FLOCKET_TP_SSH_KEY")
            assumeTrue("FLOCKET_TP_SSH_PORT/FLOCKET_TP_SSH_KEY not set", port != null && key != null)
            val session =
                SshConnector()
                    .connect(
                        SshTarget("127.0.0.1", port!!, System.getProperty("user.name")),
                        SshCredentials.PrivateKey(
                            java.io
                                .File(key!!)
                                .readText()
                                .toCharArray(),
                            null,
                        ),
                        { _, _, _, _ -> HostKeyDecision.Trusted },
                    ).let { (it as AppResult.Success).value }
            measure(session, "ssh (sshj+openssh)")
        }

    private suspend fun measure(
        session: SshSession,
        layer: String,
    ) = withTimeout(240.seconds) {
        val counting = CountingTransport(session.exec(hc.connectCommand()))
        val conn = CoreConnection.open(counting, scope)
        try {
            val r = drainFlood(conn, counting, FloodProgram(hc.workDir), layer, scope).report()
            assertTrue("the program reported its duration", r.programSeconds > 0)
        } finally {
            conn.close()
            session.close()
        }
    }

    /** Runs `sh -c <cmd>` like OpenSSH does for exec requests (no PTY), pumping stdio. */
    private class ProcessCommand(
        private val cmd: String,
        private val env: Map<String, String>,
    ) : Command {
        private lateinit var input: InputStream
        private lateinit var out: OutputStream
        private lateinit var err: OutputStream
        private lateinit var exit: ExitCallback
        private var process: Process? = null

        override fun setInputStream(`in`: InputStream) {
            input = `in`
        }

        override fun setOutputStream(out: OutputStream) {
            this.out = out
        }

        override fun setErrorStream(err: OutputStream) {
            this.err = err
        }

        override fun setExitCallback(callback: ExitCallback) {
            exit = callback
        }

        override fun start(
            channel: ChannelSession,
            environment: Environment,
        ) {
            val p = ProcessBuilder("sh", "-c", cmd).apply { environment().putAll(env) }.start()
            process = p
            pump(input, p.outputStream, closeTo = true)
            pump(p.errorStream, err, closeTo = false)
            Thread {
                val buf = ByteArray(32 * 1024)
                runCatching {
                    while (true) {
                        val n = p.inputStream.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        out.flush()
                    }
                }
                exit.onExit(runCatching { p.waitFor() }.getOrDefault(1))
            }.apply { isDaemon = true }.start()
        }

        private fun pump(
            from: InputStream,
            to: OutputStream,
            closeTo: Boolean,
        ) = Thread {
            val buf = ByteArray(32 * 1024)
            runCatching {
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    to.write(buf, 0, n)
                    to.flush()
                }
            }
            if (closeTo) runCatching { to.close() }
        }.apply { isDaemon = true }.start()

        override fun destroy(channel: ChannelSession) {
            process?.destroy()
        }
    }
}
