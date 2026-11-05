package com.mtajchert.flocket.core.ssh

import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.protocol.transport.DirectFileException
import com.mtajchert.flocket.core.protocol.transport.DirectFileReader
import com.mtajchert.flocket.core.protocol.transport.RemoteFileStat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SshSftpTest {
    private lateinit var root: Path
    private val servers = mutableListOf<TestSshServer>()
    private val sessions = mutableListOf<SshSession>()
    private val closes = AtomicInteger()
    private val closed = CountDownLatch(1)
    private var readHook: () -> Unit = {}
    private lateinit var lastServer: TestSshServer

    @Before
    fun setUp() {
        root = Files.createTempDirectory("sftp-test")
    }

    @After
    fun tearDown() {
        runBlocking { sessions.forEach { it.close() } }
        servers.forEach { it.close() }
        root.toFile().deleteRecursively()
    }

    private suspend fun reader(withSftp: Boolean = true): DirectFileReader {
        val s =
            TestSshServer(
                sftpRoot = if (withSftp) root else null,
                onSftpClose = {
                    closes.incrementAndGet()
                    closed.countDown()
                },
                onSftpRead = { readHook() },
            ).also {
                servers += it
                lastServer = it
            }
        val verifier =
            HostKeyVerifier { _, _, type, fp ->
                if (type == s.hostKeyType &&
                    fp == s.hostFingerprint
                ) {
                    HostKeyDecision.Trusted
                } else {
                    HostKeyDecision.Unknown
                }
            }
        val r =
            SshConnector().connect(
                SshTarget("127.0.0.1", s.port, "tester"),
                SshCredentials.Password("pw".toCharArray()),
                verifier,
            )
        val session = (r as AppResult.Success).value.also { sessions += it }
        return session.openFileReader()
    }

    private fun write(
        name: String,
        bytes: ByteArray,
    ): Path = root.resolve(name).also { Files.write(it, bytes) }

    private fun random(size: Int) = ByteArray(size).also { Random(7).nextBytes(it) }

    private suspend fun DirectFileReader.read(
        name: String,
        offset: Long = 0,
    ): Triple<ByteArray, Long, Long> {
        val out = ByteArrayOutputStream()
        var sum = 0L
        val n = copyTo("/$name", offset, out) { sum += it }
        return Triple(out.toByteArray(), n, sum)
    }

    @Test
    fun `stat returns size, mtime and regular for a file`() =
        runBlocking {
            val p = write("a.bin", ByteArray(1234))
            Files.setLastModifiedTime(p, FileTime.fromMillis(1_700_000_000_000L))
            assertEquals(RemoteFileStat(1234, 1_700_000_000, true), reader().stat("/a.bin"))
        }

    @Test
    fun `stat of a missing file is null`() = runBlocking { assertNull(reader().stat("/nope")) }

    @Test
    fun `stat of a directory is not regular`() =
        runBlocking {
            Files.createDirectory(root.resolve("d"))
            assertEquals(false, reader().stat("/d")?.isRegularFile)
        }

    @Test
    fun `copies a whole file`() =
        runBlocking {
            val content = random(5 * 1024 * 1024)
            write("big.bin", content)
            val (bytes, n, sum) = reader().read("big.bin")
            assertArrayEquals(content, bytes)
            assertEquals(content.size.toLong(), n)
            assertEquals(content.size.toLong(), sum)
        }

    @Test
    fun `copies from an offset`() =
        runBlocking {
            val content = random(5 * 1024 * 1024)
            write("big.bin", content)
            val (bytes, n, _) = reader().read("big.bin", 3_000_000)
            assertArrayEquals(content.copyOfRange(3_000_000, content.size), bytes)
            assertEquals((content.size - 3_000_000).toLong(), n)
        }

    @Test
    fun `copies a file whose name has spaces and quotes`() =
        runBlocking {
            val name = "my file 'q\" -x.apk"
            val content = random(10_000)
            write(name, content)
            assertArrayEquals(content, reader().read(name).first)
        }

    @Test
    fun `copy of a missing file throws MISSING`() =
        runBlocking {
            try {
                reader().read("nope")
                fail("expected MISSING")
            } catch (e: DirectFileException) {
                assertEquals(DirectFileException.Reason.MISSING, e.reason)
            }
        }

    @Test
    fun `open fails with UNAVAILABLE when the server has no sftp subsystem`() =
        runBlocking {
            try {
                reader(withSftp = false)
                fail("expected UNAVAILABLE")
            } catch (e: DirectFileException) {
                assertEquals(DirectFileException.Reason.UNAVAILABLE, e.reason)
            }
            // The failed attempt must not leave its session channel open on the shared connection.
            val deadline = System.currentTimeMillis() + 5_000
            while (lastServer.openChannels.get() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(0, lastServer.openChannels.get())
        }

    @Test
    fun `denied file throws DENIED`() =
        runBlocking {
            assumeTrue(System.getProperty("user.name") != "root")
            val p = write("secret.bin", ByteArray(100))
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("---------"))
            try {
                reader().read("secret.bin")
                fail("expected DENIED")
            } catch (e: DirectFileException) {
                assertEquals(DirectFileException.Reason.DENIED, e.reason)
            }
        }

    @Test
    fun `cancel mid-copy returns promptly and closes the remote handle`() =
        runBlocking {
            write("huge.bin", random(50 * 1024 * 1024))
            val r = reader()
            val blocked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val out =
                object : OutputStream() {
                    var seen = 0L

                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) {
                        seen += len
                        if (seen > 1024 * 1024) {
                            blocked.countDown()
                            release.await()
                        }
                    }
                }
            val job = async(Dispatchers.Default, CoroutineStart.DEFAULT) { r.copyTo("/huge.bin", 0, out) {} }
            assertTrue(blocked.await(10, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue("server saw no close", closed.await(5, TimeUnit.SECONDS))
        }

    @Test
    fun `cancel during a slow server read ends with CancellationException, not an IO error`() =
        runBlocking {
            write("slow.bin", random(4 * 1024 * 1024))
            val entered = CountDownLatch(1)
            readHook = {
                entered.countDown()
                Thread.sleep(8_000)
            }
            val r = reader()
            val job = async(Dispatchers.Default) { r.copyTo("/slow.bin", 0, ByteArrayOutputStream()) {} }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val start = System.nanoTime()
            job.cancel()
            try {
                withTimeout(2_000) { job.await() }
                fail("expected cancellation")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // expected
            }
            assertTrue("took too long", System.nanoTime() - start < 2_500_000_000L)
        }

    @Test
    fun `50 MiB copy keeps the heap flat`() =
        runBlocking {
            val size = 50 * 1024 * 1024
            write("heap.bin", random(size))
            val r = reader()
            var count = 0L
            val out =
                object : OutputStream() {
                    override fun write(b: Int) {
                        count++
                    }

                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) {
                        count += len
                    }
                }

            fun used(): Long {
                repeat(3) { System.gc() }
                return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
            }
            val before = used()
            val n = r.copyTo("/heap.bin", 0, out) {}
            val after = used()
            assertEquals(size.toLong(), n)
            assertEquals(size.toLong(), count)
            assertTrue("heap grew by ${(after - before) / 1024} KiB", after - before < 16L * 1024 * 1024)
        }
}
