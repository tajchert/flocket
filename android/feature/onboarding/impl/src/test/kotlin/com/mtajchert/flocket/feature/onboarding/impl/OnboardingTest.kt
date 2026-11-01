package com.mtajchert.flocket.feature.onboarding.impl

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.mtajchert.flocket.core.data.UserPreferences
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.session.SessionState
import com.mtajchert.flocket.core.testing.FakeConnectionManager
import com.mtajchert.flocket.core.testing.FakeDeviceKeyRepository
import com.mtajchert.flocket.core.testing.FakeHostRepository
import com.mtajchert.flocket.core.testing.FakeHostTrustRepository
import com.mtajchert.flocket.core.testing.FakePreferencesRepository
import com.mtajchert.flocket.core.testing.MainDispatcherRule
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.core.ui.component.KeySource
import com.mtajchert.flocket.core.ui.component.PickedKeyFile
import com.mtajchert.flocket.core.ui.hostform.AuthKind
import com.mtajchert.flocket.core.ui.hostform.HostFormState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingTest {
    @get:Rule(order = 0)
    val main = MainDispatcherRule()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val keys = FakeDeviceKeyRepository()
    private val hosts = FakeHostRepository()
    private val trust = FakeHostTrustRepository()
    private val prefs = FakePreferencesRepository(UserPreferences(onboardingCompleted = false))
    private val manager = FakeConnectionManager(hosts)

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) =
        OnboardingViewModel(handle, keys, hosts, trust, manager, prefs)

    private val validForm = HostFormState(label = "Mac", hostname = "mac.local", username = "me")

    @Test
    fun `steps advance and key generation selects the key`() =
        runTest {
            val vm = viewModel()
            vm.uiState.onEach { }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            vm.next()
            vm.next()
            assertEquals(OnboardingStep.DeviceKey, vm.uiState.value.step)
            vm.next() // no key yet: stays
            assertEquals(OnboardingStep.DeviceKey, vm.uiState.value.step)
            vm.generateKey()
            assertNotNull(vm.uiState.value.selectedKey)
            vm.next()
            assertEquals(OnboardingStep.AddHost, vm.uiState.value.step)
            vm.back()
            assertEquals(OnboardingStep.DeviceKey, vm.uiState.value.step)
        }

    @Test
    fun `key import picks a file by default and imports it`() =
        runTest {
            val vm = viewModel()
            vm.uiState.onEach { }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            vm.toggleImport()
            assertEquals(KeySource.File, vm.uiState.value.importSource)

            vm.onImportFilePicked(PickedKeyFile("id_ed25519.pub", "ssh-ed25519 AAAA public"))
            assertTrue(vm.uiState.value.importNotAKey)
            vm.importKey() // a public key is never sent to the repository
            assertNull(vm.uiState.value.selectedKey)

            vm.onImportFilePicked(
                PickedKeyFile(
                    "id_ed25519",
                    "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----",
                ),
            )
            assertEquals("id_ed25519", vm.uiState.value.importFileName)
            assertFalse(vm.uiState.value.importNotAKey)
            vm.importKey()
            assertNotNull(vm.uiState.value.selectedKey)
            assertEquals("", vm.uiState.value.importPem)
            assertNull(vm.uiState.value.importFileName)
            assertFalse(vm.uiState.value.importOpen)
        }

    @Test
    fun `switching key import to paste drops the picked file`() =
        runTest {
            val vm = viewModel()
            vm.uiState.onEach { }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            vm.onImportFilePicked(PickedKeyFile("id_rsa", "-----BEGIN RSA PRIVATE KEY-----"))
            assertTrue(vm.uiState.value.importOpen)
            vm.onImportSource(KeySource.Paste)
            assertEquals(KeySource.Paste, vm.uiState.value.importSource)
            assertEquals("", vm.uiState.value.importPem)
            assertNull(vm.uiState.value.importFileName)

            vm.onImportFileFailed()
            assertNotNull(vm.uiState.value.error)
        }

    @Test
    fun `progress survives process death`() =
        runTest {
            val handle = SavedStateHandle()
            val first = viewModel(handle)
            first.next()
            first.next()
            val second = viewModel(handle)
            second.uiState.onEach { }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            assertEquals(OnboardingStep.DeviceKey, second.uiState.value.step)
        }

    @Test
    fun `trust flow pins the key, connects and completes onboarding`() =
        runTest {
            val vm = viewModel()
            vm.uiState.onEach { }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            vm.usePassword()
            assertEquals(AuthKind.Password, vm.uiState.value.form.authKind)
            vm.onFormChange(validForm.copy(authKind = AuthKind.Password))
            vm.next()
            assertEquals(OnboardingStep.Connect, vm.uiState.value.step)
            val host = hosts.hosts.value.single()
            val session = manager.fakeSession(host.id)!!
            session.set(
                SessionState(
                    host.endpoint,
                    ConnectionState.NeedsAttention(SampleData.hostKeyUnknown.copy(host = host.hostname)),
                ),
            )
            assertNotNull(vm.uiState.value.connect.trustPrompt)
            vm.trustHostKey()
            assertNotNull(trust.pinnedKeys[host.hostname to host.port])
            assertEquals(1, session.retryCount)
            session.set(SessionState(host.endpoint, ConnectionState.Online(SampleData.coreInfo), SampleData.coreInfo))
            assertTrue(vm.uiState.value.connect.connected)
            testScheduler.advanceTimeBy(2_000)
            testScheduler.runCurrent()
            assertTrue(prefs.preferences.first().onboardingCompleted)
            assertTrue(vm.uiState.value.finished)
        }

    @Test
    fun `cancelling the trust dialog hides it but keeps the error with its action`() =
        runTest {
            val vm = viewModel()
            vm.uiState.onEach { }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            vm.usePassword()
            vm.onFormChange(validForm.copy(authKind = AuthKind.Password))
            vm.next()
            val host = hosts.hosts.value.single()
            manager
                .fakeSession(
                    host.id,
                )!!
                .set(SessionState(host.endpoint, ConnectionState.NeedsAttention(SampleData.hostKeyUnknown)))
            vm.dismissTrust()
            assertEquals(null, vm.uiState.value.connect.trustPrompt)
            assertNotNull(vm.uiState.value.connect.error)
        }

    @Test
    fun `happy path through the UI reaches the host form`() {
        val vm = viewModel()
        rule.setContent {
            FlocketTheme {
                val state by vm.uiState.collectAsState()
                OnboardingScreen(
                    state,
                    OnboardingActions(onNext = vm::next, onBack = vm::back, onGenerateKey = vm::generateKey),
                    nowMs = SampleData.NOW_MS,
                )
            }
        }
        rule.onNodeWithText("Your herd of terminals, in your pocket").assertIsDisplayed()
        rule.onNodeWithText("Get started").performClick()
        rule.onNodeWithText("What you’ll need").assertIsDisplayed()
        rule.onNodeWithText("I have one").performClick()
        rule.onNodeWithText("Generate key").performClick()
        rule.onNodeWithText("Your device key").assertIsDisplayed()
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("Add your machine").assertIsDisplayed()
    }
}
