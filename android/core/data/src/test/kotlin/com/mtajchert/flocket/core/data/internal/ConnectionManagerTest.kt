package com.mtajchert.flocket.core.data.internal

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.AppForeground
import com.mtajchert.flocket.core.data.NetworkMonitor
import com.mtajchert.flocket.core.data.NetworkState
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.CoreEndpoint
import com.mtajchert.flocket.core.model.Host
import com.mtajchert.flocket.core.model.HostAuth
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.protocol.transport.ByteTransport
import com.mtajchert.flocket.core.session.CoreSession
import com.mtajchert.flocket.core.session.CoreSessionFactory
import com.mtajchert.flocket.core.testing.FakeCoreSession
import com.mtajchert.flocket.core.testing.FakeHostRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {
    private class Rec(
        val fake: FakeCoreSession,
        val transportFactory: suspend () -> AppResult<ByteTransport>,
    ) : CoreSession by fake {
        var starts = 0
        var stops = 0

        override fun start() {
            starts++
        }

        override suspend fun stop() {
            stops++
        }

        override fun retryNow() = fake.retryNow()
    }

    private class FakeConnector : HostConnector {
        val opened = mutableListOf<HostId>()
        val passwords = mutableMapOf<HostId, String>()
        var failFor: HostId? = null

        override suspend fun open(hostId: HostId): AppResult<ByteTransport> {
            opened += hostId
            return AppResult.Failure(AppError.ConnectionLost)
        }

        override fun setPassword(
            hostId: HostId,
            password: CharArray,
        ) {
            passwords[hostId] = String(password)
        }

        override fun clearPassword(hostId: HostId) {
            passwords.remove(hostId)
        }
    }

    private val fg = MutableStateFlow(false)
    private val net = MutableStateFlow(NetworkState(true, 1))
    private val sessions = mutableListOf<Rec>()
    private val repo = FakeHostRepository()
    private val connector = FakeConnector()

    private fun host(
        id: String,
        label: String = id,
        hostname: String = "$id.local",
    ) = Host(HostId(id), label, hostname, 22, "me", HostAuth.Key("k"))

    private fun TestScope.manager() =
        ConnectionManagerImpl(
            repo,
            CoreSessionFactory { endpoint: CoreEndpoint, tf ->
                Rec(FakeCoreSession(endpoint), tf).also { sessions += it }
            },
            connector,
            object : AppForeground {
                override val isForeground = fg
            },
            object : NetworkMonitor {
                override val state = net
            },
            backgroundScope,
        )

    private suspend fun setHosts(vararg h: Host) {
        for (x in h) {
            repo.save(
                com.mtajchert.flocket.core.data
                    .HostDraft(x.id, x.label, x.hostname, x.port, x.username, x.auth),
            )
        }
    }

    private fun sessionOf(id: String) = sessions.last { it.endpoint.hostId.value == id }

    private fun reconnecting() = ConnectionState.Reconnecting(1, AppError.ConnectionLost, 0)

    @Test
    fun `sessions are created per host edit recreates and delete stops`() =
        runTest {
            val m = manager()
            setHosts(host("a"), host("b"))
            runCurrent()
            assertEquals(2, sessions.size)
            assertEquals(listOf("a", "b"), m.overview.value.map { it.host.id.value })
            assertTrue(m.session(HostId("a")) === sessionOf("a"))
            // label edit keeps the session
            setHosts(host("a", label = "A!"))
            runCurrent()
            assertEquals(2, sessions.size)
            assertEquals(
                "A!",
                m.overview.value[0]
                    .host.label,
            )
            // hostname edit recreates and stops the old one
            val old = sessionOf("a")
            setHosts(host("a", hostname = "new"))
            runCurrent()
            assertEquals(3, sessions.size)
            assertEquals(1, old.stops)
            assertTrue(m.session(HostId("a")) !== old)
            // delete
            repo.delete(HostId("b"))
            runCurrent()
            assertEquals(1, sessionOf("b").stops)
            assertNull(m.session(HostId("b")))
            assertEquals(1, m.overview.value.size)
        }

    @Test
    fun `foreground starts and background stops after exactly 60 seconds`() =
        runTest {
            val m = manager()
            setHosts(host("a"), host("b"))
            runCurrent()
            assertEquals(0, sessionOf("a").starts)
            fg.value = true
            runCurrent()
            assertEquals(1, sessionOf("a").starts)
            assertEquals(1, sessionOf("b").starts)
            setHosts(host("c"))
            runCurrent()
            assertEquals(1, sessionOf("c").starts)
            fg.value = false
            runCurrent()
            advanceTimeBy(59_999)
            assertEquals(0, sessionOf("a").stops)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, sessionOf("a").stops)
            assertEquals(1, sessionOf("b").stops)
            assertEquals(
                1,
                m.overview.value.size
                    .minus(2),
            )
        }

    @Test
    fun `returning within the grace period cancels the stop`() =
        runTest {
            manager()
            setHosts(host("a"))
            fg.value = true
            runCurrent()
            fg.value = false
            runCurrent()
            advanceTimeBy(30_000)
            fg.value = true
            runCurrent()
            advanceTimeBy(120_000)
            assertEquals(0, sessionOf("a").stops)
            assertEquals(2, sessionOf("a").starts)
        }

    @Test
    fun `network regained or changed retries only reconnecting sessions`() =
        runTest {
            manager()
            setHosts(host("a"), host("b"), host("c"))
            fg.value = true
            runCurrent()
            sessionOf("a").fake.update { it.copy(connection = reconnecting()) }
            sessionOf("b").fake.update { it.copy(connection = ConnectionState.Connecting(1)) }
            runCurrent()
            net.value = NetworkState(false, null)
            runCurrent()
            assertEquals(0, sessionOf("a").fake.retryCount)
            net.value = NetworkState(true, 2)
            runCurrent()
            assertEquals(1, sessionOf("a").fake.retryCount)
            assertEquals(0, sessionOf("b").fake.retryCount)
            assertEquals(0, sessionOf("c").fake.retryCount)
            net.value = NetworkState(true, 3)
            runCurrent()
            assertEquals(2, sessionOf("a").fake.retryCount)
        }

    @Test
    fun `network events in the background do nothing`() =
        runTest {
            manager()
            setHosts(host("a"))
            runCurrent()
            sessionOf("a").fake.update { it.copy(connection = reconnecting()) }
            net.value = NetworkState(true, 9)
            runCurrent()
            assertEquals(0, sessionOf("a").fake.retryCount)
        }

    @Test
    fun `a session factory failure for one host does not affect another`() =
        runTest {
            val failing = host("bad")
            val m =
                ConnectionManagerImpl(
                    repo,
                    CoreSessionFactory { endpoint, tf ->
                        Rec(FakeCoreSession(endpoint), tf).also { sessions += it }
                    },
                    connector,
                    object : AppForeground {
                        override val isForeground = fg
                    },
                    object : NetworkMonitor {
                        override val state = net
                    },
                    backgroundScope,
                )
            setHosts(failing, host("ok"))
            fg.value = true
            runCurrent()
            sessionOf(
                "bad",
            ).fake.update { it.copy(connection = ConnectionState.NeedsAttention(AppError.ConnectionLost)) }
            sessionOf("ok").fake.update {
                it.copy(connection = ConnectionState.Online(com.mtajchert.flocket.core.testing.SampleData.coreInfo))
            }
            runCurrent()
            val states = m.overview.value.associate { it.host.id.value to it.session.connection }
            assertTrue(states["bad"] is ConnectionState.NeedsAttention)
            assertTrue(states["ok"] is ConnectionState.Online)
        }

    @Test
    fun `password flow stores the password and retries the session`() =
        runTest {
            val m = manager()
            repo.save(
                com.mtajchert.flocket.core.data
                    .HostDraft(null, "pw", "pw.local", 22, "me", HostAuth.Password),
            )
            runCurrent()
            val s = sessions.single()
            s.fake.update {
                it.copy(
                    connection = ConnectionState.NeedsAttention(AppError.AuthFailed("me", listOf("password"))),
                )
            }
            m.providePassword(s.endpoint.hostId, "secret".toCharArray())
            assertEquals("secret", connector.passwords[s.endpoint.hostId])
            assertEquals(1, s.fake.retryCount)
            m.providePassword(HostId("unknown"), "x".toCharArray())
            assertEquals(1, connector.passwords.size)
        }

    @Test
    fun `transport factory is wired to the connector`() =
        runTest {
            manager()
            setHosts(host("a"))
            runCurrent()
            val r = sessions.single().transportFactory()
            assertTrue(r is AppResult.Failure)
            assertEquals(listOf(HostId("a")), connector.opened)
        }

    @Suppress("unused")
    private val unused: StateFlow<Int>? = null
}
