package com.mtajchert.flocket.core.datastore

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PreferencesDataSourceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun source(file: File) =
        PreferencesDataSource(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { file },
            ),
        )

    @Test
    fun `defaults then round trip of every field`() =
        runBlocking {
            val file = File(tmp.root, "p.preferences_pb")
            val s = source(file)
            assertEquals(StoredPreferences(), s.preferences.first())
            val changed = StoredPreferences(true, "Dark", false, 17.5f, false, false, terminalListSort = "Grouped")
            s.update { changed }
            assertEquals(changed, s.preferences.first())
        }

    @Test
    fun `corrupt file resets to defaults`() =
        runBlocking {
            val file = File(tmp.root, "bad.preferences_pb").apply { writeBytes(ByteArray(64) { 0x7f }) }
            val s = source(file)
            assertEquals(StoredPreferences(), s.preferences.first())
            s.update { it.copy(theme = "Light") }
            assertEquals("Light", s.preferences.first().theme)
        }
}
