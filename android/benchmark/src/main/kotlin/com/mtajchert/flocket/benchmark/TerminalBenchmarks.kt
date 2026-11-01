package com.mtajchert.flocket.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Terminal surface benchmarks against `:samples:terminal-playground` (install its
 * `benchmarkRelease` build first; see docs/TERMINAL_RENDERING.md). Emulator numbers are
 * indicative only.
 */
internal const val PLAYGROUND = "com.mtajchert.flocket.samples.terminal"

private fun MacrobenchmarkScope.startPlayground(mode: String) {
    startActivityAndWait(
        Intent()
            .setClassName(PLAYGROUND, "$PLAYGROUND.PlaygroundActivity")
            .putExtra("mode", mode)
            .putExtra("owner", true),
    )
    device.wait(Until.hasObject(By.desc("Terminal")), 5_000)
}

@RunWith(AndroidJUnit4::class)
class TerminalFloodBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    /** MBs of mixed SGR output for 5 s: the coalescer must keep frames inside the budget. */
    @Test
    fun flood() =
        rule.measureRepeated(
            packageName = PLAYGROUND,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Full(),
            startupMode = StartupMode.COLD,
            iterations = 3,
        ) {
            startPlayground("flood")
            Thread.sleep(5_000)
        }

    /** htop-like full-screen redraw at 60 Hz. */
    @Test
    fun fullScreenRedraw() =
        rule.measureRepeated(
            packageName = PLAYGROUND,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Full(),
            startupMode = StartupMode.COLD,
            iterations = 3,
        ) {
            startPlayground("htop")
            Thread.sleep(5_000)
        }
}

@RunWith(AndroidJUnit4::class)
class TerminalScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    /** Fling through 3000 lines of scrollback and back. */
    @Test
    fun flingScrollback() =
        rule.measureRepeated(
            packageName = PLAYGROUND,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Full(),
            startupMode = StartupMode.WARM,
            iterations = 3,
            setupBlock = {
                startPlayground("scrollback")
                Thread.sleep(1_500)
            },
        ) {
            val terminal = device.findObject(By.desc("Terminal"))
            terminal.setGestureMargin(device.displayWidth / 5)
            repeat(3) {
                terminal.fling(Direction.UP)
                device.waitForIdle()
            }
            repeat(3) {
                terminal.fling(Direction.DOWN)
                device.waitForIdle()
            }
        }
}
