package com.mtajchert.flocket.core.data.internal

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.mtajchert.flocket.core.datastore.ShareSeenDataSource
import com.mtajchert.flocket.core.model.AgentShare
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.TerminalAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ShareSeenRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 10_000L
    private val file get() = File(tmp.root, "s.preferences_pb")

    private fun share(
        id: String,
        machine: String? = null,
        at: Long,
    ) = AgentShare(id, TerminalAddress("t1"), machine, listOf("/a"), null, at)

    private fun repo(scope: CoroutineScope) =
        ShareSeenRepositoryImpl(
            ShareSeenDataSource(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })),
        )

    @Test
    fun `first read is keyed by host and machine and persists across instances`() =
        runBlocking {
            val shares = listOf(share("old", at = now - 700), share("oldr", "rohan", now - 700))
            val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val r1 = repo(first)
            assertTrue(r1.firstRead(HostId("h"), null, shares, now))
            assertEquals("only the local machine's entries", setOf("old"), r1.seen.first())
            assertTrue("another machine is its own source", r1.firstRead(HostId("h"), "rohan", shares, now))
            assertTrue("another host is its own source", r1.firstRead(HostId("h2"), null, shares, now))
            first.cancel()

            val second = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val r2 = repo(second)
            assertFalse(r2.firstRead(HostId("h"), null, shares, now))
            assertEquals(setOf("old", "oldr"), r2.seen.first())
            second.cancel()
        }

    private class FailingStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("disk") }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("disk")
    }

    @Test
    fun `a failing store never throws and hides nothing`() =
        runBlocking {
            val r = ShareSeenRepositoryImpl(ShareSeenDataSource(FailingStore()))
            assertEquals(emptySet<String>(), r.seen.first())
            r.markSeen(listOf("a"))
            assertFalse(r.firstRead(HostId("h"), null, listOf(share("old", at = 1)), now))
        }
}
