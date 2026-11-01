package com.mtajchert.flocket.core.database

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FlocketDatabaseTest {
    private lateinit var db: FlocketDatabase

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FlocketDatabase::class.java)
                .setDriver(AndroidSQLiteDriver())
                .build()
    }

    @After
    fun tearDown() = db.close()

    private fun host(
        id: String,
        order: Int,
        created: Long = 0,
    ) = HostEntity(id, "L$id", "h$id", 22, "u", HostEntity.AUTH_KEY, "k", null, null, order, created)

    @Test
    fun `hosts are ordered by sortOrder then createdAt and upsert replaces`() =
        runBlocking {
            val dao = db.hostDao()
            dao.upsert(host("b", 1))
            dao.upsert(host("a", 0, created = 5))
            dao.upsert(host("c", 1, created = -1))
            assertEquals(listOf("a", "c", "b"), dao.observeAll().first().map { it.id })
            dao.upsert(host("a", 0).copy(label = "renamed"))
            assertEquals("renamed", dao.get("a")?.label)
            assertEquals(1, dao.maxSortOrder())
            dao.updateHcPath("a", "/x/hc")
            assertEquals("/x/hc", dao.get("a")?.hcPath)
            dao.delete("a")
            assertNull(dao.get("a"))
        }

    @Test
    fun `pins are keyed by hostname and port`() =
        runBlocking {
            val dao = db.pinnedHostKeyDao()
            dao.upsert(PinnedHostKeyEntity("h", 22, "ssh-ed25519", "SHA256:a", 1))
            dao.upsert(PinnedHostKeyEntity("h", 2222, "ssh-ed25519", "SHA256:b", 1))
            dao.upsert(PinnedHostKeyEntity("h", 22, "ssh-ed25519", "SHA256:c", 2))
            assertEquals("SHA256:c", dao.get("h", 22)?.fingerprint)
            assertEquals(2, dao.observeAll().first().size)
            dao.delete("h", 22)
            assertNull(dao.get("h", 22))
            assertNotNull(dao.get("h", 2222))
        }
}
