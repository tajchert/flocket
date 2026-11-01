package com.mtajchert.flocket.feature.hosts.impl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.BellMode
import com.mtajchert.flocket.core.model.ClipboardWritePolicy
import com.mtajchert.flocket.core.model.HostSetting
import com.mtajchert.flocket.core.model.TerminalColorScheme
import com.mtajchert.flocket.core.model.TerminalPreferences
import com.mtajchert.flocket.core.testing.FakeHostRepository
import com.mtajchert.flocket.core.testing.FakeHostSettingsRepository
import com.mtajchert.flocket.core.testing.FakePreferencesRepository
import com.mtajchert.flocket.core.testing.MainDispatcherRule
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.core.ui.terminalsettings.TerminalSettingsCallbacks
import com.mtajchert.flocket.feature.hosts.impl.settings.HostSettingsCallbacks
import com.mtajchert.flocket.feature.hosts.impl.settings.HostSettingsScreen
import com.mtajchert.flocket.feature.hosts.impl.settings.HostSettingsViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HostSettingsTest {
    @get:Rule val rule = createComposeRule()

    @get:Rule val main = MainDispatcherRule()

    private val studio = SampleData.studio.id
    private val prefs = FakePreferencesRepository()
    private val settings = FakeHostSettingsRepository(prefs)

    private fun vm(hostId: String = "studio") =
        HostSettingsViewModel(hostId, FakeHostRepository(SampleData.hosts), settings, prefs)

    // ---- ViewModel --------------------------------------------------------------------------------

    @Test
    fun `a new host follows every app default`() =
        runTest {
            val vm = vm()
            vm.uiState.onEach { }.launchIn(kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val s = vm.uiState.value
            assertEquals("Studio Mac", s.hostLabel)
            assertTrue(s.form.overridden!!.isEmpty())
            assertFalse(s.hasOverrides)
            assertEquals(prefs.preferences.value.terminal, s.form.values)
        }

    @Test
    fun `turning Use app default off copies the current default and turning it on clears it`() =
        runTest {
            prefs.update { it.copy(terminal = it.terminal.copy(fontSizeSp = 17f, bell = BellMode.Off)) }
            val vm = vm()
            vm.uiState.onEach { }.launchIn(kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            vm.setUseAppDefault(HostSetting.FontSize, false)
            vm.setUseAppDefault(HostSetting.Bell, false)
            var o = settings.overridesNow.getValue(studio)
            assertEquals(17f, o.fontSizeSp)
            assertEquals(BellMode.Off, o.bell)
            assertEquals(setOf(HostSetting.FontSize, HostSetting.Bell), vm.uiState.value.form.overridden)
            vm.setFontSize(21f)
            assertEquals(21f, vm.uiState.value.form.values.fontSizeSp)
            assertEquals(
                17f,
                vm.uiState.value.form.appDefaults!!
                    .fontSizeSp,
            )
            vm.setUseAppDefault(HostSetting.FontSize, true)
            o = settings.overridesNow.getValue(studio)
            assertNull(o.fontSizeSp)
            assertEquals(17f, vm.uiState.value.form.values.fontSizeSp)
        }

    @Test
    fun `every control writes only its own override`() =
        runTest {
            val vm = vm()
            vm.uiState.onEach { }.launchIn(kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            vm.setColorScheme(TerminalColorScheme.HighContrastDark)
            vm.setShowExtraKeys(false)
            vm.setKeepScreenOn(false)
            vm.setClipboardPolicy(ClipboardWritePolicy.Allow)
            vm.setAutoConnect(false)
            vm.setWorkingDirectory(" ~/code ")
            val t = vm.uiState.value.form.values
            assertEquals(TerminalColorScheme.HighContrastDark, t.colorScheme)
            assertFalse(t.showExtraKeys)
            assertFalse(t.keepScreenOn)
            assertEquals(ClipboardWritePolicy.Allow, t.clipboardWritePolicy)
            assertFalse(t.autoConnect)
            assertEquals("~/code", t.defaultWorkingDirectory)
            assertEquals(13f, t.fontSizeSp)
            assertEquals(
                6,
                vm.uiState.value.form.overridden!!
                    .size,
            )
            // A blank folder is "use the app default", not an empty override.
            vm.setWorkingDirectory("  ")
            assertEquals("~", vm.uiState.value.form.values.defaultWorkingDirectory)
            assertFalse(HostSetting.WorkingDirectory in vm.uiState.value.form.overridden!!)
            // The app-level preferences are never touched by host edits.
            assertEquals(prefs.preferences.value.terminal, TerminalPreferences())
        }

    @Test
    fun `reset all drops every override`() =
        runTest {
            val vm = vm()
            vm.uiState.onEach { }.launchIn(kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            vm.setBell(BellMode.Off)
            vm.setFontSize(30f)
            assertTrue(vm.uiState.value.hasOverrides)
            vm.resetAll()
            assertFalse(vm.uiState.value.hasOverrides)
            assertTrue(settings.overridesNow.isEmpty())
        }

    @Test
    fun `a missing host has no label`() =
        runTest {
            val vm = vm("gone")
            vm.uiState.onEach { }.launchIn(kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            assertNull(vm.uiState.value.hostLabel)
            assertFalse(vm.uiState.value.loading)
        }

    // ---- Screen -----------------------------------------------------------------------------------

    private fun showScreen(vm: HostSettingsViewModel) =
        rule.setContent {
            FlocketTheme(dynamicColor = false) {
                val state by vm.uiState.collectAsState()
                Box(Modifier.requiredSize(600.dp, 3000.dp)) {
                    HostSettingsScreen(
                        state,
                        HostSettingsCallbacks(
                            onResetAll = vm::resetAll,
                            terminal =
                                TerminalSettingsCallbacks(
                                    onFontSize = vm::setFontSize,
                                    onBell = vm::setBell,
                                    onUseAppDefault = vm::setUseAppDefault,
                                ),
                        ),
                    )
                }
            }
        }

    @Test
    fun `use app default toggles the custom control and shows the default value`() {
        prefs.update2 { it.copy(fontSizeSp = 15f) }
        val vm = vm()
        showScreen(vm)
        rule.onNodeWithText("Use app default (15 sp)").assertExists()
        rule.onNodeWithTag("font_slider").assertDoesNotExist()
        rule.onNodeWithTag("reset_all").assertIsNotEnabled()

        rule.onNodeWithTag("use_default_FontSize").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("font_slider").assertExists()
        rule.onNodeWithTag("reset_all").assertIsEnabled()
        assertEquals(15f, settings.overridesNow.getValue(studio).fontSizeSp)

        rule.onNodeWithTag("use_default_FontSize").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("font_slider").assertDoesNotExist()
        assertTrue(settings.overridesNow.isEmpty())
    }

    @Test
    fun `choosing a bell option on a customized setting and reset all`() {
        val vm = vm()
        showScreen(vm)
        rule.onNodeWithTag("use_default_Bell").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("bell_1").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(BellMode.Off, settings.overridesNow.getValue(studio).bell)
        rule.onNodeWithTag("reset_all").performClick()
        rule.waitForIdle()
        assertTrue(settings.overridesNow.isEmpty())
        rule.onNodeWithTag("bell_1").assertDoesNotExist()
    }

    private fun FakePreferencesRepository.update2(transform: (TerminalPreferences) -> TerminalPreferences) {
        kotlinx.coroutines.runBlocking { update { it.copy(terminal = transform(it.terminal)) } }
    }
}
