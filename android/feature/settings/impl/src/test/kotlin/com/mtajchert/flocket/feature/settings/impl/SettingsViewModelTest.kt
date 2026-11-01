@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.settings.impl

import app.cash.turbine.test
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.data.ThemePreference
import com.mtajchert.flocket.core.testing.FakeDeviceKeyRepository
import com.mtajchert.flocket.core.testing.FakeHostRepository
import com.mtajchert.flocket.core.testing.FakePreferencesRepository
import com.mtajchert.flocket.core.testing.MainDispatcherRule
import com.mtajchert.flocket.core.testing.SampleData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val prefs = FakePreferencesRepository()
    private val keys = FakeDeviceKeyRepository(listOf(SampleData.deviceKey))
    private val hosts = FakeHostRepository(SampleData.hosts)

    private fun TestScope.vm(): SettingsViewModel {
        val vm = SettingsViewModel(prefs, keys, hosts)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun `preference changes are persisted`() =
        runTest {
            val vm = vm()
            vm.setTheme(ThemePreference.Dark)
            vm.setDynamicColor(false)
            vm.setShowExtraKeys(false)
            vm.setKeepScreenOn(false)
            vm.setKeepConnected(false)
            vm.setTerminalFontSize(40f)
            val p = vm.uiState.value.preferences
            assertEquals(ThemePreference.Dark, p.theme)
            assertEquals(false, p.dynamicColor)
            assertEquals(false, p.showExtraKeys)
            assertEquals(false, p.keepScreenOnInTerminal)
            assertEquals(false, p.keepConnectedInBackground)
            assertEquals(SettingsViewModel.MAX_FONT_SP, p.terminalFontSize)
        }

    @Test
    fun `keys list which hosts use each key`() =
        runTest {
            val item =
                vm()
                    .uiState.value.keys
                    .single()
            assertEquals(listOf("Studio Mac", "Hetzner build box"), item.usedBy)
        }

    @Test
    fun `generate needs a name then adds the key`() =
        runTest {
            val vm = vm()
            vm.requestGenerate()
            vm.onGenerateLabel("  ")
            vm.generate()
            assertTrue((vm.dialog.value as SettingsDialog.Generate).labelMissing)
            vm.events.test {
                vm.onGenerateLabel("phone")
                vm.generate()
                assertTrue(awaitItem() is SettingsEvent.Info)
            }
            assertNull(vm.dialog.value)
            assertEquals(2, vm.uiState.value.keys.size)
        }

    @Test
    fun `import rejects text that is not a private key and reports repository errors inline`() =
        runTest {
            val vm = vm()
            vm.requestImport()
            vm.onImportChange(label = "laptop", pem = "ssh-ed25519 AAAA public")
            vm.import()
            assertEquals(ImportProblem.NotAPrivateKey, (vm.dialog.value as SettingsDialog.Import).problem)

            vm.onImportChange(pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----")
            keys.nextError = AppError.InvalidInput("passphrase", "wrong")
            vm.import()
            assertNotNull((vm.dialog.value as SettingsDialog.Import).error)
            assertEquals(1, vm.uiState.value.keys.size)

            vm.import()
            assertNull(vm.dialog.value)
            assertEquals(2, vm.uiState.value.keys.size)
        }

    @Test
    fun `delete asks first`() =
        runTest {
            val vm = vm()
            vm.requestDelete(
                vm.uiState.value.keys
                    .single(),
            )
            assertEquals(1, vm.uiState.value.keys.size)
            vm.events.test {
                vm.confirmDelete()
                assertTrue(awaitItem() is SettingsEvent.Info)
            }
            assertEquals(0, vm.uiState.value.keys.size)
        }

    @Test
    fun `bundled notices match THIRD_PARTY_NOTICES and parse into rows`() {
        val root = File("../../../THIRD_PARTY_NOTICES.md")
        val bundled = File("src/main/res/raw/third_party_notices.md")
        assertEquals(
            "Run: cp android/THIRD_PARTY_NOTICES.md <settings>/res/raw/third_party_notices.md",
            root.readText(),
            bundled.readText(),
        )
        val notices = parseNotices(bundled.readText())
        assertTrue(notices.rows.size >= 10)
        assertTrue(notices.rows.any { it.library.startsWith("SSHJ") && it.license == "Apache-2.0" })
        assertTrue(notices.rows.none { '[' in it.library || '`' in it.use })
        assertTrue(notices.footnotes.isNotEmpty())
    }
}
