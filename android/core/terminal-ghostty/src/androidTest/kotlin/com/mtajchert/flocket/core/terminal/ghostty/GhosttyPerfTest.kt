package com.mtajchert.flocket.core.terminal.ghostty

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.flocket.core.terminal.engine.ScreenSnapshot
import com.mtajchert.flocket.core.terminal.engine.TerminalTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Snapshot / feed cost benchmarks. Numbers are logged with tag GHOSTTY_PERF (see
 * docs/TERMINAL_ENGINE.md for recorded results); the assertions are loose smoke bounds only.
 */
@RunWith(AndroidJUnit4::class)
class GhosttyPerfTest {
    private val theme =
        TerminalTheme(
            foreground = 0xFFE0E0E0.toInt(),
            background = 0xFF101010.toInt(),
            cursor = 0xFFFFFFFF.toInt(),
            palette = IntArray(16) { 0xFF000000.toInt() or (it * 0x111111) },
        )

    /** One full screen of colored text: every row dirty, most cells styled. */
    private fun screenful(
        cols: Int,
        rows: Int,
    ): ByteArray {
        val sb = StringBuilder("\u001b[H")
        for (r in 0 until rows) {
            sb.append("\u001b[").append(31 + r % 7).append('m')
            if (r % 3 == 0) sb.append("\u001b[1m")
            for (c in 0 until cols) sb.append(('a' + (r + c) % 26))
            sb.append("\u001b[0m")
            if (r < rows - 1) sb.append("\r\n")
        }
        return sb.toString().encodeToByteArray()
    }

    private fun medianSnapshotMs(
        cols: Int,
        rows: Int,
        iterations: Int = 100,
    ): Double {
        val engine = GhosttyTerminalEngine(cols, rows)
        try {
            engine.setTheme(theme)
            val screen = screenful(cols, rows)
            val snap = ScreenSnapshot(cols, rows)
            engine.snapshot(snap)
            repeat(10) {
                engine.feed(screen)
                engine.snapshot(snap)
            } // warm-up
            val times = DoubleArray(iterations)
            for (i in 0 until iterations) {
                engine.feed(screen) // rewrites every row: all rows dirty
                val t0 = System.nanoTime()
                engine.snapshot(snap)
                times[i] = (System.nanoTime() - t0) / 1e6
                assertTrue(snap.dirtyRows.all { it })
            }
            times.sort()
            return times[iterations / 2]
        } finally {
            engine.close()
        }
    }

    @Test
    fun fullyDirtySnapshotCost() {
        val tablet = medianSnapshotMs(200, 60)
        val phone = medianSnapshotMs(120, 40)
        Log.i(TAG, "full-dirty snapshot median: 200x60 = %.3f ms, 120x40 = %.3f ms".format(tablet, phone))
        assertTrue("200x60 snapshot too slow: $tablet ms", tablet < 50)
    }

    @Test
    fun oneMegabyteSgrFlood() {
        val line = StringBuilder()
        var i = 0
        val sb = StringBuilder()
        while (sb.length < 1 shl 20) {
            line.setLength(0)
            line
                .append("\u001b[")
                .append(31 + i % 7)
                .append(";")
                .append(if (i % 2 == 0) 1 else 22)
                .append('m')
            repeat(38) { line.append("y ") }
            line
                .append("\u001b[0m\u001b[4")
                .append(i % 8)
                .append("m ")
                .append(i)
                .append("\u001b[0m\r\n")
            sb.append(line)
            i++
        }
        val flood = sb.toString().encodeToByteArray()
        val engine = GhosttyTerminalEngine(120, 40)
        try {
            engine.setTheme(theme)
            val snap = ScreenSnapshot(120, 40)
            engine.feed(flood, 0, 64 * 1024) // warm-up

            fun run(snapshotPerRead: Boolean): Double {
                val runs = DoubleArray(5)
                for (r in runs.indices) {
                    val t0 = System.nanoTime()
                    var off = 0
                    while (off < flood.size) { // 16 KB reads, like the transport
                        val n = minOf(16 * 1024, flood.size - off)
                        engine.feed(flood, off, n)
                        if (snapshotPerRead) engine.snapshot(snap)
                        off += n
                    }
                    engine.snapshot(snap)
                    runs[r] = (System.nanoTime() - t0) / 1e6
                }
                runs.sort()
                return runs[runs.size / 2]
            }
            val ms = run(snapshotPerRead = false)
            val framed = run(snapshotPerRead = true)
            Log.i(
                TAG,
                "1 MiB SGR flood (${flood.size} B, 120x40): feed median %.1f ms (%.0f MB/s), with snapshot per 16 KB %.1f ms"
                    .format(ms, flood.size / 1e3 / ms, framed),
            )
            assertTrue("flood too slow: $ms ms", ms < 5000)
        } finally {
            engine.close()
        }
    }

    private companion object {
        const val TAG = "GHOSTTY_PERF"
    }
}
