package com.mtajchert.flocket.core.data.internal

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.mtajchert.flocket.core.data.AutoConnectSettings
import com.mtajchert.flocket.core.data.HostDraft
import com.mtajchert.flocket.core.database.FlocketDatabase
import com.mtajchert.flocket.core.datastore.PreferencesDataSource
import com.mtajchert.flocket.core.model.BellMode
import com.mtajchert.flocket.core.model.ClipboardWritePolicy
import com.mtajchert.flocket.core.model.HostAuth
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.HostSetting
import com.mtajchert.flocket.core.model.HostSettingsOverrides
import com.mtajchert.flocket.core.model.TerminalColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class HostSettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: FlocketDatabase
    private lateinit var hosts: HostRepositoryImpl
    private lateinit var prefs: PreferencesRepositoryImpl
    private lateinit var repo: HostSettingsRepositoryImpl
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
        prefs =
            PreferencesRepositoryImpl(
                PreferencesDataSource(
                    PreferenceDataStoreFactory.create(
                        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                        scope = scope,
                        produceFile = { File(tmp.root, "p.preferences_pb") },
                    ),
                ),
            )
        repo = HostSettingsRepositoryImpl(db.hostSettingsDao(), prefs)
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
    }

    private suspend fun newHost(label: String = "H"): HostId =
        (
            hosts.save(
                HostDraft(null, label, "$label.local", 22, "me", HostAuth.Password),
            ) as com.mtajchert.flocket.core.common.result.AppResult.Success
        ).value.id

    @Test
    fun `a host without a row uses the app defaults`() =
        runBlocking {
            val id = newHost()
            assertTrue(repo.overrides(id).first().isEmpty)
            val eff = repo.effective(id).first()
            assertEquals(prefs.preferences.first().terminal, eff.terminal)
            assertTrue(eff.overridden.isEmpty())
            assertTrue(repo.customizedHosts.first().isEmpty())
        }

    @Test
    fun `overrides round trip and win over later app changes`() =
        runBlocking {
            val id = newHost()
            val o =
                HostSettingsOverrides(
                    fontSizeSp = 17f,
                    colorScheme = TerminalColorScheme.HighContrastDark,
                    showExtraKeys = false,
                    keepScreenOn = false,
                    clipboardWritePolicy = ClipboardWritePolicy.Allow,
                    bell = BellMode.Off,
                    autoConnect = false,
                    defaultWorkingDirectory = "~/code",
                )
            repo.update(id) { o }
            assertEquals(o, repo.overrides(id).first())
            assertEquals(setOf(id), repo.customizedHosts.first())
            prefs.update { it.copy(terminal = it.terminal.copy(fontSizeSp = 9f, bell = BellMode.Haptic)) }
            val eff = repo.effective(id).first()
            assertEquals(17f, eff.terminal.fontSizeSp)
            assertEquals(BellMode.Off, eff.terminal.bell)
            assertEquals(HostSetting.entries.toSet(), eff.overridden)
        }

    @Test
    fun `clearing the last override removes the row and reset all clears everything`() =
        runBlocking {
            val id = newHost()
            repo.update(id) { it.copy(fontSizeSp = 20f, bell = BellMode.Off) }
            repo.reset(id, HostSetting.FontSize)
            assertEquals(setOf(HostSetting.Bell), repo.overrides(id).first().overridden)
            repo.reset(id, HostSetting.Bell)
            assertNull(db.hostSettingsDao().get(id.value))
            repo.update(id) { it.copy(autoConnect = false) }
            repo.resetAll(id)
            assertNull(db.hostSettingsDao().get(id.value))
            assertTrue(repo.customizedHosts.first().isEmpty())
        }

    @Test
    fun `pinch writes the host override when there is one else the app default`() =
        runBlocking {
            val a = newHost("A")
            val b = newHost("B")
            repo.update(a) { it.copy(fontSizeSp = 14f) }
            repo.setFontSize(a, 19f)
            assertEquals(19f, repo.overrides(a).first().fontSizeSp)
            assertEquals(
                13f,
                prefs.preferences
                    .first()
                    .terminal.fontSizeSp,
            )
            repo.setFontSize(b, 21f)
            assertNull(repo.overrides(b).first().fontSizeSp)
            assertEquals(
                21f,
                prefs.preferences
                    .first()
                    .terminal.fontSizeSp,
            )
            // A host with other overrides but no font override still follows the app default.
            repo.update(b) { it.copy(bell = BellMode.Off) }
            repo.setFontSize(b, 22f)
            assertEquals(
                22f,
                prefs.preferences
                    .first()
                    .terminal.fontSizeSp,
            )
            assertNull(repo.overrides(b).first().fontSizeSp)
        }

    @Test
    fun `deleting a host drops its overrides and re-saving a host keeps them`() =
        runBlocking {
            val id = newHost("A")
            repo.update(id) { it.copy(bell = BellMode.Off) }
            hosts.save(HostDraft(id, "Renamed", "A.local", 22, "me", HostAuth.Password))
            assertEquals(BellMode.Off, repo.overrides(id).first().bell)
            hosts.delete(id)
            assertNull(db.hostSettingsDao().get(id.value))
        }

    @Test
    fun `auto connect combines the app default and host overrides`() =
        runBlocking {
            val a = newHost("A")
            val b = newHost("B")
            repo.update(a) { it.copy(autoConnect = false) }
            repo.update(b) { it.copy(bell = BellMode.Off) }
            val s = repo.autoConnect.first()
            assertEquals(AutoConnectSettings(true, mapOf(a to false)), s)
            assertTrue(s.isEnabled(b))
            assertEquals(false, s.isEnabled(a))
            prefs.update { it.copy(terminal = it.terminal.copy(autoConnect = false)) }
            val t = repo.autoConnect.first()
            assertEquals(false, t.isEnabled(b))
        }
}
