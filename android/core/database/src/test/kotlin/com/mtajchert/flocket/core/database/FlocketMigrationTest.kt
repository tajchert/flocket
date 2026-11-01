package com.mtajchert.flocket.core.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** v1 -> v2 (AutoMigration) must keep hosts and pinned keys and add an empty `host_settings` table. */
@RunWith(RobolectricTestRunner::class)
class FlocketMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("migration-test.db"),
            AndroidSQLiteDriver(),
            FlocketDatabase::class,
            { FlocketDatabase_Impl() },
        )

    private fun SQLiteConnection.count(table: String): Long =
        prepare("SELECT COUNT(*) FROM $table").use { st ->
            st.step()
            st.getLong(0)
        }

    @Test
    fun `v1 to v2 keeps hosts and pins and adds an empty settings table`() =
        runBlocking {
            helper.createDatabase(1).use { db ->
                db.execSQL(
                    "INSERT INTO hosts (id,label,hostname,port,username,authType,keyAlias,hcPath," +
                        "session,sortOrder,createdAt) " +
                        "VALUES ('h1','Studio','studio.local',22,'me','key','k1','/x/hc','s',0,5)",
                )
                db.execSQL("INSERT INTO pinned_host_keys VALUES ('studio.local',22,'ssh-ed25519','SHA256:abc',9)")
            }
            helper.runMigrationsAndValidate(2, emptyList()).use { db ->
                assertEquals(1L, db.count("hosts"))
                assertEquals(1L, db.count("pinned_host_keys"))
                assertEquals(0L, db.count("host_settings"))
                db.prepare("SELECT label, hcPath, session FROM hosts WHERE id='h1'").use { st ->
                    st.step()
                    assertEquals("Studio", st.getText(0))
                    assertEquals("/x/hc", st.getText(1))
                    assertEquals("s", st.getText(2))
                }
                db.prepare("SELECT fingerprint FROM pinned_host_keys").use { st ->
                    st.step()
                    assertEquals("SHA256:abc", st.getText(0))
                }
                // The new table works and cascades with its host.
                db.execSQL("PRAGMA foreign_keys=ON")
                db.execSQL("INSERT INTO host_settings (hostId, fontSizeSp) VALUES ('h1', 16.0)")
                assertEquals(1L, db.count("host_settings"))
                db.execSQL("DELETE FROM hosts WHERE id='h1'")
                assertEquals(0L, db.count("host_settings"))
            }
        }
}
