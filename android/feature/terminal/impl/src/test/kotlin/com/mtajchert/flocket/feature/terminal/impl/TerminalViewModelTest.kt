package com.mtajchert.flocket.feature.terminal.impl

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.session.AttachRequest
import com.mtajchert.flocket.core.session.StreamEnd
import com.mtajchert.flocket.core.session.TerminalStream
import com.mtajchert.flocket.core.terminal.controller.TerminalController
import com.mtajchert.flocket.core.terminal.fake.FakeTerminalEngine
import com.mtajchert.flocket.core.testing.FakeConnectionManager
import com.mtajchert.flocket.core.testing.FakeCoreSession
import com.mtajchert.flocket.core.testing.FakeHostRepository
import com.mtajchert.flocket.core.testing.FakePreferencesRepository
import com.mtajchert.flocket.core.testing.FakeTerminalStream
import com.mtajchert.flocket.core.testing.MainDispatcherRule
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.feature.terminal.api.TerminalRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val hosts = FakeHostRepository(SampleData.hosts)
    private val manager =
        FakeConnectionManager(hosts, initialState = { host ->
            if (host ==
                SampleData.studio
            ) {
                SampleData.studioOnline.copy(connection = ConnectionState.Idle)
            } else {
                SampleData.piNeedsTrust
            }
        })
    private val session: FakeCoreSession get() = manager.fakeSession(SampleData.studio.id)!!
    private val streams = mutableListOf<FakeTerminalStream>()
    private val store = ViewModelStore()

    private fun TestScope.viewModel(
        address: String = "t1",
        startedHere: Boolean = false,
        savedState: SavedStateHandle = SavedStateHandle(),
        attach: (suspend (TerminalAddress, AttachRequest) -> AppResult<TerminalStream>)? = null,
    ): TerminalViewModel {
        manager.overview.value // sessions are created with the overview
        session.attachHandler = attach ?: { a, _ -> AppResult.Success(FakeTerminalStream(a).also { streams += it }) }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val vm =
            TerminalViewModel(
                route = TerminalRoute("studio", address, startedHere),
                savedState = savedState,
                connections = manager,
                preferences = FakePreferencesRepository(),
                controllerFactory = {
                    TerminalController(
                        engineFactory = { c, r -> FakeTerminalEngine(c, r) },
                        theme = FakeTerminalEngine.DEFAULT_THEME,
                        frameClock = { awaitCancellation() },
                        engineDispatcher = dispatcher,
                    )
                },
                appScope = CoroutineScope(dispatcher),
            )
        val provided =
            ViewModelProvider.create(
                store,
                viewModelFactory { initializer { vm } },
            )[TerminalViewModel::class]
        backgroundScope.launch { provided.uiState.collect {} }
        runCurrent()
        return provided
    }

    private fun goOnline() = session.update { it.copy(connection = ConnectionState.Online(SampleData.coreInfo)) }

    private fun attaches() = session.calls.filter { it.startsWith("attach:") }

    @Test
    fun `waits for Online, then attaches passively once`() =
        runTest {
            val vm = viewModel()
            assertEquals(TerminalPhase.Connecting, vm.uiState.value.phase)
            assertTrue(attaches().isEmpty())

            goOnline()
            advanceUntilIdle()

            assertEquals(listOf("attach:t1:Passive"), attaches())
            assertEquals(TerminalPhase.Live, vm.uiState.value.phase)
            assertTrue(vm.uiState.value.streamConnected)
            assertFalse("passive never takes the size", streams.single().calls.any { it.startsWith("resize") })
        }

    @Test
    fun `a terminal started here attaches as owner, and re-attaches passively`() =
        runTest {
            val saved = SavedStateHandle()
            goOnline()
            val vm = viewModel(startedHere = true, savedState = saved)
            advanceUntilIdle()
            assertEquals(listOf("attach:t1:Owner"), attaches())

            streams.last().end.value = StreamEnd.Unknown
            advanceUntilIdle()
            assertEquals(TerminalPhase.StreamEnded, vm.uiState.value.phase)
            vm.retryAttach()
            advanceUntilIdle()
            assertEquals(listOf("attach:t1:Owner", "attach:t1:Passive"), attaches())
            assertEquals(TerminalPhase.Live, vm.uiState.value.phase)
        }

    @Test
    fun `after process death a started-here route attaches passively`() =
        runTest {
            goOnline()
            viewModel(startedHere = true, savedState = SavedStateHandle(mapOf("attached_once" to true)))
            advanceUntilIdle()
            assertEquals(listOf("attach:t1:Passive"), attaches())
        }

    @Test
    fun `program end shows the exit banner`() =
        runTest {
            goOnline()
            val vm = viewModel()
            advanceUntilIdle()
            streams.single().end.value = StreamEnd.ProgramEnded("exit", 3, null)
            advanceUntilIdle()
            assertEquals(TerminalPhase.Exited(3, null), vm.uiState.value.phase)
        }

    @Test
    fun `a finished record is not attached`() =
        runTest {
            goOnline()
            val vm = viewModel(address = "t6")
            advanceUntilIdle()
            assertTrue(attaches().isEmpty())
            assertEquals(TerminalPhase.Exited(0, null), vm.uiState.value.phase)
        }

    @Test
    fun `lost program waits for the new shell and re-attaches with a fresh-shell notice`() =
        runTest {
            goOnline()
            val vm = viewModel()
            advanceUntilIdle()
            session.update { st ->
                st.copy(
                    terminals =
                        st.terminals.map {
                            if (it.address.value ==
                                "t1"
                            ) {
                                it.copy(liveness = Liveness.Waiting("holder_lost"))
                            } else {
                                it
                            }
                        },
                )
            }
            streams.single().end.value = StreamEnd.ProgramLost("holder_lost")
            advanceUntilIdle()
            assertEquals(TerminalPhase.WaitingForShell, vm.uiState.value.phase)
            assertEquals(1, attaches().size)

            session.update { st ->
                st.copy(
                    terminals =
                        st.terminals.map {
                            if (it.address.value ==
                                "t1"
                            ) {
                                it.copy(liveness = Liveness.Running)
                            } else {
                                it
                            }
                        },
                )
            }
            advanceUntilIdle()
            assertEquals(2, attaches().size)
            assertEquals(TerminalPhase.Live, vm.uiState.value.phase)
            assertTrue(vm.uiState.value.freshShell)
            vm.dismissFreshShell()
            assertFalse(vm.uiState.value.freshShell)
        }

    @Test
    fun `attach rejected as unavailable waits for restarted`() =
        runTest {
            goOnline()
            var first = true
            val vm =
                viewModel { a, _ ->
                    if (first) {
                        first = false
                        // The core noticed the lost holder before our record did.
                        session.update { st ->
                            st.copy(
                                terminals =
                                    st.terminals.map {
                                        if (it.address.value ==
                                            "t1"
                                        ) {
                                            it.copy(liveness = Liveness.Waiting("holder_lost"))
                                        } else {
                                            it
                                        }
                                    },
                            )
                        }
                        AppResult.Failure(AppError.Rpc(AppError.Rpc.UNAVAILABLE, "waiting"))
                    } else {
                        AppResult.Success(FakeTerminalStream(a).also { streams += it })
                    }
                }
            advanceUntilIdle()
            assertEquals(TerminalPhase.WaitingForShell, vm.uiState.value.phase)
            session.update { st ->
                st.copy(
                    terminals =
                        st.terminals.map {
                            if (it.address.value ==
                                "t1"
                            ) {
                                it.copy(liveness = Liveness.Running)
                            } else {
                                it
                            }
                        },
                )
            }
            advanceUntilIdle()
            assertEquals(TerminalPhase.Live, vm.uiState.value.phase)
        }

    @Test
    fun `stream reconnecting is reflected without re-attaching`() =
        runTest {
            goOnline()
            val vm = viewModel()
            advanceUntilIdle()
            streams.single().connected.value = false
            advanceUntilIdle()
            assertFalse(vm.uiState.value.streamConnected)
            assertEquals(TerminalPhase.Live, vm.uiState.value.phase)
            streams.single().connected.value = true
            advanceUntilIdle()
            assertTrue(vm.uiState.value.streamConnected)
            assertEquals(1, attaches().size)
        }

    @Test
    fun `clearing the ViewModel detaches the stream`() =
        runTest {
            goOnline()
            viewModel()
            advanceUntilIdle()
            assertFalse("detach" in streams.single().calls)
            store.clear()
            advanceUntilIdle()
            assertTrue("detach" in streams.single().calls)
        }

    @Test
    fun `interrupt with unknown outcome is reported, never retried`() =
        runTest {
            goOnline()
            val vm = viewModel()
            advanceUntilIdle()
            session.interruptHandler = { AppResult.Failure(AppError.OutcomeUnknown("terminal.interrupt")) }
            vm.interrupt()
            advanceUntilIdle()
            assertEquals(TerminalScreenEvent.OutcomeUnknown, vm.events.first())
            assertEquals(1, session.calls.count { it.startsWith("interrupt:") })
        }

    @Test
    fun `kill needs confirmation`() =
        runTest {
            goOnline()
            val vm = viewModel()
            session.killHandler = { AppResult.Success(SampleData.terminals.first()) }
            vm.requestKill()
            assertTrue(vm.uiState.value.confirmKill)
            assertTrue(session.calls.none { it.startsWith("kill:") })
            vm.confirmKill()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.confirmKill)
            assertEquals(1, session.calls.count { it.startsWith("kill:") })
        }

    @Test
    fun `clipboard requests need consent`() =
        runTest {
            val vm = viewModel()
            vm.onClipboardWriteRequest("secret")
            assertEquals("secret", vm.uiState.value.clipboardRequest)
            vm.answerClipboardRequest(allow = false)
            assertEquals(null, vm.uiState.value.clipboardRequest)
            vm.onClipboardWriteRequest("ok")
            vm.answerClipboardRequest(allow = true)
            assertEquals(TerminalScreenEvent.CopyToClipboard("ok"), vm.events.first())
        }

    @Test
    fun `unknown host shows host missing`() =
        runTest {
            val vm =
                TerminalViewModel(
                    TerminalRoute("gone", "t1"),
                    SavedStateHandle(),
                    manager,
                    FakePreferencesRepository(),
                    {
                        TerminalController({ c, r -> FakeTerminalEngine(c, r) }, FakeTerminalEngine.DEFAULT_THEME, {
                            awaitCancellation()
                        }, UnconfinedTestDispatcher(testScheduler))
                    },
                    CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                )
            backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()
            assertEquals(TerminalPhase.HostMissing, vm.uiState.value.phase)
        }
}
