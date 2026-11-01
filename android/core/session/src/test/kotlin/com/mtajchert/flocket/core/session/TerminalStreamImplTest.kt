package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.logging.Logger
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.protocol.AttachChannel
import com.mtajchert.flocket.core.protocol.ChannelEnd
import com.mtajchert.flocket.core.protocol.dto.DisplayDto
import com.mtajchert.flocket.core.protocol.dto.TerminalDto
import com.mtajchert.flocket.core.protocol.testing.FakeCore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalStreamImplTest {
    private class StubChannel : AttachChannel {
        override val channel = 7
        override val terminal: TerminalDto = TerminalDto.parse(FakeCore.terminal("t1"))
        override val output: Flow<ByteArray> = emptyFlow()
        override val closeReason = MutableStateFlow<ChannelEnd?>(null)
        var closes = 0

        override suspend fun send(bytes: ByteArray) = Unit

        override suspend fun resize(
            cols: Int,
            rows: Int,
        ) = Unit

        override suspend fun focus(focused: Boolean) = Unit

        override suspend fun facts(display: DisplayDto) = Unit

        override suspend fun close() {
            closes++
            closeReason.value = ChannelEnd.LocalDetach
        }
    }

    @Test
    fun `an ended handle refuses a late channel and never reports connected again`() =
        runTest {
            val finished = mutableListOf<TerminalStreamImpl>()
            val host =
                object : StreamHost {
                    override fun reattachSoon(stream: TerminalStreamImpl) = Unit

                    override fun onFinished(stream: TerminalStreamImpl) {
                        finished += stream
                    }
                }
            val s =
                TerminalStreamImpl(
                    TerminalAddress("t1"),
                    AttachMode.Passive,
                    0,
                    80,
                    24,
                    null,
                    backgroundScope,
                    host,
                    Logger.None,
                )
            val first = StubChannel()
            val a = s.install(1, first, active = false, cols = 80, rows = 24, display = null)
            assertTrue(a != null && s.connected.value)

            s.finish(StreamEnd.Detached)
            runCurrent()
            assertEquals(1, first.closes)
            assertFalse(s.connected.value)

            // A re-attach that raced the detach must not resurrect the handle.
            assertNull(s.install(2, StubChannel(), active = false, cols = 80, rows = 24, display = null))
            assertFalse(s.connected.value)
            assertEquals(StreamEnd.Detached, s.end.value)
            assertEquals(listOf(s), finished)
        }
}
