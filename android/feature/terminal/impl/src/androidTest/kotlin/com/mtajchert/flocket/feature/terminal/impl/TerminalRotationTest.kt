package com.mtajchert.flocket.feature.terminal.impl

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.terminal.controller.TerminalController
import com.mtajchert.flocket.core.terminal.fake.FakeTerminalEngine
import com.mtajchert.flocket.core.testing.FakeAgentMarks
import com.mtajchert.flocket.core.testing.FakeConnectionManager
import com.mtajchert.flocket.core.testing.FakeHostRepository
import com.mtajchert.flocket.core.testing.FakeHostSettingsRepository
import com.mtajchert.flocket.core.testing.FakePreferencesRepository
import com.mtajchert.flocket.core.testing.FakeShareSeenRepository
import com.mtajchert.flocket.core.testing.FakeTerminalStream
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.feature.terminal.api.TerminalRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Rotation / fold / any config change: the ViewModel (and its controller and stream) is retained,
 * so the terminal is never re-attached; only a real close detaches.
 */
@RunWith(AndroidJUnit4::class)
class TerminalRotationTest {
    private val manager =
        FakeConnectionManager(FakeHostRepository(SampleData.hosts), initialState = { SampleData.studioOnline })
    private val streams = CopyOnWriteArrayList<FakeTerminalStream>()

    private val factory =
        viewModelFactory {
            initializer {
                TerminalViewModel(
                    route = TerminalRoute("studio", "t1"),
                    connections = manager,
                    preferences = FakePreferencesRepository(),
                    hostSettings = FakeHostSettingsRepository(),
                    agentMarks = FakeAgentMarks(),
                    controllerFactory = {
                        TerminalController({ c, r -> FakeTerminalEngine(c, r) }, FakeTerminalEngine.DEFAULT_THEME)
                    },
                    appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    shareSeen = FakeShareSeenRepository(),
                )
            }
        }

    private fun ComponentActivity.show(): TerminalViewModel {
        val vm = ViewModelProvider(this, factory)[TerminalViewModel::class.java]
        setContent { FlocketTheme(dynamicColor = false) { TerminalRouteContent(vm, onClose = {}) } }
        return vm
    }

    private fun waitFor(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    @Test
    fun recreateKeepsControllerAndStreamWithoutReattach() {
        manager.overview.value
        val session = manager.fakeSession(SampleData.studio.id)!!
        session.attachHandler = { a, _ -> AppResult.Success(FakeTerminalStream(a).also { streams += it }) }
        assertTrue(SampleData.studioOnline.connection is ConnectionState.Online)

        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        lateinit var first: TerminalViewModel
        scenario.onActivity { first = it.show() }
        waitFor { streams.size == 1 && first.uiState.value.phase == TerminalPhase.Live }
        streams.single().chunks.trySend("hello from the core\r\n".encodeToByteArray())

        scenario.recreate()
        lateinit var second: TerminalViewModel
        scenario.onActivity { second = it.show() }

        assertSame(first, second)
        assertSame(first.controller, second.controller)
        Thread.sleep(300)
        assertEquals("no re-attach on recreate", 1, session.calls.count { it.startsWith("attach:") })
        assertTrue("not detached on recreate", "detach" !in streams.single().calls)

        scenario.close()
        waitFor { "detach" in streams.single().calls }
    }
}
