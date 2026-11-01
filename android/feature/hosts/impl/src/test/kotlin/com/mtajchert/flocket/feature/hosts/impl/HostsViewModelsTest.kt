package com.mtajchert.flocket.feature.hosts.impl

import androidx.lifecycle.SavedStateHandle
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.session.SessionState
import com.mtajchert.flocket.core.testing.FakeConnectionManager
import com.mtajchert.flocket.core.testing.FakeDeviceKeyRepository
import com.mtajchert.flocket.core.testing.FakeHostRepository
import com.mtajchert.flocket.core.testing.FakeHostTrustRepository
import com.mtajchert.flocket.core.testing.MainDispatcherRule
import com.mtajchert.flocket.core.testing.SampleData
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
            val vm = HostsViewModel(manager)
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
            val vm = HostDetailViewModel("pi", manager, hosts, trust)
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
            val vm = HostDetailViewModel("studio", manager, hosts, trust)
            vm.uiState.onEach { }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            vm.requestDelete()
            vm.delete()
            assertTrue(vm.uiState.value.deleted)
            assertTrue(hosts.hosts.value.none { it.id == SampleData.studio.id })
        }
}
