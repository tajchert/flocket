package com.mtajchert.flocket.core.ssh

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.protocol.transport.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.random.Random

class SshConnectorTest {
    private val servers = mutableListOf<TestSshServer>()

    private fun server(authorized: Set<String> = emptySet()) =
        TestSshServer(authorizedFingerprints = authorized).also { servers += it }

    @After
    fun tearDown() = servers.forEach { it.close() }

    private fun target(s: TestSshServer) = SshTarget("127.0.0.1", s.port, "tester")

    private fun trusting(s: TestSshServer) =
        HostKeyVerifier { _, _, type, fp ->
            if (type == s.hostKeyType && fp == s.hostFingerprint) HostKeyDecision.Trusted else HostKeyDecision.Unknown
        }

    private fun password(p: String = "pw") = SshCredentials.Password(p.toCharArray())

    private suspend fun connected(s: TestSshServer): SshSession {
        val r = SshConnector().connect(target(s), password(), trusting(s))
        return (r as AppResult.Success).value
    }

    @Test
    fun `unknown host key aborts before any auth attempt`() =
        runBlocking {
            val s = server()
            val r = SshConnector().connect(target(s), password(), { _, _, _, _ -> HostKeyDecision.Unknown })
            val err = (r as AppResult.Failure).error as AppError.HostKeyUnknown
            assertEquals(s.hostFingerprint, err.fingerprint)
            assertEquals(s.hostKeyType, err.keyType)
            assertTrue(err.fingerprint.startsWith("SHA256:") && !err.fingerprint.endsWith("="))
            assertEquals(0, s.authAttempts.get())
        }

    @Test
    fun `pinned host key connects`() =
        runBlocking {
            val s = server()
            connected(s).close()
            assertEquals(1, s.authAttempts.get())
        }

    @Test
    fun `changed host key is reported with both fingerprints`() =
        runBlocking {
            val s = server()
            val r =
                SshConnector().connect(
                    target(s),
                    password(),
                    { _, _, _, _ -> HostKeyDecision.Changed("SHA256:old") },
                )
            val err = (r as AppResult.Failure).error as AppError.HostKeyChanged
            assertEquals("SHA256:old", err.expectedFingerprint)
            assertEquals(s.hostFingerprint, err.actualFingerprint)
            assertEquals(0, s.authAttempts.get())
        }

    @Test
    fun `wrong password is AuthFailed`() =
        runBlocking {
            val s = server()
            val r = SshConnector().connect(target(s), password("nope"), trusting(s))
            val err = (r as AppResult.Failure).error as AppError.AuthFailed
            assertEquals("tester", err.username)
            assertEquals(listOf("password"), err.methodsTried)
        }

    @Test
    fun `generated ed25519 key authenticates`() =
        runBlocking {
            val key = SshKeys.generateEd25519("test")
            val s = server(setOf(key.fingerprintSha256))
            val r = SshConnector().connect(target(s), SshCredentials.PrivateKey(key.privateKeyPem), trusting(s))
            (r as AppResult.Success).value.close()
        }

    @Test
    fun `unauthorized key is AuthFailed`() =
        runBlocking {
            val key = SshKeys.generateEd25519("test")
            val s = server(emptySet())
            val r = SshConnector().connect(target(s), SshCredentials.PrivateKey(key.privateKeyPem), trusting(s))
            assertTrue((r as AppResult.Failure).error is AppError.AuthFailed)
        }

    @Test
    fun `refused port is Unreachable`() =
        runBlocking {
            val s = server()
            val t = target(s)
            s.close()
            val r = SshConnector().connect(t, password(), { _, _, _, _ -> HostKeyDecision.Trusted })
            assertTrue((r as AppResult.Failure).error is AppError.Unreachable)
        }

    @Test
    fun `exec round trips 1 MiB with fragmented reads and bounded stderr`() =
        runBlocking {
            val s = server()
            val session = connected(s)
            val t = session.exec(listOf("echo"))
            val payload = Random(1).nextBytes(1024 * 1024)
            val reader =
                async(Dispatchers.IO) {
                    val got = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(37)
                    while (got.size() < payload.size) {
                        val n = t.read(buf)
                        if (n < 0) break
                        got.write(buf, 0, n)
                    }
                    got.toByteArray()
                }
            var off = 0
            while (off < payload.size) {
                val n = minOf(10_000, payload.size - off)
                t.write(payload, off, n)
                off += n
            }
            assertArrayEquals(payload, reader.await())
            val d = t.diagnostics()
            assertTrue(d.length <= 8 * 1024)
            assertTrue(d.endsWith("TAIL-MARK"))
            t.close()
            t.close()
            session.close()
        }

    @Test
    fun `banner on stdout precedes data and stdout carries no stderr`() =
        runBlocking {
            val s = server()
            val session = connected(s)
            val t = session.exec(listOf("banner"))
            t.write("data".toByteArray())
            val buf = ByteArray(64)
            val got = StringBuilder()
            while (got.length < 12) {
                val n = t.read(buf)
                if (n < 0) break
                got.append(String(buf, 0, n))
            }
            assertEquals("WELCOME\ndata", got.toString())
            t.close()
            session.close()
        }

    @Test
    fun `exit status 127 surfaces on read with stderr diagnostics`() =
        runBlocking {
            val s = server()
            val session = connected(s)
            val t = session.exec(listOf("missing"))
            try {
                t.read(ByteArray(16))
                fail("expected TransportException")
            } catch (e: TransportException) {
                assertEquals(127, e.exitStatus)
            }
            assertTrue(t.diagnostics().contains("hc: not found"))
            t.close()
            session.close()
        }

    @Test
    fun `run collects stdout stderr and status`() =
        runBlocking {
            val s = server()
            val session = connected(s)
            val r = session.run(listOf("missing"))
            assertEquals(127, r.exitStatus)
            assertTrue(r.stderr.contains("not found"))
            session.close()
        }

    private fun res(name: String) = javaClass.getResource("/keys/$name")!!.readText()

    @Test
    fun `imported and normalized keys of every type authenticate`() =
        runBlocking {
            for ((name, pass) in listOf("ed25519" to null, "ecdsa" to null, "rsa" to null, "ed25519_enc" to "secret")) {
                val k = (SshKeys.import(res(name).toCharArray(), pass?.toCharArray()) as AppResult.Success).value
                val s = server(setOf(k.fingerprintSha256))
                val r = SshConnector().connect(target(s), SshCredentials.PrivateKey(k.privateKeyPem), trusting(s))
                assertTrue("$name: $r", r is AppResult.Success)
                (r as AppResult.Success).value.close()
            }
        }

    @Test
    fun `verifier that throws rejects the key before any auth attempt`() =
        runBlocking {
            val s = server()
            val r = SshConnector().connect(target(s), password(), { _, _, _, _ -> error("pin store down") })
            assertTrue((r as AppResult.Failure).error is AppError.Unexpected)
            assertEquals(0, s.authAttempts.get())
        }

    @Test
    fun `password array is not wiped by connect`() =
        runBlocking {
            val s = server()
            val creds = password()
            (SshConnector().connect(target(s), creds, trusting(s)) as AppResult.Success).value.close()
            assertEquals("pw", String(creds.password))
        }

    @Test
    fun `cancelling connect to a silent server returns promptly`() =
        runBlocking {
            ServerSocket(0).use { silent ->
                thread(isDaemon = true) { runCatching { silent.accept() } }
                val job =
                    launch(Dispatchers.IO) {
                        SshConnector().connect(
                            SshTarget("127.0.0.1", silent.localPort, "tester"),
                            password(),
                            { _, _, _, _ -> HostKeyDecision.Trusted },
                        )
                    }
                delay(300)
                withTimeout(5_000) { job.cancelAndJoin() }
            }
        }

    @Test
    fun `cancelling a blocked read is prompt and keeps the transport usable`() =
        runBlocking {
            val s = server()
            val session = connected(s)
            val t = session.exec(listOf("echo"))
            val started = CompletableDeferred<Unit>()
            val blocked =
                launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    started.complete(Unit)
                    t.read(ByteArray(16))
                }
            started.await()
            delay(200)
            withTimeout(2_000) { blocked.cancelAndJoin() }
            t.write("ping".toByteArray())
            val buf = ByteArray(16)
            val got = StringBuilder()
            withTimeout(5_000) {
                while (got.length < 4) {
                    val n = t.read(buf)
                    if (n < 0) break
                    got.append(String(buf, 0, n))
                }
            }
            assertEquals("ping", got.toString())
            t.close()
            session.close()
        }

    @Test
    fun `close makes a pending read return end of stream`() =
        runBlocking {
            val s = server()
            val session = connected(s)
            val t = session.exec(listOf("echo"))
            val pending = async(Dispatchers.IO) { t.read(ByteArray(16)) }
            delay(200)
            t.close()
            assertEquals(-1, withTimeout(5_000) { pending.await() })
            assertEquals(-1, t.read(ByteArray(16)))
            session.close()
        }

    @Test
    fun `session close from a cancelled coroutine still disconnects`() =
        runBlocking {
            val s = server()
            val session = connected(s)
            val job =
                launch {
                    try {
                        delay(Long.MAX_VALUE)
                    } finally {
                        session.close()
                    }
                }
            delay(50)
            job.cancelAndJoin()
            assertFalse(session.isConnected)
        }
}
