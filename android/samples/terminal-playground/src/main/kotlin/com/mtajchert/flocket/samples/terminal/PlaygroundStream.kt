@file:Suppress("ktlint:standard:max-line-length") // Escape-sequence fixtures read best on one line.

package com.mtajchert.flocket.samples.terminal

import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.session.DisplayFacts
import com.mtajchert.flocket.core.session.StreamEnd
import com.mtajchert.flocket.core.session.TerminalSize
import com.mtajchert.flocket.core.session.TerminalStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

enum class PlaygroundMode { Echo, Colors, Htop, Flood, Scrollback }

/**
 * In-process [TerminalStream] standing in for herdr-core. The bounded output channel mimics the
 * core's credit window: a slow consumer suspends the producer instead of buffering.
 */
class PlaygroundStream(
    private val scope: CoroutineScope,
    initial: TerminalSize,
) : TerminalStream {
    override val address = TerminalAddress("playground")
    override val size = MutableStateFlow(initial)
    override val connected = MutableStateFlow(true)
    override val end = MutableStateFlow<StreamEnd?>(null)
    private val out = Channel<ByteArray>(capacity = 4)
    override val output = out.receiveAsFlow()

    /** Bytes produced so far (for the throughput readout). */
    val produced = MutableStateFlow(0L)
    private var job: Job? = null
    private val line = StringBuilder()

    fun start(mode: PlaygroundMode) {
        job?.cancel()
        job =
            scope.launch(Dispatchers.Default) {
                emit("\u001b[0m\u001b[?25h\u001b[2J\u001b[H")
                when (mode) {
                    PlaygroundMode.Echo -> emit(BANNER + PROMPT)
                    PlaygroundMode.Colors -> emit(colorDemo() + PROMPT)
                    PlaygroundMode.Htop -> htop()
                    PlaygroundMode.Flood -> flood()
                    PlaygroundMode.Scrollback -> scrollback()
                }
            }
    }

    private suspend fun emit(text: String) = emit(text.encodeToByteArray())

    private suspend fun emit(bytes: ByteArray) {
        out.send(bytes)
        produced.value += bytes.size
    }

    private suspend fun htop() {
        emit("\u001b[?25l")
        var t = 0.0
        val sb = StringBuilder()
        while (scope.isActive) {
            val (cols, rows) = size.value
            sb.setLength(0)
            sb
                .append(
                    "\u001b[H\u001b[1;37;44m PID  USER   CPU%  MEM%  COMMAND",
                ).append(" ".repeat((cols - 32).coerceAtLeast(0)))
                .append("\u001b[0m\r\n")
            for (r in 1 until rows) {
                val load = (sin(t + r * 0.37) + 1) / 2
                val bar = ((cols - 30).coerceAtLeast(4) * load).toInt()
                val color =
                    if (load > 0.8) {
                        31
                    } else if (load > 0.5) {
                        33
                    } else {
                        32
                    }
                sb
                    .append(
                        "\u001b[2K",
                    ).append(String.format("%5d \u001b[36mmtaj\u001b[0m  %5.1f  ", 1000 + r, load * 100))
                sb
                    .append("\u001b[")
                    .append(color)
                    .append('m')
                    .append("█".repeat(bar))
                    .append("▌")
                sb.append("\u001b[0m")
                if (r < rows - 1) sb.append("\r\n")
            }
            emit(sb.toString())
            t += 0.08
            delay(16)
        }
    }

    private suspend fun flood() {
        val chunks = List(8) { floodChunk(it) }
        var i = 0
        while (scope.isActive) emit(chunks[i++ and 7])
    }

    private suspend fun scrollback() {
        val sb = StringBuilder()
        for (i in 1..3000) {
            sb
                .append("\u001b[3")
                .append(1 + i % 7)
                .append('m')
                .append(String.format("%5d", i))
                .append("\u001b[0m ")
            sb.append("scrollback line with some text to fill the row ").append("•".repeat(i % 20)).append("\r\n")
            if (i % 200 == 0) {
                emit(sb.toString())
                sb.setLength(0)
            }
        }
        emit(sb.toString() + PROMPT)
    }

    override suspend fun write(bytes: ByteArray): AppResult<Unit> {
        val text = bytes.decodeToString()
        if (text.startsWith("\u001b")) return AppResult.Success(Unit) // arrows etc.: no line editing here
        val echo = StringBuilder()
        for (ch in text) {
            when (ch) {
                '\r' -> {
                    val cmd = line.toString().trim()
                    line.setLength(0)
                    echo.append("\r\n")
                    when (cmd) {
                        "" -> Unit
                        "clear" -> echo.append("\u001b[2J\u001b[H")
                        "help" -> echo.append(BANNER)
                        else -> echo.append("\u001b[2mecho:\u001b[0m ").append(cmd).append("\r\n")
                    }
                    echo.append(PROMPT)
                }

                '\u007f', '\b' -> {
                    if (line.isNotEmpty()) {
                        line.setLength(line.length - 1)
                        echo.append("\b \b")
                    }
                }

                '\u0003' -> {
                    line.setLength(0)
                    echo.append("^C\r\n").append(PROMPT)
                }

                else -> {
                    if (ch >= ' ') {
                        line.append(ch)
                        echo.append(ch)
                    }
                }
            }
        }
        if (echo.isNotEmpty()) emit(echo.toString())
        return AppResult.Success(Unit)
    }

    override fun resize(
        cols: Int,
        rows: Int,
    ) {
        size.value = TerminalSize(cols, rows)
    }

    override fun focus(focused: Boolean) = Unit

    override fun updateDisplay(facts: DisplayFacts) = Unit

    override suspend fun detach() {
        job?.cancel()
        end.value = StreamEnd.Detached
    }

    companion object {
        const val PROMPT = "\u001b[32m❯\u001b[0m "
        const val BANNER = "\u001b[1mFlocket terminal playground\u001b[0m (Ghostty engine, fake stream). Type; 'clear', 'help'.\r\n"

        fun colorDemo(): String =
            buildString {
                append(
                    "\u001b[1mbold\u001b[0m \u001b[3mitalic\u001b[0m \u001b[4munderline\u001b[0m \u001b[9mstrike\u001b[0m \u001b[2mfaint\u001b[0m \u001b[7minverse\u001b[0m\r\n",
                )
                for (i in 0 until 16) append("\u001b[48;5;${i}m  ")
                append("\u001b[0m\r\n")
                for (row in 0 until 6) {
                    for (i in 0 until 36) append("\u001b[48;5;${16 + row * 36 + i}m ")
                    append("\u001b[0m\r\n")
                }
                for (i in 0 until 72) {
                    val r = i * 255 / 72
                    append("\u001b[48;2;$r;${80 + i};${255 - i * 3}m ")
                }
                append("\u001b[0m\r\n")
                append("wide: 你好世界 한글 日本語 | emoji 🎉🚀👍🏽 👨‍👩‍👧 | café ñ\r\n")
                append(
                    "╭──────────┬──────────╮\r\n│ \u001b[1mbox\u001b[0m      │ ▁▂▃▄▅▆▇█ │\r\n╰──────────┴──────────╯\r\n",
                )
                append("\u001b]2;Colors demo\u0007")
            }

        private fun floodChunk(seed: Int): ByteArray {
            val sb = StringBuilder(64 * 1024)
            var n = seed * 997
            val words =
                listOf(
                    "compile",
                    "kotlin",
                    "ghostty",
                    "render",
                    "frame",
                    "vsync",
                    "flocket",
                    "herdr",
                    "agent",
                    "terminal",
                )
            while (sb.length < 60 * 1024) {
                n = n * 1103515245 + 12345
                val c = (n ushr 16) and 0xFF
                sb
                    .append("\u001b[38;5;")
                    .append(c)
                    .append('m')
                    .append(String.format("%08x", n))
                    .append("\u001b[0m ")
                repeat(6) { k ->
                    val w = words[(c + k) % words.size]
                    when ((c + k) % 5) {
                        0 -> {
                            sb.append("\u001b[1m").append(w).append("\u001b[22m ")
                        }

                        1 -> {
                            sb.append("\u001b[3;36m").append(w).append("\u001b[0m ")
                        }

                        2 -> {
                            sb
                                .append("\u001b[48;2;")
                                .append(c)
                                .append(";40;80m")
                                .append(w)
                                .append("\u001b[0m ")
                        }

                        else -> {
                            sb.append(w).append(' ')
                        }
                    }
                }
                sb.append("\r\n")
            }
            return sb.toString().encodeToByteArray()
        }
    }
}
