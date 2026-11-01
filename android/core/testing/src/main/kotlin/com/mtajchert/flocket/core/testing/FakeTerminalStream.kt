package com.mtajchert.flocket.core.testing

import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.session.DisplayFacts
import com.mtajchert.flocket.core.session.StreamEnd
import com.mtajchert.flocket.core.session.TerminalSize
import com.mtajchert.flocket.core.session.TerminalStream
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.Collections

/**
 * A [TerminalStream] you drive from a test: send output with [chunks], flip [connected], set [end].
 * Every control call is recorded in [calls] (`write`, `resize CxR`, `focus true`, `display`, `detach`).
 */
class FakeTerminalStream(
    override val address: TerminalAddress = TerminalAddress("t1"),
    initial: TerminalSize = TerminalSize(80, 24),
) : TerminalStream {
    override val size = MutableStateFlow(initial)
    val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    override val output = chunks.receiveAsFlow()
    override val end = MutableStateFlow<StreamEnd?>(null)
    override val connected = MutableStateFlow(true)
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var writeResult: AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun write(bytes: ByteArray): AppResult<Unit> {
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
        if (end.value == null) end.value = StreamEnd.Detached
    }
}
