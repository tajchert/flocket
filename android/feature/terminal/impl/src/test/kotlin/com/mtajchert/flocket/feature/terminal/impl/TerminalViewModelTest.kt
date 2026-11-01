package com.mtajchert.flocket.feature.terminal.impl

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.UserPreferences
import com.mtajchert.flocket.core.model.BellMode
import com.mtajchert.flocket.core.model.ClipboardWritePolicy
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalColorScheme
import com.mtajchert.flocket.core.session.AttachRequest
import com.mtajchert.flocket.core.session.StreamEnd
import com.mtajchert.flocket.core.session.TerminalStream
import com.mtajchert.flocket.core.terminal.controller.TerminalController
import com.mtajchert.flocket.core.terminal.fake.FakeTerminalEngine
import com.mtajchert.flocket.core.testing.FakeConnectionManager
import com.mtajchert.flocket.core.testing.FakeCoreSession
import com.mtajchert.flocket.core.testing.FakeHostRepository
import com.mtajchert.flocket.core.testing.FakeHostSettingsRepository
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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        preferences: FakePreferencesRepository = FakePreferencesRepository(),
        settings: FakeHostSettingsRepository = FakeHostSettingsRepository(preferences),
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
                preferences = preferences,
                hostSettings = settings,
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
            assertEquals(setOf<Any>(vm), manager.heldTerminals)
            streams.single().end.value = StreamEnd.ProgramEnded("exit", 3, null)
            advanceUntilIdle()
            assertEquals(TerminalPhase.Exited(3, null, "exit"), vm.uiState.value.phase)
            // An ended terminal no longer keeps the app connected in the background.
            assertTrue(manager.heldTerminals.isEmpty())
        }

    @Test
    fun `notification permission is offered once while attached and only with keep-alive on`() =
        runTest {
            goOnline()
            val prefs = FakePreferencesRepository()
            val vm = viewModel(preferences = prefs)
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()
            assertTrue(vm.uiState.value.offerNotificationPermission)
            vm.onNotificationPermissionAsked()
            advanceUntilIdle()
            assertTrue(!vm.uiState.value.offerNotificationPermission)
            assertTrue(prefs.preferences.value.notificationPermissionAsked)
            collector.cancel()
        }

    @Test
    fun `notification permission is not offered when keep-alive is off`() =
        runTest {
            goOnline()
            val prefs = FakePreferencesRepository(UserPreferences(keepConnectedInBackground = false))
            val vm = viewModel(preferences = prefs)
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()
            assertEquals(TerminalPhase.Live, vm.uiState.value.phase)
            assertTrue(!vm.uiState.value.offerNotificationPermission)
            collector.cancel()
        }

    @Test
    fun `a finished record is not attached`() =
        runTest {
            goOnline()
            val vm = viewModel(address = "t6")
            advanceUntilIdle()
            assertTrue(attaches().isEmpty())
            assertEquals(TerminalPhase.Exited(0, null, "exit"), vm.uiState.value.phase)
            assertTrue(manager.heldTerminals.isEmpty())
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
                    FakeHostSettingsRepository(),
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

    // ---- layered settings -------------------------------------------------------------------------

    private val studioId = SampleData.studio.id

    @Test
    fun `screen state uses the app defaults then the host overrides`() =
        runTest {
            val prefs = FakePreferencesRepository()
            val settings = FakeHostSettingsRepository(prefs)
            val vm = viewModel(preferences = prefs, settings = settings)
            var ui = vm.uiState.value
            assertEquals(13f, ui.fontSizeSp)
            assertEquals(TerminalColorScheme.FollowApp, ui.colorScheme)
            assertEquals(BellMode.Haptic, ui.bell)
            assertTrue(ui.showExtraKeys && ui.keepScreenOn)

            prefs.update { it.copy(terminal = it.terminal.copy(fontSizeSp = 16f, showExtraKeys = false)) }
            ui = vm.uiState.value
            assertEquals(16f, ui.fontSizeSp)
            assertFalse(ui.showExtraKeys)

            settings.update(studioId) {
                it.copy(
                    fontSizeSp = 20f,
                    colorScheme = TerminalColorScheme.SolarizedDark,
                    showExtraKeys = true,
                    keepScreenOn = false,
                    bell = BellMode.Off,
                )
            }
            ui = vm.uiState.value
            assertEquals(20f, ui.fontSizeSp)
            assertEquals(TerminalColorScheme.SolarizedDark, ui.colorScheme)
            assertTrue(ui.showExtraKeys)
            assertFalse(ui.keepScreenOn)
            assertEquals(BellMode.Off, ui.bell)
            // The app default keeps changing underneath, the override stays.
            prefs.update { it.copy(terminal = it.terminal.copy(fontSizeSp = 9f)) }
            assertEquals(20f, vm.uiState.value.fontSizeSp)
        }

    @Test
    fun `opening a terminal asks the manager to connect its host`() =
        runTest {
            viewModel()
            assertTrue(studioId in manager.connectRequests)
        }

    @Test
    fun `pinch writes the host font override when it has one`() =
        runTest {
            val prefs = FakePreferencesRepository()
            val settings = FakeHostSettingsRepository(prefs)
            settings.update(studioId) { it.copy(fontSizeSp = 14f) }
            val vm = viewModel(preferences = prefs, settings = settings)
            vm.onFontSizeChange(18f)
            advanceUntilIdle()
            assertEquals(18f, settings.overridesNow.getValue(studioId).fontSizeSp)
            assertEquals(13f, prefs.preferences.value.terminal.fontSizeSp)
        }

    @Test
    fun `pinch writes the app default when the host follows it`() =
        runTest {
            val prefs = FakePreferencesRepository()
            val settings = FakeHostSettingsRepository(prefs)
            val vm = viewModel(preferences = prefs, settings = settings)
            vm.onFontSizeChange(18f)
            advanceUntilIdle()
            assertEquals(18f, prefs.preferences.value.terminal.fontSizeSp)
            assertTrue(settings.overridesNow.isEmpty())
        }

    @Test
    fun `clipboard Allow copies without a dialog and Deny drops the request`() =
        runTest {
            val prefs = FakePreferencesRepository()
            val settings = FakeHostSettingsRepository(prefs)
            val vm = viewModel(preferences = prefs, settings = settings)
            settings.update(studioId) { it.copy(clipboardWritePolicy = ClipboardWritePolicy.Allow) }
            vm.onClipboardWriteRequest("quiet")
            assertNull(vm.uiState.value.clipboardRequest)
            assertEquals(TerminalScreenEvent.CopyToClipboard("quiet"), vm.events.first())

            settings.update(studioId) { it.copy(clipboardWritePolicy = ClipboardWritePolicy.Deny) }
            vm.onClipboardWriteRequest("nope")
            assertNull(vm.uiState.value.clipboardRequest)
            val next = withTimeoutOrNull(50) { vm.events.first() }
            // Only the earlier note about the allowed copy may be pending, never a second copy.
            assertTrue(next !is TerminalScreenEvent.CopyToClipboard)
        }

    @Test
    fun `clipboard Ask from the app default shows the dialog and always-allow sets the host override`() =
        runTest {
            val prefs = FakePreferencesRepository()
            val settings = FakeHostSettingsRepository(prefs)
            val vm = viewModel(preferences = prefs, settings = settings)
            vm.onClipboardWriteRequest("first")
            assertEquals("first", vm.uiState.value.clipboardRequest)
            vm.alwaysAllowClipboard()
            assertNull(vm.uiState.value.clipboardRequest)
            assertEquals(TerminalScreenEvent.CopyToClipboard("first"), vm.events.first())
            assertEquals(ClipboardWritePolicy.Allow, settings.overridesNow.getValue(studioId).clipboardWritePolicy)
            // The app default is untouched; later requests from this host no longer ask.
            assertEquals(ClipboardWritePolicy.Ask, prefs.preferences.value.terminal.clipboardWritePolicy)
            vm.onClipboardWriteRequest("second")
            assertNull(vm.uiState.value.clipboardRequest)
        }

    @Test
    fun `app level Deny applies to hosts without an override`() =
        runTest {
            val prefs = FakePreferencesRepository()
            prefs.update { it.copy(terminal = it.terminal.copy(clipboardWritePolicy = ClipboardWritePolicy.Deny)) }
            val vm = viewModel(preferences = prefs)
            vm.onClipboardWriteRequest("x")
            assertNull(vm.uiState.value.clipboardRequest)
        }
}
