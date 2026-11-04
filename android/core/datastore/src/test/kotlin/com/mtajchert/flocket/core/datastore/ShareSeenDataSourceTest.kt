package com.mtajchert.flocket.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShareSeenDataSourceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun source(
        file: File = File(tmp.root, "s.preferences_pb"),
        scope: CoroutineScope = this.scope,
    ) = ShareSeenDataSource(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))

    @Test
    fun `marked ids survive a new instance on the same file`() =
        runBlocking {
            val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            source(scope = first).markSeen(listOf("a", "b"))
            first.cancel() // a closed store, like a process restart
            assertEquals(setOf("a", "b"), source().seen.first())
        }

    @Test
    fun `keeps at most 1000 ids and drops the oldest`() =
        runBlocking {
            val s = source()
            s.markSeen((1..600).map { "id$it" })
            s.markSeen((601..1100).map { "id$it" })
            val seen = s.seen.first()
            assertEquals(1000, seen.size)
            assertFalse("id100" in seen)
            assertTrue("id101" in seen)
            assertTrue("id1100" in seen)
        }

    @Test
    fun `first read hides entries older than ten minutes, once per source`() =
        runBlocking {
            val s = source()
            val now = 10_000L
            val entries = listOf("old" to now - 601, "edge" to now - 600, "new" to now - 5)
            assertTrue(s.firstRead("h|", entries, now))
            assertEquals(setOf("old"), s.seen.first())
            assertFalse(s.firstRead("h|", listOf("later" to now - 9_000), now))
            assertEquals(setOf("old"), s.seen.first())
            assertTrue("another source is its own first read", s.firstRead("h|rohan", listOf("x" to 1L), now))
            assertEquals(setOf("old", "x"), s.seen.first())
        }
}
