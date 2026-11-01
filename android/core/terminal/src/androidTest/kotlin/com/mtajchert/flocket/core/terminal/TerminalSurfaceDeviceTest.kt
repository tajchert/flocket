package com.mtajchert.flocket.core.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.session.AttachMode
import com.mtajchert.flocket.core.session.DisplayFacts
import com.mtajchert.flocket.core.session.StreamEnd
import com.mtajchert.flocket.core.session.TerminalSize
import com.mtajchert.flocket.core.session.TerminalStream
import com.mtajchert.flocket.core.terminal.controller.TerminalController
import com.mtajchert.flocket.core.terminal.engine.TerminalEngineFactory
import com.mtajchert.flocket.core.terminal.ghostty.GhosttyTerminalEngine
import com.mtajchert.flocket.core.terminal.ui.TerminalSurface
import com.mtajchert.flocket.core.terminal.ui.TerminalThemes
import com.mtajchert.flocket.core.terminal.ui.TerminalView
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Stream whose output is a channel; counts how often it is (re)collected = (re)attached. */
class DeviceFakeStream : TerminalStream {
    override val address = TerminalAddress("t1")
    override val size = MutableStateFlow(TerminalSize(40, 12))
    override val connected = MutableStateFlow(true)
    override val end = MutableStateFlow<StreamEnd?>(null)
    val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    val collections = AtomicInteger()
    override val output =
        flow {
            collections.incrementAndGet()
            for (c in chunks) emit(c)
        }

    override suspend fun write(bytes: ByteArray): AppResult<Unit> = AppResult.Success(Unit)

    override fun resize(
        cols: Int,
        rows: Int,
    ) {
        size.value = TerminalSize(cols, rows)
    }

    override fun focus(focused: Boolean) = Unit

    override fun updateDisplay(facts: DisplayFacts) = Unit

    override suspend fun detach() = Unit
}

class TerminalTestViewModel : ViewModel() {
    val engines = AtomicInteger()
    val stream = DeviceFakeStream()
    val controller =
        TerminalController(
            engineFactory =
                TerminalEngineFactory { c, r ->
                    engines.incrementAndGet()
                    GhosttyTerminalEngine(c, r)
                },
            theme = TerminalThemes.Dark,
        ).also { it.bind(stream, AttachMode.Passive) }

    override fun onCleared() = controller.close()
}

class TerminalTestActivity : ComponentActivity() {
    val vm: TerminalTestViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TerminalSurface(vm.controller, TerminalThemes.Dark, Modifier.fillMaxSize()) }
    }
}

@RunWith(AndroidJUnit4::class)
class TerminalSurfaceDeviceTest {
    private fun fixture(): ByteArray {
        val hex =
            InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open("attach-first-paint.hex")
                .bufferedReader()
                .readText()
                .trim()
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun waitFor(
        timeoutMs: Long = 5_000,
        check: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
        }
    }

    private fun ActivityScenario<TerminalTestActivity>.terminalBitmap(): Bitmap {
        lateinit var bmp: Bitmap
        onActivity { a ->
            val view = findTerminal(a.window.decorView)!!
            bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
        }
        return bmp
    }

    private fun findTerminal(v: View): TerminalView? {
        if (v is TerminalView) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                findTerminal(
                    v.getChildAt(i),
                )?.let { return it }
            }
        }
        return null
    }

    @Test
    fun rendersTheFirstPaintFixtureWithTheGhosttyEngine() {
        ActivityScenario.launch(TerminalTestActivity::class.java).use { scenario ->
            lateinit var vm: TerminalTestViewModel
            scenario.onActivity { vm = it.vm }
            runBlocking { vm.stream.chunks.send(fixture()) }
            waitFor { vm.controller.bytesFed > 0 && vm.controller.publishedFrames > 0 }
            assertEquals("sh-3.2$", runBlocking { vm.controller.screenText() }.lines().first().trimEnd())
            waitFor {
                val bmp = scenario.terminalBitmap()
                // Some pixel in the first text row differs from the background.
                val bg = TerminalThemes.Dark.background
                (0 until bmp.width / 2).any { x -> (0 until bmp.height / 10).any { y -> bmp.getPixel(x, y) != bg } }
            }
        }
    }

    @Test
    fun recreatingTheActivityKeepsTheControllerAndDoesNotReattach() {
        ActivityScenario.launch(TerminalTestActivity::class.java).use { scenario ->
            lateinit var before: TerminalController
            lateinit var vm: TerminalTestViewModel
            scenario.onActivity {
                vm = it.vm
                before = it.vm.controller
            }
            runBlocking { vm.stream.chunks.send("hello\r\n".encodeToByteArray()) }
            waitFor { vm.controller.publishedFrames > 0 && vm.stream.collections.get() == 1 }
            scenario.recreate()
            lateinit var after: TerminalController
            scenario.onActivity { after = it.vm.controller }
            assertSame(before, after)
            runBlocking { vm.stream.chunks.send("world".encodeToByteArray()) }
            waitFor { runBlocking { vm.controller.screenText() }.contains("world") }
            assertEquals(1, vm.engines.get())
            assertEquals(1, vm.stream.collections.get())
            assertTrue(runBlocking { vm.controller.screenText() }.startsWith("hello"))
            assertNotEquals(0, scenario.terminalBitmap().width)
        }
    }
}
