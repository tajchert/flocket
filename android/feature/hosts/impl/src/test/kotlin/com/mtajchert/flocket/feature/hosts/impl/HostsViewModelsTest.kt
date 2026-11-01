package com.mtajchert.flocket.feature.hosts.impl

import androidx.lifecycle.SavedStateHandle
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.session.SessionState
import com.mtajchert.flocket.core.testing.FakeConnectionManager
import com.mtajchert.flocket.core.testing.FakeDeviceKeyRepository
import com.mtajchert.flocket.core.testing.FakeHostRepository
import com.mtajchert.flocket.core.testing.FakeHostSettingsRepository
import com.mtajchert.flocket.core.testing.FakeHostTrustRepository
import com.mtajchert.flocket.core.testing.MainDispatcherRule
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.core.ui.component.ImportKeyProblem
import com.mtajchert.flocket.core.ui.component.PickedKeyFile
import com.mtajchert.flocket.core.ui.hostform.AuthKind
import com.mtajchert.flocket.core.ui.hostform.HostFormField
import com.mtajchert.flocket.core.ui.hostform.HostFormState
import com.mtajchert.flocket.feature.hosts.impl.detail.HostDetailDialog
import com.mtajchert.flocket.feature.hosts.impl.detail.HostDetailViewModel
import com.mtajchert.flocket.feature.hosts.impl.edit.HostEditViewModel
import com.mtajchert.flocket.feature.hosts.impl.list.HostsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HostsViewModelsTest {
    @get:Rule val main = MainDispatcherRule()

    private val hosts = FakeHostRepository(SampleData.hosts)
    private val manager =
        FakeConnectionManager(hosts, initialState = { h ->
            SampleData.overview.firstOrNull { it.host.id == h.id }?.session
                ?: SessionState(h.endpoint)
        })
    private val trust = FakeHostTrustRepository()

    @Test
    fun `list counts blocked, working and total per host`() =
        runTest {
            val vm = HostsViewModel(manager, FakeHostSettingsRepository())
            val studio =
                vm.uiState
                    .first { it.hosts.isNotEmpty() }
                    .hosts
                    .first()
            assertEquals(2, studio.counts.blocked) // codex permission + remote error
            assertEquals(1, studio.counts.working)
            assertEquals(7, studio.counts.total)
        }

    @Test
    fun `edit form validates on save and does not call the repository`() =
        runTest {
            val vm = HostEditViewModel(null, SavedStateHandle(), hosts, FakeDeviceKeyRepository())
            vm.uiState.onEach { }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            vm.save()
            val state = vm.uiState.value
            assertTrue(HostFormField.Hostname in state.errors)
            assertEquals(false, state.saved)
        }

    @Test
    fun `edit form saves a valid host`() =
        runTest {
            val vm = HostEditViewModel(null, SavedStateHandle(), hosts, FakeDeviceKeyRepository())
            vm.uiState.onEach { }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            vm.onFormChange(HostFormState(label = "New", hostname = "new.local", username = "me", keyAlias = "key-1"))
            vm.save()
            assertTrue(vm.uiState.value.saved)
            assertEquals("new.local", hosts.saved.single().hostname)
        }

    @Test
    fun `importing a key selects it and switches the form to key sign-in`() =
        runTest {
            val keys = FakeDeviceKeyRepository()
            val vm = HostEditViewModel(null, SavedStateHandle(), hosts, keys)
            vm.uiState.onEach { }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            vm.onFormChange(HostFormState(authKind = AuthKind.Password))
            vm.requestImport()
            vm.onImportFilePicked(PickedKeyFile("android_mini_ed25519", PRIVATE_KEY))
            assertEquals(
                "android_mini_ed25519",
                vm.uiState.value.importDialog
                    ?.label,
            )

            vm.importKey()

            val state = vm.uiState.value
            assertNull(state.importDialog)
            assertEquals(AuthKind.Key, state.form.authKind)
            assertEquals(state.keys.single().alias, state.form.keyAlias)
            assertEquals("android_mini_ed25519", state.keys.single().label)
        }

    @Test
    fun `a failed import keeps the dialog open with the error and leaves the selection alone`() =
        runTest {
            val keys = FakeDeviceKeyRepository()
            val vm = HostEditViewModel(null, SavedStateHandle(), hosts, keys)
            vm.uiState.onEach { }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            vm.onFormChange(HostFormState(authKind = AuthKind.Password))
            vm.requestImport()
            vm.onImportFilePicked(PickedKeyFile("id_ed25519", PRIVATE_KEY))
            vm.onImportChange(passphrase = "wrong")
            keys.nextError = AppError.InvalidInput("passphrase", "wrong")

            vm.importKey()

            val state = vm.uiState.value
            val dialog = state.importDialog
            assertNotNull(dialog?.error)
            assertEquals(false, dialog?.busy)
            assertEquals("wrong", dialog?.passphrase)
            assertEquals("id_ed25519", dialog?.fileName)
            assertEquals(AuthKind.Password, state.form.authKind)
            assertNull(state.form.keyAlias)
            assertTrue(state.keys.isEmpty())
        }

    @Test
    fun `importing text that is not a private key is rejected before the repository`() =
        runTest {
            val keys = FakeDeviceKeyRepository()
            val vm = HostEditViewModel(null, SavedStateHandle(), hosts, keys)
            vm.uiState.onEach { }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            vm.requestImport()
            vm.onImportFilePicked(PickedKeyFile("id_ed25519.pub", "ssh-ed25519 AAAA public"))
            vm.importKey()
            assertEquals(
                ImportKeyProblem.NotAPrivateKey,
                vm.uiState.value.importDialog
                    ?.problem,
            )
            assertTrue(
                vm.uiState.value.keys
                    .isEmpty(),
            )
        }

    @Test
    fun `edit form restores from saved state after process death`() =
        runTest {
            val handle = SavedStateHandle(mapOf("form" to HostFormState(label = "Kept")))
            val vm = HostEditViewModel(null, handle, hosts, FakeDeviceKeyRepository())
            vm.uiState.onEach { }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            assertEquals("Kept", vm.uiState.value.form.label)
        }

    @Test
    fun `trust flow pins the key and retries the session`() =
        runTest {
            val vm = HostDetailViewModel("pi", manager, hosts, trust, FakeHostSettingsRepository())
            vm.uiState.onEach { }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val error = vm.uiState.value.error
            assertTrue(error?.source is AppError.HostKeyUnknown)
            vm.onErrorAction(error!!.primaryAction!!)
            assertTrue(vm.uiState.value.dialog is HostDetailDialog.TrustKey)
            vm.trust()
            assertNotNull(trust.pinnedKeys["192.168.1.42" to 22])
            assertEquals(1, manager.fakeSession(SampleData.pi.id)!!.retryCount)
            assertNull(vm.uiState.value.dialog)
            assertTrue(
                manager
                    .fakeSession(SampleData.pi.id)!!
                    .state.value.connection is ConnectionState.NeedsAttention,
            )
        }

    @Test
    fun `delete removes the host and flags the screen to leave`() =
        runTest {
            val vm = HostDetailViewModel("studio", manager, hosts, trust, FakeHostSettingsRepository())
            vm.uiState.onEach { }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            vm.requestDelete()
            vm.delete()
            assertTrue(vm.uiState.value.deleted)
            assertTrue(hosts.hosts.value.none { it.id == SampleData.studio.id })
        }

    @Test
    fun `list marks hosts with overrides as customized`() =
        runTest {
            val settings = FakeHostSettingsRepository()
            settings.update(SampleData.studio.id) { it.copy(bell = com.mtajchert.flocket.core.model.BellMode.Off) }
            val vm = HostsViewModel(manager, settings)
            vm.uiState.onEach { }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val items = vm.uiState.first { it.hosts.isNotEmpty() }.hosts
            assertEquals(listOf(true, false, false), items.map { it.customized })
            settings.resetAll(SampleData.studio.id)
            assertTrue(
                vm.uiState.value.hosts
                    .none { it.customized },
            )
        }

    @Test
    fun `detail counts custom settings and connecting is requested on open and retry`() =
        runTest {
            val settings = FakeHostSettingsRepository()
            settings.update(SampleData.pi.id) { it.copy(fontSizeSp = 18f, autoConnect = false) }
            val vm = HostDetailViewModel("pi", manager, hosts, trust, settings)
            vm.uiState.onEach { }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            assertEquals(2, vm.uiState.first { it.host != null }.customSettings)
            assertEquals(listOf(SampleData.pi.id), manager.connectRequests)
            vm.retry()
            assertEquals(listOf(SampleData.pi.id, SampleData.pi.id), manager.connectRequests)
        }
}

private const val PRIVATE_KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----"
