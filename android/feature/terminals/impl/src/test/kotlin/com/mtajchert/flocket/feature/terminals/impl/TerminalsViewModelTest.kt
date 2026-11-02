@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.terminals.impl

import app.cash.turbine.test
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.UserPreferences
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.CoreInfo
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.LayoutRef
import com.mtajchert.flocket.core.model.MachineState
import com.mtajchert.flocket.core.model.TerminalSort
import com.mtajchert.flocket.core.session.SendReceipt
import com.mtajchert.flocket.core.session.SessionState
import com.mtajchert.flocket.core.testing.FakeConnectionManager
import com.mtajchert.flocket.core.testing.FakeCoreSession
import com.mtajchert.flocket.core.testing.FakeHostRepository
import com.mtajchert.flocket.core.testing.FakeHostSettingsRepository
import com.mtajchert.flocket.core.testing.FakePreferencesRepository
import com.mtajchert.flocket.core.testing.MainDispatcherRule
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.core.ui.error.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.mtajchert.flocket.core.ui.R as UiR

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val studio = HostId("studio")
    private val hetzner = HostId("hetzner")
    private val pi = HostId("pi")

    private fun manager(withTemplates: Boolean = false): FakeConnectionManager {
        val repo = FakeHostRepository(SampleData.hosts)
        return FakeConnectionManager(repo, initialState = { h ->
            val state = SampleData.overview.first { it.host.id == h.id }.session
            if (withTemplates && h.id == studio) {
                val core = SampleData.coreInfo.copy(methods = SampleData.coreInfo.methods + CoreInfo.TEMPLATES_LIST)
                state.copy(
                    core = core,
                    connection =
                        com.mtajchert.flocket.core.model.ConnectionState
                            .Online(core),
                )
            } else {
                state
            }
        })
    }

    private fun TestScope.vm(
        manager: FakeConnectionManager = manager(),
        settings: FakeHostSettingsRepository = FakeHostSettingsRepository(),
        lastCwd: LastCwdStore = InMemoryLastCwdStore(),
        prefs: FakePreferencesRepository = FakePreferencesRepository(),
    ): TerminalsViewModel {
        val vm = TerminalsViewModel(manager, lastCwd, settings, prefs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private fun TerminalsUiState.group(id: HostId) = groups.first { it.hostId == id }

    private fun TerminalsUiState.addresses(
        id: HostId,
        machine: String? = null,
    ) = group(id)
        .sections
        .firstOrNull { it.machine == machine }
        ?.items
        ?.map { it.address }
        .orEmpty()

    @Test
    fun `grouped mode groups by host then by federated machine and sorts rows by desktop rank`() =
        runTest {
            val state = vm(prefs = grouped()).uiState.value
            assertEquals(listOf(studio, hetzner, pi), state.groups.map { it.hostId })
            assertEquals(listOf("t2", "t3", "t1", "t4", "t5"), state.addresses(studio)) // blocked, done, working, idle, no status
            assertEquals(listOf("rohan/t7"), state.addresses(studio, "rohan"))
            assertEquals(
                MachineState.Connected,
                state
                    .group(studio)
                    .sections
                    .last()
                    .machineState,
            )
            assertEquals(1, state.hiddenFinished)
        }

    private fun grouped() = FakePreferencesRepository(UserPreferences(terminalSort = TerminalSort.Grouped))

    @Test
    fun `sort defaults to inbox and follows the stored preference`() =
        runTest {
            val prefs = FakePreferencesRepository()
            val vm = vm(prefs = prefs)
            assertEquals(TerminalSort.Inbox, vm.uiState.value.sort)
            // priority sections: needs you, done, working, idle, no status
            assertEquals(listOf("t2", "t3", "t1", "t4", "t5"), vm.uiState.value.addresses(studio))
            prefs.update { it.copy(terminalSort = TerminalSort.Grouped) }
            assertEquals(TerminalSort.Grouped, vm.uiState.value.sort)
            assertTrue(
                vm.uiState.value
                    .group(studio)
                    .sections
                    .first()
                    .priority
                    .isEmpty(),
            )
        }

    @Test
    fun `setSort persists the choice`() =
        runTest {
            val prefs = FakePreferencesRepository()
            val vm = vm(prefs = prefs)
            vm.setSort(TerminalSort.Grouped)
            assertEquals(TerminalSort.Grouped, prefs.preferences.value.terminalSort)
            assertEquals(TerminalSort.Grouped, vm.uiState.value.sort)
            vm.setSort(TerminalSort.Priority)
            assertEquals(TerminalSort.Priority, prefs.preferences.value.terminalSort)
        }

    @Test
    fun `filters keep hosts that need the user and finished terminals are opt in`() =
        runTest {
            val vm = vm()
            vm.setFilter(TerminalFilter.NeedsAttention)
            var state = vm.uiState.value
            assertEquals(listOf("t2"), state.addresses(studio))
            assertEquals(listOf("rohan/t7"), state.addresses(studio, "rohan"))
            assertNotNull(state.group(pi)) // NeedsAttention host stays so its action is reachable
            vm.setFilter(TerminalFilter.Working)
            state = vm.uiState.value
            assertEquals(listOf("t1"), state.addresses(studio))
            vm.setFilter(TerminalFilter.All)
            assertFalse("t6" in vm.uiState.value.addresses(studio))
            vm.setShowFinished(true)
            assertTrue("t6" in vm.uiState.value.addresses(studio))
            vm.setShowFinished(false)
            vm.setFilter(TerminalFilter.Finished)
            assertEquals(listOf("t6"), vm.uiState.value.addresses(studio))
        }

    @Test
    fun `stale host rows are read only and actions are refused without calling the core`() =
        runTest {
            val vm = vm(manager())
            val item =
                vm.uiState.value
                    .group(hetzner)
                    .sections
                    .first()
                    .items
                    .first()
            assertFalse(item.writable)
            assertFalse(
                vm.uiState.value
                    .group(hetzner)
                    .online,
            )
            vm.events.test {
                vm.requestKill(item)
                assertTrue(awaitItem() is TerminalsEvent.Info)
            }
            assertNull(vm.dialog.value)
        }

    @Test
    fun `kill asks first and only then calls the core`() =
        runTest {
            val m = manager()
            val session = m.fakeSession(studio)!!
            session.killHandler = { AppResult.Success(SampleData.terminals.first()) }
            val vm = vm(m)
            val item =
                vm.uiState.value
                    .group(studio)
                    .sections
                    .first()
                    .items
                    .first { it.address == "t1" }
            vm.requestKill(item)
            assertTrue(vm.dialog.value is TerminalDialog.ConfirmKill)
            assertTrue(session.calls.none { it.startsWith("kill") })
            vm.events.test {
                vm.confirmKill()
                assertTrue(awaitItem() is TerminalsEvent.Info)
            }
            assertEquals(listOf("kill:t1"), session.calls)
            assertNull(vm.dialog.value)
        }

    @Test
    fun `an unknown outcome is reported as such and never retried`() =
        runTest {
            val m = manager()
            val session = m.fakeSession(studio)!!
            session.interruptHandler = { AppResult.Failure(AppError.OutcomeUnknown("interrupt")) }
            val vm = vm(m)
            val item =
                vm.uiState.value
                    .group(studio)
                    .sections
                    .first()
                    .items
                    .first()
            vm.events.test {
                vm.interrupt(item)
                assertEquals(TerminalsEvent.OutcomeUnknown, awaitItem())
            }
            advanceUntilIdle()
            assertEquals(1, session.calls.count { it.startsWith("interrupt") })
        }

    @Test
    fun `blocked refusal asks before a forced resend`() =
        runTest {
            val m = manager()
            val session = m.fakeSession(studio)!!
            var first = true
            session.sendHandler = { _, _, force ->
                if (!force && first) {
                    first = false
                    AppResult.Failure(AppError.Rpc(AppError.Rpc.BLOCKED, "Program is waiting for permission"))
                } else {
                    AppResult.Success(SendReceipt("working", 3, true))
                }
            }
            val vm = vm(m)
            val item =
                vm.uiState.value
                    .group(studio)
                    .sections
                    .first()
                    .items
                    .first { it.address == "t2" }
            vm.requestSend(item)
            vm.onSendTextChange("yes")
            vm.sendText()
            val blocked = vm.dialog.value as TerminalDialog.SendText
            assertEquals(SendPhase.Blocked("Program is waiting for permission"), blocked.phase)
            assertEquals(listOf("send:t2:yes:false"), session.calls) // not resent on its own
            vm.sendText(force = true)
            assertEquals(SendPhase.Sent("working"), (vm.dialog.value as TerminalDialog.SendText).phase)
            assertEquals("send:t2:yes:true", session.calls.last())
        }

    @Test
    fun `send with an unknown outcome says so`() =
        runTest {
            val m = manager()
            m.fakeSession(studio)!!.sendHandler = { _, _, _ -> AppResult.Failure(AppError.OutcomeUnknown("send")) }
            val vm = vm(m)
            vm.requestSend(
                vm.uiState.value
                    .group(studio)
                    .sections
                    .first()
                    .items
                    .first(),
            )
            vm.onSendTextChange("hi")
            vm.sendText()
            assertEquals(SendPhase.OutcomeUnknown, (vm.dialog.value as TerminalDialog.SendText).phase)
        }

    @Test
    fun `new terminal offers online hosts and connected machines only`() =
        runTest {
            val vm = vm()
            vm.openNewTerminal()
            val form = (vm.dialog.value as TerminalDialog.NewTerminal).form
            assertEquals(listOf(studio), form.hosts.map { it.id })
            assertEquals(listOf("rohan"), form.hosts.first().machines)
            assertEquals(studio, form.hostId)
            assertEquals("~", form.cwd)
        }

    @Test
    fun `new terminal requires a working directory and a command for command mode`() =
        runTest {
            val m = manager()
            val session = m.fakeSession(studio)!!
            val vm = vm(m)
            vm.openNewTerminal()
            vm.updateForm { it.copy(cwd = " ") }
            vm.submitNewTerminal()
            var form = (vm.dialog.value as TerminalDialog.NewTerminal).form
            assertTrue(form.showErrors && form.cwdMissing)
            vm.updateForm { it.copy(cwd = "~/code", source = TerminalSource.Command) }
            vm.submitNewTerminal()
            form = (vm.dialog.value as TerminalDialog.NewTerminal).form
            assertTrue(form.commandMissing)
            assertTrue(session.calls.none { it.startsWith("run") })
        }

    @Test
    fun `starting a terminal opens it and remembers the folder per host`() =
        runTest {
            val m = manager()
            val session = m.fakeSession(studio)!!
            session.runHandler = { AppResult.Success(SampleData.terminals[2]) }
            val vm = vm(m)
            vm.openNewTerminal()
            vm.updateForm { it.copy(cwd = "~/code/flocket", machine = "rohan", keep = true) }
            vm.events.test {
                vm.submitNewTerminal()
                assertEquals(TerminalsEvent.OpenTerminal(studio, "t3"), awaitItem())
            }
            assertEquals("run:~/code/flocket:null:null:rohan:true", session.calls.single())
            assertNull(vm.dialog.value)
            vm.openNewTerminal()
            assertEquals("~/code/flocket", (vm.dialog.value as TerminalDialog.NewTerminal).form.cwd)
        }

    @Test
    fun `a failed start keeps the sheet open with the error`() =
        runTest {
            val m = manager()
            m.fakeSession(studio)!!.runHandler =
                { AppResult.Failure(AppError.Rpc(AppError.Rpc.NAME_TAKEN, "name taken")) }
            val vm = vm(m)
            vm.openNewTerminal()
            vm.submitNewTerminal()
            val form = (vm.dialog.value as TerminalDialog.NewTerminal).form
            assertNotNull(form.error)
            assertFalse(form.submitting)
        }

    @Test
    fun `templates load when the core supports them`() =
        runTest {
            val m = manager(withTemplates = true)
            m.fakeSession(studio)!!.templatesHandler = {
                AppResult.Success(
                    listOf(
                        com.mtajchert.flocket.core.model
                            .TerminalTemplate("claude", "Claude Code", "claude", null),
                    ),
                )
            }
            val vm = vm(m)
            vm.openNewTerminal()
            val form = (vm.dialog.value as TerminalDialog.NewTerminal).form
            assertEquals(1, (form.templates as TemplatesState.Loaded).templates.size)
        }

    @Test
    fun `refresh retries only hosts that are not online`() =
        runTest {
            val m = manager()
            val vm = vm(m)
            vm.refresh()
            assertTrue(vm.uiState.value.refreshing)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.refreshing)
            assertEquals(0, m.fakeSession(studio)!!.retryCount)
            assertEquals(1, m.fakeSession(hetzner)!!.retryCount)
            assertEquals(1, m.fakeSession(pi)!!.retryCount)
        }

    @Test
    fun `empty states`() =
        runTest {
            val none = FakeConnectionManager(FakeHostRepository())
            assertEquals(EmptyKind.NoHosts, vm(none).uiState.value.emptyKind)

            val repo = FakeHostRepository(listOf(SampleData.studio))
            val online =
                FakeConnectionManager(repo, initialState = { SampleData.studioOnline.copy(terminals = emptyList()) })
            assertEquals(EmptyKind.NoTerminals, vm(online).uiState.value.emptyKind)

            val offline =
                FakeConnectionManager(
                    FakeHostRepository(listOf(SampleData.pi)),
                    initialState = { SampleData.piNeedsTrust },
                )
            val state = vm(offline).uiState.value
            assertEquals(EmptyKind.AllOffline, state.emptyKind)
            assertNotNull(state.groups.single().error) // the "review host key" action is still reachable
        }

    @Test
    fun `needs attention hosts expose the error action`() =
        runTest {
            val g = vm().uiState.value.group(pi)
            assertEquals(com.mtajchert.flocket.core.ui.error.UiErrorAction.TrustHostKey, g.error?.primaryAction)
        }

    @Test
    fun `paths are shortened`() {
        assertEquals("~/code/flocket", shortenPath("/Users/me/code/flocket"))
        assertEquals("~", shortenPath("/home/me"))
        assertEquals("/srv/app", shortenPath("/srv/app"))
        assertEquals(
            "~/…/very-long-directory-name/src",
            shortenPath("/Users/me/projects/clients/acme/very-long-directory-name/src"),
        )
    }

    @Suppress("unused")
    private val keepImports = listOf<Any?>(FakeCoreSession::class, SessionState::class)

    @Test
    fun `new terminal prefills the app default folder, a host override wins, and typing is not overwritten`() =
        runTest {
            val prefs = FakePreferencesRepository()
            prefs.update { it.copy(terminal = it.terminal.copy(defaultWorkingDirectory = "~/work")) }
            val settings = FakeHostSettingsRepository(prefs)
            val vm = vm(settings = settings)
            vm.openNewTerminal()
            assertEquals("~/work", (vm.dialog.value as TerminalDialog.NewTerminal).form.cwd)

            settings.update(studio) { it.copy(defaultWorkingDirectory = "~/code") }
            vm.openNewTerminal()
            assertEquals("~/code", (vm.dialog.value as TerminalDialog.NewTerminal).form.cwd)
        }

    @Test
    fun `configured default folder wins over last used, which wins over home when none is set`() =
        runTest {
            val prefs = FakePreferencesRepository()
            val settings = FakeHostSettingsRepository(prefs)
            val last = InMemoryLastCwdStore().apply { put(studio, "~/last") }
            val vm = vm(settings = settings, lastCwd = last)
            vm.openNewTerminal()
            advanceUntilIdle()
            assertEquals("~/last", (vm.dialog.value as TerminalDialog.NewTerminal).form.cwd)

            prefs.update { it.copy(terminal = it.terminal.copy(defaultWorkingDirectory = "~/work")) }
            vm.openNewTerminal()
            advanceUntilIdle()
            assertEquals("~/work", (vm.dialog.value as TerminalDialog.NewTerminal).form.cwd)
        }

    @Test
    fun `refresh and retry connect hosts that do not connect on launch`() =
        runTest {
            val m = manager()
            val vm = vm(m)
            vm.refresh()
            advanceUntilIdle()
            assertTrue(
                m.connectRequests.isNotEmpty() ||
                    m.overview.value.all { it.session.connection is ConnectionState.Online },
            )
            vm.onHostAction(studio, com.mtajchert.flocket.core.ui.error.UiErrorAction.Retry)
            assertTrue(studio in m.connectRequests)
        }

    // ---- rename -------------------------------------------------------------------------------

    private val spaceRef = LayoutRef(null, "s1")
    private val tabRef = LayoutRef(null, "s1", "tb1")
    private val rohanRef = LayoutRef("rohan", "rs1")

    private suspend fun TestScope.renameWith(
        ref: LayoutRef,
        host: HostId = studio,
        text: String = "new",
        handler: suspend (LayoutRef, String) -> AppResult<Unit>,
    ): List<TerminalsEvent> {
        val m = manager()
        m.fakeSession(host)!!.renameHandler = handler
        val vm = vm(m)
        val events = mutableListOf<TerminalsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.requestRename(host, ref, "old", "auto")
        vm.updateRename(text)
        vm.confirmRename()
        advanceUntilIdle()
        return events
    }

    @Test
    fun `rename calls renameInLayout with the ref and trimmed text`() =
        runTest {
            val m = manager()
            val session = m.fakeSession(studio)!!
            session.renameHandler = { _, _ -> AppResult.Success(Unit) }
            val vm = vm(m)
            vm.requestRename(studio, tabRef, "old", "2")
            assertEquals(TerminalDialog.Rename(studio, tabRef, "old", "2"), vm.dialog.value)
            vm.updateRename("  build  ")
            vm.confirmRename()
            advanceUntilIdle()
            assertNull(vm.dialog.value)
            assertEquals(listOf("rename:null:s1:tb1:build"), session.calls.filter { it.startsWith("rename:") })
        }

    @Test
    fun `rename errors map to the exact messages`() =
        runTest {
            fun text(events: List<TerminalsEvent>) = (events.single() as TerminalsEvent.Info).text as UiText.Res

            var e = renameWith(tabRef) { _, _ -> AppResult.Failure(AppError.Rpc(AppError.Rpc.CONFLICT, "x")) }
            assertEquals(UiR.string.rename_error_conflict, text(e).id)
            e = renameWith(tabRef) { _, _ -> AppResult.Failure(AppError.Rpc(AppError.Rpc.NOT_FOUND, "x")) }
            assertEquals(UiR.string.rename_error_gone_tab, text(e).id)
            e = renameWith(spaceRef) { _, _ -> AppResult.Failure(AppError.Rpc(AppError.Rpc.NOT_FOUND, "x")) }
            assertEquals(UiR.string.rename_error_gone_space, text(e).id)
            e = renameWith(rohanRef) { _, _ -> AppResult.Failure(AppError.Rpc(AppError.Rpc.UNAVAILABLE, "x")) }
            assertEquals(UiR.string.rename_error_unreachable, text(e).id)
            assertEquals(listOf<Any>("rohan"), text(e).args)
            e = renameWith(rohanRef) { _, _ -> AppResult.Failure(AppError.Rpc("needs_update", "x")) }
            assertEquals(listOf<Any>("rohan"), text(e).args)
            e = renameWith(spaceRef) { _, _ -> AppResult.Failure(AppError.ConnectionLost) }
            assertEquals(UiR.string.rename_error_unreachable, text(e).id)
            assertEquals(listOf<Any>(SampleData.studio.label), text(e).args)
            e = renameWith(spaceRef) { _, _ -> AppResult.Failure(AppError.OutcomeUnknown("doc.set")) }
            assertEquals(UiR.string.rename_outcome_unknown, text(e).id)
            // Anything else uses the generic failure path.
            e = renameWith(spaceRef) { _, _ -> AppResult.Failure(AppError.Rpc(AppError.Rpc.UNAVAILABLE, "x")) }
            assertTrue(e.single() is TerminalsEvent.Failure)
        }

    @Test
    fun `rename is not offered while the host is stale or the machine is not connected`() =
        runTest {
            val m = manager()
            val session = m.fakeSession(hetzner)!!
            val vm = vm(m)
            vm.events.test {
                vm.requestRename(hetzner, spaceRef, "old", "auto")
                assertTrue(awaitItem() is TerminalsEvent.Info)
            }
            assertNull(vm.dialog.value)
            assertTrue(session.calls.none { it.startsWith("rename:") })
            // A stale host is named by its label; a federated space whose machine is not connected, by the machine.
            vm.events.test {
                vm.requestRename(hetzner, spaceRef, "old", "auto")
                val host = (awaitItem() as TerminalsEvent.Info).text as UiText.Res
                assertEquals(UiR.string.rename_error_unreachable, host.id)
                assertEquals(listOf<Any>(SampleData.hetzner.label), host.args)
            }
            vm.events.test {
                vm.requestRename(studio, LayoutRef("ghost", "gs"), "old", "auto")
                val machine = (awaitItem() as TerminalsEvent.Info).text as UiText.Res
                assertEquals(UiR.string.rename_error_unreachable, machine.id)
                assertEquals(listOf<Any>("ghost"), machine.args)
            }
            assertNull(vm.dialog.value)
        }
}
