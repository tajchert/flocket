package com.mtajchert.flocket.core.terminal.controller

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.session.AttachMode
import com.mtajchert.flocket.core.session.DisplayFacts
import com.mtajchert.flocket.core.session.StreamEnd
import com.mtajchert.flocket.core.session.TerminalSize
import com.mtajchert.flocket.core.session.TerminalStream
import com.mtajchert.flocket.core.terminal.engine.Key
import com.mtajchert.flocket.core.terminal.engine.KeyInput
import com.mtajchert.flocket.core.terminal.engine.ScreenSnapshot
import com.mtajchert.flocket.core.terminal.engine.TerminalEngine
import com.mtajchert.flocket.core.terminal.fake.FakeTerminalEngine
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Manual vsync: [awaitFrame] suspends until [tick]. */
class ManualFrameClock : FrameClock {
    private var waiter: CancellableContinuation<Long>? = null
    var ticks = 0
        private set

    override suspend fun awaitFrame(): Long =
        suspendCancellableCoroutine { cont ->
            waiter = cont
            cont.invokeOnCancellation { waiter = null }
        }

    fun tick() {
        ticks++
        val w = waiter ?: return
        waiter = null
        w.resume(ticks * 16_666_667L)
    }
}

class FakeStream(
    initial: TerminalSize = TerminalSize(80, 24),
) : TerminalStream {
    override val address = TerminalAddress("t1")
    override val size = MutableStateFlow(initial)
    val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    override val output = chunks.receiveAsFlow()
    override val end = MutableStateFlow<StreamEnd?>(null)
    override val connected = MutableStateFlow(true)
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val writes: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
    var writeResult: AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun write(bytes: ByteArray): AppResult<Unit> {
        writes += bytes
        calls += "write"
        return writeResult
    }

    override fun resize(
        cols: Int,
        rows: Int,
    ) {
        calls += "resize ${cols}x$rows"
    }

    override fun focus(focused: Boolean) {
        calls += "focus $focused"
    }

    override fun updateDisplay(facts: DisplayFacts) {
        calls += "display"
    }

    override suspend fun detach() {
        calls += "detach"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalControllerTest {
    private lateinit var engine: FakeTerminalEngine
    private val clock = ManualFrameClock()

    private fun TestScope.controller(): TerminalController =
        TerminalController(
            engineFactory = { c, r -> FakeTerminalEngine(c, r).also { engine = it } },
            theme = FakeTerminalEngine.DEFAULT_THEME,
            frameClock = clock,
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )

    // 412dp phone at 2.625x: 1000 x 1400 px area, 10 x 20 px cells -> 100 x 70.
    private val phone = TerminalViewport(1000, 1400, 10f, 20f)

    private fun frame(c: TerminalController): ScreenSnapshot = ScreenSnapshot(1, 1).also { c.acquireFrame(it) }

    private fun ScreenSnapshot.line(row: Int): String =
        buildString {
            for (c in 0 until cols) {
                append(
                    codepoints[index(c, row)].let {
                        if (it ==
                            0
                        ) {
                            ' '
                        } else {
                            it.toChar()
                        }
                    },
                )
            }
        }.trimEnd()

    @Test
    fun `feeds output chunks in order and publishes them on the next frame`() =
        runTest {
            val c = controller()
            val s = FakeStream()
            c.bind(s)
            listOf("he", "llo ", "wor", "ld").forEach { s.chunks.send(it.encodeToByteArray()) }
            advanceUntilIdle()
            assertEquals("", frame(c).line(0))
            clock.tick()
            advanceUntilIdle()
            assertEquals("hello world", frame(c).line(0))
            assertEquals(11L, c.bytesFed)
            c.close()
        }

    @Test
    fun `publishes at most one frame per vsync tick and drops no bytes`() =
        runTest {
            val c = controller()
            val s = FakeStream()
            c.bind(s)
            advanceUntilIdle()
            var sent = 0L
            repeat(50) { tick ->
                repeat(40) { i ->
                    val b = "\u001b[3${i % 8}mline $tick.$i\r\n".encodeToByteArray()
                    sent += b.size
                    s.chunks.send(b)
                }
                runCurrent()
                clock.tick()
                runCurrent()
                assertTrue("frames ${c.publishedFrames} > ticks ${clock.ticks}", c.publishedFrames <= clock.ticks)
            }
            advanceUntilIdle()
            clock.tick()
            advanceUntilIdle()
            assertEquals(sent, c.bytesFed)
            assertEquals(sent, engine.bytesFed)
            assertTrue(c.publishedFrames in 1..clock.ticks.toLong())
            assertEquals("line 49.39", frame(c).line(22))
            c.close()
        }

    @Test
    fun `all engine calls run on the single engine thread`() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "engine-test") }
        val threads = Collections.synchronizedSet(mutableSetOf<String>())
        val c =
            TerminalController(
                engineFactory = { cols, rows -> ThreadRecordingEngine(FakeTerminalEngine(cols, rows), threads) },
                theme = FakeTerminalEngine.DEFAULT_THEME,
                frameClock = { kotlinx.coroutines.delay(1).let { System.nanoTime() } },
                engineDispatcher = executor.asCoroutineDispatcher(),
                resizeDebounceMs = 1,
            )
        val s = FakeStream()
        runBlocking {
            c.bind(s)
            repeat(20) { s.chunks.send("x$it\n".encodeToByteArray()) }
            // bind() is applied on the engine thread; a key queued before that is refused (no stream).
            withTimeout(5_000) { c.connected.first { it } }
            c.sendKey(KeyInput(Key.Character, "a"))
            c.onViewportChanged(phone)
            c.scrollBy(-1)
            c.setTheme(FakeTerminalEngine.DEFAULT_THEME.copy(foreground = 0xFF00FF00.toInt()))
            withTimeout(5_000) { while (s.writes.isEmpty() || c.publishedFrames == 0L) kotlinx.coroutines.delay(5) }
            c.close()
            kotlinx.coroutines.delay(50)
        }
        executor.shutdown()
        // Debug builds of kotlinx.coroutines append " @coroutine#N" to the thread name.
        assertEquals(setOf("engine-test"), threads.map { it.substringBefore(" @") }.toSet())
    }

    @Test
    fun `passive viewer never resizes, focuses or sends display facts`() =
        runTest {
            val c = controller()
            val s = FakeStream(TerminalSize(120, 40))
            c.bind(s, AttachMode.Passive)
            c.onViewportChanged(phone)
            c.setFocused(true)
            c.setVisible(true)
            c.setTheme(FakeTerminalEngine.DEFAULT_THEME.copy(background = 0xFF000000.toInt()))
            advanceUntilIdle()
            assertEquals(emptyList<String>(), s.calls.toList())
            assertEquals(TerminalSize(120, 40), c.gridSize.value)
            assertFalse(c.isOwner.value)
            c.close()
        }

    @Test
    fun `first keystroke takes ownership and resizes before writing`() =
        runTest {
            val c = controller()
            val s = FakeStream(TerminalSize(120, 40))
            c.bind(s)
            c.onViewportChanged(phone)
            c.setFocused(true)
            advanceUntilIdle()
            c.sendKey(KeyInput(Key.Character, "c", ctrl = true))
            advanceUntilIdle()
            assertEquals(listOf("resize 100x70", "focus true", "display", "write"), s.calls.toList())
            assertEquals(listOf(3), s.writes.single().map { it.toInt() })
            assertTrue(c.isOwner.value)
            assertEquals(TerminalSize(100, 70), c.gridSize.value)
            c.close()
        }

    @Test
    fun `fitToScreen takes ownership and sends the viewport size`() =
        runTest {
            val c = controller()
            val s = FakeStream(TerminalSize(120, 40))
            c.bind(s)
            c.onViewportChanged(phone)
            advanceUntilIdle()
            c.fitToScreen()
            advanceUntilIdle()
            assertEquals(listOf("resize 100x70", "display"), s.calls.toList())
            assertEquals(100, engine.cols)
            c.close()
        }

    @Test
    fun `owner resizes once after a fold animation settles`() =
        runTest {
            val c = controller()
            val s = FakeStream(TerminalSize(100, 70))
            c.bind(s, AttachMode.Owner)
            c.onViewportChanged(phone)
            advanceUntilIdle()
            s.calls.clear()
            // Unfold: 20 animation frames 16 ms apart, width growing to 2000 px.
            for (i in 1..20) {
                c.onViewportChanged(TerminalViewport(1000 + i * 50, 1400, 10f, 20f))
                advanceTimeBy(16)
            }
            assertEquals(emptyList<String>(), s.calls.toList())
            advanceTimeBy(200)
            assertEquals(listOf("resize 200x70"), s.calls.toList())
            assertEquals(200, engine.cols)
            c.close()
        }

    @Test
    fun `another viewer taking the size makes us passive until the next keystroke`() =
        runTest {
            val c = controller()
            val s = FakeStream(TerminalSize(100, 70))
            c.bind(s, AttachMode.Owner)
            c.onViewportChanged(phone)
            advanceUntilIdle()
            assertTrue(c.isOwner.value)
            s.size.value = TerminalSize(200, 50) // desktop typed
            advanceUntilIdle()
            assertFalse(c.isOwner.value)
            assertEquals(200, engine.cols)
            s.calls.clear()
            c.onViewportChanged(TerminalViewport(900, 1400, 10f, 20f)) // rotation: must not steal
            advanceUntilIdle()
            assertEquals(emptyList<String>(), s.calls.toList())
            c.sendText("x")
            advanceUntilIdle()
            assertEquals(listOf("resize 90x70", "display", "write"), s.calls.toList())
            c.close()
        }

    @Test
    fun `the core's previous size arriving after our resize does not drop ownership`() =
        runTest {
            val c = controller()
            val s = FakeStream(TerminalSize(200, 120)) // headless size from before our RESIZE
            c.onViewportChanged(phone)
            c.bind(s, AttachMode.Owner)
            advanceUntilIdle()
            assertTrue(c.isOwner.value)
            assertEquals(TerminalSize(100, 70), c.gridSize.value)
            s.size.value = TerminalSize(100, 70) // the core applied our size
            advanceUntilIdle()
            assertTrue(c.isOwner.value)
            s.size.value = TerminalSize(200, 50) // now the desktop takes it
            advanceUntilIdle()
            assertFalse(c.isOwner.value)
            assertEquals(200, engine.cols)
            c.close()
        }

    @Test
    fun `a tap takes the size back after another viewer took it`() =
        runTest {
            val c = controller()
            val s = FakeStream(TerminalSize(100, 70))
            c.bind(s, AttachMode.Owner)
            c.onViewportChanged(phone)
            advanceUntilIdle()
            s.size.value = TerminalSize(200, 50) // the desktop took the size
            advanceUntilIdle()
            assertFalse(c.isOwner.value)
            s.calls.clear()
            c.reclaimSize()
            advanceUntilIdle()
            assertTrue(c.isOwner.value)
            assertEquals(listOf("resize 100x70", "display"), s.calls.toList())
            assertEquals(TerminalSize(100, 70), c.gridSize.value)
            c.close()
        }

    @Test
    fun `a tap never resizes while we own the size, never owned it, or the link is down`() =
        runTest {
            val c = controller()
            val s = FakeStream(TerminalSize(100, 70))
            c.bind(s, AttachMode.Owner)
            c.onViewportChanged(phone)
            advanceUntilIdle()
            s.calls.clear()
            c.reclaimSize() // already the owner
            advanceUntilIdle()
            assertEquals(emptyList<String>(), s.calls.toList())

            val passive = FakeStream(TerminalSize(200, 50))
            c.bind(passive, AttachMode.Passive) // never owned: a tap is not a keystroke
            advanceUntilIdle()
            c.reclaimSize()
            advanceUntilIdle()
            assertFalse(c.isOwner.value)
            assertEquals(emptyList<String>(), passive.calls.toList())

            val dropped = FakeStream(TerminalSize(100, 70))
            c.bind(dropped, AttachMode.Owner)
            advanceUntilIdle()
            dropped.size.value = TerminalSize(200, 50)
            advanceUntilIdle()
            dropped.connected.value = false
            advanceUntilIdle()
            dropped.calls.clear()
            c.reclaimSize() // the reconnect takes the size again, not the tap
            advanceUntilIdle()
            assertFalse(c.isOwner.value)
            assertEquals(emptyList<String>(), dropped.calls.toList())
            c.close()
        }

    @Test
    fun `coming back to the screen takes the size back after another viewer took it`() =
        runTest {
            val c = controller()
            val s = FakeStream(TerminalSize(100, 70))
            c.bind(s, AttachMode.Owner)
            c.onViewportChanged(phone)
            advanceUntilIdle()
            c.setVisible(false)
            advanceUntilIdle()
            s.size.value = TerminalSize(200, 50) // the desktop took it while we were away
            advanceUntilIdle()
            assertFalse(c.isOwner.value)
            s.calls.clear()
            c.setVisible(true)
            advanceUntilIdle()
            assertTrue(c.isOwner.value)
            assertEquals(listOf("resize 100x70", "display"), s.calls.toList())
            c.close()
        }

    @Test
    fun `coming back to the screen as the owner sends no resize`() =
        runTest {
            val c = controller()
            val s = FakeStream(TerminalSize(100, 70))
            c.bind(s, AttachMode.Owner)
            c.onViewportChanged(phone)
            advanceUntilIdle()
            c.setVisible(false)
            advanceUntilIdle()
            s.calls.clear()
            c.setVisible(true)
            advanceUntilIdle()
            assertEquals(emptyList<String>(), s.calls.filter { it.startsWith("resize") })
            c.close()
        }

    @Test
    fun `a configuration change does not take the size back from another viewer`() =
        runTest {
            val c = controller()
            val s = FakeStream(TerminalSize(100, 70))
            c.bind(s, AttachMode.Owner)
            c.onViewportChanged(phone)
            advanceUntilIdle()
            s.size.value = TerminalSize(200, 50) // the desktop took it while the phone shows the terminal
            advanceUntilIdle()
            assertFalse(c.isOwner.value)
            s.calls.clear()
            c.setVisible(false, configurationChange = true) // rotation, fold: the activity is recreated
            advanceUntilIdle()
            c.setVisible(true)
            advanceUntilIdle()
            assertFalse(c.isOwner.value)
            assertEquals(emptyList<String>(), s.calls.filter { it.startsWith("resize") })
            c.close()
        }

    @Test
    fun `a real return after a configuration change still takes the size back`() =
        runTest {
            val c = controller()
            val s = FakeStream(TerminalSize(100, 70))
            c.bind(s, AttachMode.Owner)
            c.onViewportChanged(phone)
            advanceUntilIdle()
            s.size.value = TerminalSize(200, 50)
            advanceUntilIdle()
            c.setVisible(false, configurationChange = true)
            c.setVisible(true)
            c.setVisible(false) // then the app goes to the background
            advanceUntilIdle()
            s.calls.clear()
            c.setVisible(true)
            advanceUntilIdle()
            assertTrue(c.isOwner.value)
            assertEquals(listOf("resize 100x70", "display"), s.calls.toList())
            c.close()
        }

    @Test
    fun `frames resume after a configuration change`() =
        runTest {
            val c = controller()
            val s = FakeStream()
            c.bind(s)
            c.setVisible(false, configurationChange = true)
            advanceUntilIdle()
            val before = c.publishedFrames
            s.chunks.send("rotating".encodeToByteArray())
            advanceUntilIdle()
            clock.tick()
            advanceUntilIdle()
            assertEquals(before, c.publishedFrames)
            c.setVisible(true)
            advanceUntilIdle()
            clock.tick()
            advanceUntilIdle()
            assertEquals("rotating", frame(c).line(0))
            c.close()
        }

    @Test
    fun `focus follows visibility and view focus while owner`() =
        runTest {
            val c = controller()
            val s = FakeStream()
            c.bind(s, AttachMode.Owner)
            advanceUntilIdle()
            s.calls.clear()
            c.setFocused(true)
            advanceUntilIdle()
            c.setVisible(false) // app backgrounded / screen off
            advanceUntilIdle()
            c.setVisible(true)
            advanceUntilIdle()
            c.setFocused(false) // another pane focused
            advanceUntilIdle()
            assertEquals(listOf("focus true", "focus false", "focus true", "focus false"), s.calls.toList())
            c.close()
        }

    @Test
    fun `frames are not published while invisible`() =
        runTest {
            val c = controller()
            val s = FakeStream()
            c.bind(s)
            c.setVisible(false)
            advanceUntilIdle()
            val before = c.publishedFrames
            s.chunks.send("hidden".encodeToByteArray())
            advanceUntilIdle()
            clock.tick()
            advanceUntilIdle()
            assertEquals(before, c.publishedFrames)
            c.setVisible(true)
            advanceUntilIdle()
            clock.tick()
            advanceUntilIdle()
            assertEquals("hidden", frame(c).line(0))
            c.close()
        }

    @Test
    fun `input failure is surfaced and not retried`() =
        runTest {
            val c = controller()
            val s = FakeStream()
            s.writeResult = AppResult.Failure(AppError.ConnectionLost)
            c.bind(s, AttachMode.Owner)
            val failures = mutableListOf<AppError>()
            val job = launch { c.failures.collect { failures += it } }
            advanceUntilIdle()
            c.sendText("ls\n")
            advanceUntilIdle()
            assertEquals(1, s.writes.size)
            assertEquals(listOf<AppError>(AppError.ConnectionLost), failures)
            job.cancel()
            c.close()
        }

    @Test
    fun `scrolling a program with mouse tracking writes its wheel events in one write`() =
        runTest {
            val c = controller()
            val s = FakeStream()
            c.bind(s, AttachMode.Owner)
            advanceUntilIdle()
            s.chunks.send("\u001b[?1049h\u001b[?1000h\u001b[?1006h".encodeToByteArray())
            advanceUntilIdle()
            assertTrue(c.scrollProgram(-3, col = 0, row = 0))
            advanceUntilIdle()
            assertEquals(listOf("\u001b[<64;1;1M".repeat(3)), s.writes.map { it.decodeToString() })
            c.close()
        }

    @Test
    fun `scrolling the program writes nothing on the main screen`() =
        runTest {
            val c = controller()
            val s = FakeStream()
            c.bind(s, AttachMode.Owner)
            advanceUntilIdle()
            c.scrollProgram(-3, col = 0, row = 0)
            advanceUntilIdle()
            assertTrue(s.writes.isEmpty())
            c.close()
        }

    @Test
    fun `sticky ctrl applies to the next IME character once`() =
        runTest {
            val c = controller()
            val s = FakeStream()
            c.bind(s, AttachMode.Owner)
            advanceUntilIdle()
            c.toggleStickyCtrl()
            assertTrue(c.stickyCtrl.value)
            c.sendText("c")
            c.sendText("c")
            advanceUntilIdle()
            assertFalse(c.stickyCtrl.value)
            assertEquals(listOf("\u0003", "c"), s.writes.map { it.decodeToString() })
            c.close()
        }

    @Test
    fun `a reconnect of a passive bind stays passive until the user interacts again`() =
        runTest {
            val c = controller()
            val s = FakeStream(TerminalSize(100, 70))
            c.bind(s, AttachMode.Passive)
            c.onViewportChanged(phone)
            advanceUntilIdle()
            c.fitToScreen()
            advanceUntilIdle()
            assertTrue(c.isOwner.value)
            assertTrue(c.connected.value)
            s.connected.value = false
            advanceUntilIdle()
            assertFalse(c.connected.value)
            assertFalse(c.isOwner.value)
            s.connected.value = true
            s.chunks.send("\u001b[2J\u001b[Hfresh paint".encodeToByteArray())
            s.calls.clear()
            c.onViewportChanged(TerminalViewport(900, 1400, 10f, 20f))
            advanceUntilIdle()
            clock.tick()
            advanceUntilIdle()
            assertEquals(emptyList<String>(), s.calls.toList())
            assertEquals("fresh paint", frame(c).line(0))
            c.close()
        }

    @Test
    fun `an owner bind takes the size again when the link is back`() =
        runTest {
            val c = controller()
            val s = FakeStream(TerminalSize(100, 70))
            c.bind(s, AttachMode.Owner)
            c.onViewportChanged(phone)
            c.setFocused(true)
            advanceUntilIdle()
            s.connected.value = false
            advanceUntilIdle()
            assertFalse(c.isOwner.value)
            // While the link was down nobody led, so the core went back to the headless size.
            s.size.value = TerminalSize(200, 120)
            advanceUntilIdle()
            s.calls.clear()
            s.connected.value = true
            advanceUntilIdle()
            assertTrue(c.isOwner.value)
            assertEquals(listOf("resize 100x70", "focus true", "display"), s.calls.toList())
            assertEquals(TerminalSize(100, 70), c.gridSize.value)
            c.close()
        }

    @Test
    fun `an owner bind reconnected in the background takes the size when visible again`() =
        runTest {
            val c = controller()
            val s = FakeStream(TerminalSize(100, 70))
            c.bind(s, AttachMode.Owner)
            c.onViewportChanged(phone)
            advanceUntilIdle()
            c.setVisible(false)
            s.connected.value = false
            advanceUntilIdle()
            s.calls.clear()
            s.connected.value = true
            advanceUntilIdle()
            assertEquals(emptyList<String>(), s.calls.toList())
            c.setVisible(true)
            advanceUntilIdle()
            assertEquals(listOf("resize 100x70", "display"), s.calls.toList())
            c.close()
        }

    @Test
    fun `preferredSize is the viewport grid once laid out`() =
        runTest {
            val c = controller()
            advanceUntilIdle()
            assertEquals(TerminalSize(80, 24), c.preferredSize())
            c.onViewportChanged(phone)
            assertEquals(TerminalSize(100, 70), c.preferredSize())
            c.close()
        }

    @Test
    fun `observe mode refuses input`() =
        runTest {
            val c = controller()
            val s = FakeStream()
            c.bind(s, AttachMode.Observe)
            advanceUntilIdle()
            c.sendText("x")
            c.fitToScreen()
            advanceUntilIdle()
            assertTrue(c.isReadOnly.value)
            assertEquals(emptyList<String>(), s.calls.toList())
            c.close()
        }

    @Test
    fun `close releases the engine and stops collecting`() =
        runTest {
            val c = controller()
            val s = FakeStream()
            c.bind(s)
            s.chunks.send("a".encodeToByteArray())
            advanceUntilIdle()
            c.close()
            advanceUntilIdle()
            assertTrue(engine.isClosed)
            s.chunks.send("b".encodeToByteArray())
            advanceUntilIdle()
            assertEquals(1L, c.bytesFed)
            assertFalse(c.sendText("x"))
        }

    @Test
    fun `title and cwd events become state, bell and clipboard become events`() =
        runTest {
            val c = controller()
            val s = FakeStream()
            c.bind(s)
            val events = mutableListOf<Any>()
            val job = launch { c.events.collect { events += it } }
            s.chunks.send(
                "\u001b]2;build\u0007\u001b]7;file://host/tmp\u0007\u0007\u001b]52;c;aGk=\u0007".encodeToByteArray(),
            )
            advanceUntilIdle()
            clock.tick()
            advanceUntilIdle()
            assertEquals("build", c.title.value)
            assertEquals("file://host/tmp", c.workingDirectory.value)
            assertEquals(2, events.size)
            assertEquals("ClipboardWrite(text=hi)", events[1].toString())
            job.cancel()
            c.close()
        }
}

/** Records the thread of every engine call. */
private class ThreadRecordingEngine(
    private val delegate: TerminalEngine,
    private val threads: MutableSet<String>,
) : TerminalEngine by delegate {
    private fun rec() {
        threads += Thread.currentThread().name
    }

    override fun feed(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) = rec().also { delegate.feed(bytes, offset, length) }

    override fun resize(
        cols: Int,
        rows: Int,
    ) = rec().also { delegate.resize(cols, rows) }

    override fun setTheme(theme: com.mtajchert.flocket.core.terminal.engine.TerminalTheme) =
        rec().also {
            delegate.setTheme(theme)
        }

    override fun snapshot(target: ScreenSnapshot?) = rec().let { delegate.snapshot(target) }

    override fun scrollViewport(deltaRows: Int) = rec().also { delegate.scrollViewport(deltaRows) }

    override fun encodeKey(event: KeyInput) = rec().let { delegate.encodeKey(event) }

    override fun drainEvents() = rec().let { delegate.drainEvents() }

    override fun close() = rec().also { delegate.close() }
}
