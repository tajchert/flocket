package com.mtajchert.flocket.core.data.internal

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.cash.turbine.test
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.HostDraft
import com.mtajchert.flocket.core.data.ThemePreference
import com.mtajchert.flocket.core.data.UserPreferences
import com.mtajchert.flocket.core.database.FlocketDatabase
import com.mtajchert.flocket.core.datastore.PreferencesDataSource
import com.mtajchert.flocket.core.model.BellMode
import com.mtajchert.flocket.core.model.ClipboardWritePolicy
import com.mtajchert.flocket.core.model.DeviceKey
import com.mtajchert.flocket.core.model.HostAuth
import com.mtajchert.flocket.core.model.PinnedHostKey
import com.mtajchert.flocket.core.model.TerminalColorScheme
import com.mtajchert.flocket.core.model.TerminalPreferences
import com.mtajchert.flocket.core.security.KeyVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RepositoriesTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: FlocketDatabase
    private lateinit var hosts: HostRepositoryImpl
    private lateinit var trust: HostTrustRepositoryImpl
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ids = 0

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FlocketDatabase::class.java)
                .setDriver(AndroidSQLiteDriver())
                .build()
        hosts = HostRepositoryImpl(db.hostDao(), db.pinnedHostKeyDao(), newId = { "id-${ids++}" })
        trust = HostTrustRepositoryImpl(db.pinnedHostKeyDao())
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
    }

    private fun draft(
        label: String = "Mac",
        hostname: String = "mac.local",
        port: Int = 22,
        username: String = "me",
        session: String? = null,
        hcPath: String? = null,
        id: com.mtajchert.flocket.core.model.HostId? = null,
    ) = HostDraft(id, label, hostname, port, username, HostAuth.Key("k1"), hcPath, session)

    private fun invalidField(r: AppResult<*>) = ((r as AppResult.Failure).error as AppError.InvalidInput).field

    @Test
    fun `validation rejects bad input with the offending field`() =
        runBlocking {
            assertEquals("label", invalidField(hosts.save(draft(label = " "))))
            assertEquals("hostname", invalidField(hosts.save(draft(hostname = ""))))
            assertEquals("hostname", invalidField(hosts.save(draft(hostname = "a b"))))
            assertEquals("port", invalidField(hosts.save(draft(port = 0))))
            assertEquals("port", invalidField(hosts.save(draft(port = 65536))))
            assertEquals("username", invalidField(hosts.save(draft(username = ""))))
            assertEquals("session", invalidField(hosts.save(draft(session = "Upper"))))
            assertEquals("session", invalidField(hosts.save(draft(session = "-x"))))
            assertEquals("session", invalidField(hosts.save(draft(session = "a".repeat(33)))))
            assertEquals("session", invalidField(hosts.save(draft(session = "t7"))))
            assertEquals("hcPath", invalidField(hosts.save(draft(hcPath = "hc"))))
            assertTrue(hosts.hosts.first().isEmpty())
            assertTrue(hosts.save(draft(session = "work-1.a_b")) is AppResult.Success)
            assertTrue(hosts.save(draft(session = "t7x")) is AppResult.Success)
        }

    @Test
    fun `save creates then updates in place and the flow keeps order`() =
        runBlocking {
            hosts.hosts.test {
                assertTrue(awaitItem().isEmpty())
                val a = (hosts.save(draft(label = "A")) as AppResult.Success).value
                assertEquals(listOf("A"), awaitItem().map { it.label })
                val b = (hosts.save(draft(label = "B", hostname = "b")) as AppResult.Success).value
                assertEquals(listOf("A", "B"), awaitItem().map { it.label })
                hosts.save(draft(id = a.id, label = "A2", session = " work "))
                val list = awaitItem()
                assertEquals(listOf("A2", "B"), list.map { it.label })
                assertEquals("work", list[0].session)
                assertEquals(b.id, list[1].id)
                cancelAndIgnoreRemainingEvents()
            }
            val gone =
                hosts.save(
                    draft(
                        id =
                            com.mtajchert.flocket.core.model
                                .HostId("nope"),
                    ),
                )
            assertEquals("id", invalidField(gone))
        }

    @Test
    fun `delete removes the host and its pin only when unshared`() =
        runBlocking {
            val a = (hosts.save(draft(label = "A")) as AppResult.Success).value
            val b = (hosts.save(draft(label = "B")) as AppResult.Success).value
            trust.pin(PinnedHostKey("mac.local", 22, "ssh-ed25519", "SHA256:x", 1))
            hosts.delete(a.id)
            assertNotNull(trust.pinned("mac.local", 22))
            hosts.delete(b.id)
            assertNull(trust.pinned("mac.local", 22))
            assertNull(hosts.host(a.id))
        }

    @Test
    fun `hc path cache updates the record`() =
        runBlocking {
            val a = (hosts.save(draft()) as AppResult.Success).value
            hosts.cacheHcPath(a.id, "/opt/hc")
            assertEquals("/opt/hc", hosts.host(a.id)?.hcPath)
        }

    @Test
    fun `pin replace and forget`() =
        runBlocking {
            assertNull(trust.pinned("h", 22))
            trust.pin(PinnedHostKey("h", 22, "ssh-ed25519", "SHA256:a", 1))
            trust.pin(PinnedHostKey("h", 22, "ssh-ed25519", "SHA256:b", 2))
            assertEquals("SHA256:b", trust.pinned("h", 22)?.fingerprint)
            assertNull(trust.pinned("h", 2222))
            assertTrue(trust.pin(PinnedHostKey("h", 22, "ssh-ed25519", "bad", 2)) is AppResult.Failure)
            trust.forget("h", 22)
            assertNull(trust.pinned("h", 22))
        }

    private fun prefs(file: File) =
        PreferencesRepositoryImpl(
            PreferencesDataSource(
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    scope = scope,
                    produceFile = { file },
                ),
            ),
        )

    @Test
    fun `preferences default round trip and unknown theme fallback`() =
        runBlocking {
            val repo = prefs(File(tmp.root, "p.preferences_pb"))
            assertEquals(UserPreferences(), repo.preferences.first())
            val changed =
                UserPreferences(
                    onboardingCompleted = true,
                    theme = ThemePreference.Dark,
                    dynamicColor = false,
                    terminal =
                        TerminalPreferences(
                            fontSizeSp = 18f,
                            colorScheme = TerminalColorScheme.SolarizedDark,
                            showExtraKeys = false,
                            keepScreenOn = false,
                            clipboardWritePolicy = ClipboardWritePolicy.Deny,
                            bell = BellMode.Off,
                            autoConnect = false,
                            defaultWorkingDirectory = "~/code",
                        ),
                )
            assertTrue(repo.update { changed } is AppResult.Success)
            assertEquals(changed, repo.preferences.first())
        }

    @Test
    fun `corrupt preferences file resets to defaults`() =
        runBlocking {
            val f = File(tmp.root, "c.preferences_pb").apply { writeBytes(ByteArray(64) { 0x7f }) }
            assertEquals(UserPreferences(), prefs(f).preferences.first())
        }

    private class MemVault : KeyVault {
        val keys = linkedMapOf<String, DeviceKey>()

        private fun add(
            alias: String,
            comment: String,
        ): AppResult<DeviceKey> {
            if (alias in keys) return AppResult.Failure(AppError.InvalidInput("alias", "already exists"))
            return AppResult
                .Success(DeviceKey(alias, "ssh-ed25519 AAAA $comment", "SHA256:$alias", "ssh-ed25519", 0))
                .also { keys[alias] = it.value }
        }

        override suspend fun generateEd25519(
            alias: String,
            comment: String,
        ) = add(alias, comment)

        override suspend fun import(
            alias: String,
            pem: CharArray,
            passphrase: CharArray?,
            comment: String,
        ) = add(alias, comment)

        override suspend fun list() = AppResult.Success(keys.values.toList())

        override suspend fun delete(alias: String): AppResult<Unit> {
            keys.remove(alias)
            return AppResult.Success(Unit)
        }

        override suspend fun loadPrivateKeyPem(alias: String): AppResult<CharArray> =
            AppResult.Success("pem".toCharArray())
    }

    @Test
    fun `device keys get unique aliases so same labels never overwrite`() =
        runBlocking {
            val repo = DeviceKeyRepositoryImpl(MemVault())
            repo.keys.test {
                assertTrue(awaitItem().isEmpty())
                val a = (repo.generate("Phone") as AppResult.Success).value
                assertEquals(1, awaitItem().size)
                val b = (repo.import("Phone", "x".toCharArray(), null) as AppResult.Success).value
                assertEquals(2, awaitItem().size)
                assertNotEquals(a.alias, b.alias)
                assertTrue(a.alias.startsWith("key-"))
                repo.delete(a.alias)
                assertEquals(listOf(b.alias), awaitItem().map { it.alias })
                assertEquals("label", invalidField(repo.generate(" ")))
                cancelAndIgnoreRemainingEvents()
            }
        }
}
