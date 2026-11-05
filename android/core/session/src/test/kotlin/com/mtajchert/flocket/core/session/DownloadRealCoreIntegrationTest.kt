package com.mtajchert.flocket.core.session

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Chunked downloads (`stat` + `dd` over `machine.exec`) against a real `hc` in isolated sessions
 * ([RealCoreHarness]). The session runs on `hc connect` processes, which are not a `DirectFileSource`,
 * so every download is chunked. It runs the host's own `stat` (GNU or BSD, whichever this machine has).
 */
class DownloadRealCoreIntegrationTest {
    private val hc = RealCoreHarness()

    @Before
    fun setUp() = hc.setUp()

    @After
    fun tearDown() = hc.tearDown()

    @Test
    fun `chunked download of 20 MiB from the local machine and from rohan matches the file`() =
        runBlocking {
            withTimeout(120.seconds) {
                hc.addRemoteMachine("rohan")
                val s = hc.newSession()
                s.start()
                s.awaitOnline()
                // A space and a quote in the name: argv, never a shell line.
                val file = File(hc.workDir, "my 'build\".apk").apply { writeBytes(Random.nextBytes(SIZE)) }
                val expected = sha256(file)

                for (machine in listOf(null, "rohan")) {
                    val target = File(hc.workDir, "out-${machine ?: "local"}/app.apk.part")
                    val events = s.download(machine, file.absolutePath, target).toList()
                    val started = events.filterIsInstance<DownloadEvent.Started>().single()
                    assertEquals(DownloadMethod.CHUNKED, started.method)
                    assertEquals(SIZE.toLong(), started.size)
                    assertEquals(file.lastModified() / 1000, started.resume.mtimeSeconds)
                    assertEquals(
                        "$machine: $events",
                        DownloadEvent.Done(SIZE.toLong(), DownloadMethod.CHUNKED),
                        events.last(),
                    )
                    assertEquals("$machine checksum", expected, sha256(target))
                }
                s.stop()
            }
        }

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SIZE = 20 * 1024 * 1024
    }
}
