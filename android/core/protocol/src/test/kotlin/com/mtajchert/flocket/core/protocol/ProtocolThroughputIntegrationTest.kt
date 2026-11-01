package com.mtajchert.flocket.core.protocol

import com.mtajchert.flocket.core.protocol.testing.CountingTransport
import com.mtajchert.flocket.core.protocol.testing.FloodProgram
import com.mtajchert.flocket.core.protocol.testing.FloodResult
import com.mtajchert.flocket.core.protocol.testing.IsolatedHc
import com.mtajchert.flocket.core.protocol.testing.drainFlood
import com.mtajchert.flocket.core.protocol.testing.headlessFloodSeconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Layer 1 of the throughput bisection (docs/TERMINAL_RENDERING.md, Performance): a flood with no
 * viewer, then the same flood drained as fast as possible through `hc connect` + [CoreConnection].
 * Skipped without `hc`; runs in an isolated session.
 */
class ProtocolThroughputIntegrationTest {
    private lateinit var hc: IsolatedHc
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        assumeTrue("hc not on PATH: skipping throughput test", IsolatedHc.available())
        hc = IsolatedHc()
    }

    @After
    fun tearDown() {
        scope.cancel()
        if (::hc.isInitialized) hc.close()
    }

    @Test
    fun `flood without a viewer and drained through CoreConnection`() =
        runBlocking {
            withTimeout(240.seconds) {
                val counting = CountingTransport(hc.transport())
                val conn = CoreConnection.open(counting, scope)
                try {
                    // Baseline: nobody attached.
                    val headless = headlessFloodSeconds(conn, FloodProgram(hc.workDir))
                    FloodResult("headless (no viewer)", 0, headless, headless).report()
                    val r =
                        drainFlood(
                            conn,
                            counting,
                            FloodProgram(hc.workDir),
                            "protocol (hc connect)",
                            scope,
                        ).report()
                    assertTrue("the program reported its duration", r.programSeconds > 0)
                } finally {
                    conn.close()
                }
            }
        }
}
