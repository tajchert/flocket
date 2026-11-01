package com.mtajchert.flocket.core.data.internal

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.HostDraft
import com.mtajchert.flocket.core.model.DeviceKey
import com.mtajchert.flocket.core.model.HostAuth
import com.mtajchert.flocket.core.model.PinnedHostKey
import com.mtajchert.flocket.core.protocol.transport.ByteTransport
import com.mtajchert.flocket.core.security.KeyVault
import com.mtajchert.flocket.core.ssh.CommandResult
import com.mtajchert.flocket.core.ssh.HostKeyDecision
import com.mtajchert.flocket.core.ssh.HostKeyVerifier
import com.mtajchert.flocket.core.ssh.SshCredentials
import com.mtajchert.flocket.core.ssh.SshTarget
import com.mtajchert.flocket.core.testing.FakeHostRepository
import com.mtajchert.flocket.core.testing.FakeHostTrustRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshHostConnectorTest {
    private class Link(
        val hcFound: Boolean = true,
    ) : SshLink {
        var closed = false
        val commands = mutableListOf<List<String>>()
        val transport = T()

        class T : ByteTransport {
            var closedT = false

            override suspend fun read(buffer: ByteArray) = -1

            override suspend fun write(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ) = Unit

            override fun diagnostics() = ""

            override suspend fun close() {
                closedT = true
            }
        }

        override suspend fun exec(command: List<String>): ByteTransport {
            commands += command
            return transport
        }

        override suspend fun run(command: List<String>) =
            if (hcFound) CommandResult("/opt/bin/hc\n", "", 0) else CommandResult("", "nope", 1)

        override suspend fun close() {
            closed = true
        }
    }

    private class Vault : KeyVault {
        var lastPem: CharArray? = null

        override suspend fun generateEd25519(
            alias: String,
            comment: String,
        ): AppResult<DeviceKey> = error("unused")

        override suspend fun import(
            alias: String,
            pem: CharArray,
            passphrase: CharArray?,
            comment: String,
        ): AppResult<DeviceKey> = error("unused")

        override suspend fun list(): AppResult<List<DeviceKey>> = AppResult.Success(emptyList())

        override suspend fun delete(alias: String): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun loadPrivateKeyPem(alias: String): AppResult<CharArray> =
            AppResult.Success("PEM".toCharArray().also { lastPem = it })
    }

    private val hosts = FakeHostRepository()
    private val trust = FakeHostTrustRepository()
    private val vault = Vault()
    private var link: Link = Link()
    private var decision: HostKeyDecision? = null
    private var creds: SshCredentials? = null
    private var passwordSeen: String? = null
    private var target: SshTarget? = null
    private var openError: AppError? = null
    private val cached = mutableMapOf<String, String>()

    private val connector =
        SshHostConnector(
            hosts,
            object : HcPathCache {
                override suspend fun cacheHcPath(
                    id: com.mtajchert.flocket.core.model.HostId,
                    path: String,
                ) {
                    cached[id.value] = path
                }
            },
            trust,
            vault,
            SshOpener { t, c, verifier ->
                target = t
                creds = c
                passwordSeen = (c as? SshCredentials.Password)?.let { String(it.password) }
                // The key exchange asks the verifier before auth.
                decision = verifier.verify(t.hostname, t.port, "ssh-ed25519", "SHA256:seen")
                val err =
                    openError ?: when (val d = decision) {
                        HostKeyDecision.Unknown -> {
                            AppError.HostKeyUnknown(t.hostname, "ssh-ed25519", "SHA256:seen")
                        }

                        is HostKeyDecision.Changed -> {
                            AppError.HostKeyChanged(t.hostname, "ssh-ed25519", d.expectedFingerprint, "SHA256:seen")
                        }

                        else -> {
                            null
                        }
                    }
                if (err != null) AppResult.Failure(err) else AppResult.Success(link)
            },
        )

    private suspend fun addHost(
        auth: HostAuth = HostAuth.Key("k"),
        hcPath: String? = null,
        session: String? = null,
    ) = (hosts.save(HostDraft(null, "H", "h.local", 22, "me", auth, hcPath, session)) as AppResult.Success).value

    private fun pin(fp: String) {
        runBlocking { trust.pin(PinnedHostKey("h.local", 22, "ssh-ed25519", fp, 0)) }
    }

    @Test
    fun `unknown host key maps to HostKeyUnknown when no pin exists`() =
        runBlocking {
            val h = addHost()
            val r = connector.open(h.id)
            assertTrue((r as AppResult.Failure).error is AppError.HostKeyUnknown)
            assertEquals(HostKeyDecision.Unknown, decision)
            assertEquals("PEM".length, vault.lastPem!!.size)
            assertTrue(vault.lastPem!!.all { it == '\u0000' })
        }

    @Test
    fun `different pinned fingerprint is HostKeyChanged`() =
        runBlocking {
            val h = addHost()
            pin("SHA256:old")
            val r = connector.open(h.id)
            assertEquals("SHA256:old", ((r as AppResult.Failure).error as AppError.HostKeyChanged).expectedFingerprint)
        }

    @Test
    fun `trusted host locates hc caches the path and execs connect with the session`() =
        runBlocking {
            val h = addHost(session = "work")
            pin("SHA256:seen")
            val r = connector.open(h.id)
            assertTrue(r is AppResult.Success)
            assertEquals(listOf(listOf("/opt/bin/hc", "connect", "--session", "work")), link.commands)
            assertEquals("/opt/bin/hc", cached[h.id.value])
            assertEquals(false, link.closed)
            (r as AppResult.Success).value.close()
            assertTrue(link.transport.closedT)
            assertTrue(link.closed)
        }

    @Test
    fun `configured hc path is used without caching and missing hc closes the link`() =
        runBlocking {
            pin("SHA256:seen")
            val h = addHost(hcPath = "/custom/hc")
            connector.open(h.id)
            assertEquals("/custom/hc", link.commands.single().first())
            assertTrue(cached.isEmpty())

            link = Link(hcFound = false)
            val h2 =
                (
                    hosts.save(
                        HostDraft(null, "H2", "h.local", 22, "me", HostAuth.Key("k")),
                    ) as AppResult.Success
                ).value
            val r = connector.open(h2.id)
            assertTrue((r as AppResult.Failure).error is AppError.CoreNotFound)
            assertTrue(link.closed)
        }

    @Test
    fun `password hosts need a password then forget a rejected one`() =
        runBlocking {
            pin("SHA256:seen")
            val h = addHost(HostAuth.Password)
            val first = connector.open(h.id)
            assertEquals(listOf("password"), ((first as AppResult.Failure).error as AppError.AuthFailed).methodsTried)
            connector.setPassword(h.id, "pw".toCharArray())
            assertTrue(connector.open(h.id) is AppResult.Success)
            assertEquals("pw", passwordSeen)
            openError = AppError.AuthFailed("me", listOf("password"))
            assertTrue(connector.open(h.id) is AppResult.Failure)
            openError = null
            // forgotten: asks again
            assertTrue((connector.open(h.id) as AppResult.Failure).error is AppError.AuthFailed)
            assertEquals(h.hostname, target?.hostname)
        }
}
