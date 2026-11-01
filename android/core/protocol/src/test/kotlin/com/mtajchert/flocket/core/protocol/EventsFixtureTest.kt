package com.mtajchert.flocket.core.protocol

import app.cash.turbine.test
import com.mtajchert.flocket.core.protocol.testing.FakeCore
import com.mtajchert.flocket.core.protocol.testing.FakeCoreConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EventsFixtureTest {
    @Test
    fun `recorded events and missed excerpt parse in order with the fresh snapshot`() =
        runTest {
            val core = FakeCore(backgroundScope, FakeCoreConfig(streamChunk = 4096))
            val conn = CoreConnection.open(core.client, backgroundScope)
            val sub = conn.subscribeEvents()
            val lines = File(Fixtures.dir, "events-missed.excerpt.jsonl").readText()
            sub.notifications.test {
                core.streamOutput(sub.channel, lines.toByteArray())
                assertEquals(155L, (awaitItem() as CoreNotification.Event).seq)
                assertEquals(156L, (awaitItem() as CoreNotification.Event).seq)
                val missed = awaitItem() as CoreNotification.Missed
                assertEquals(2134L, missed.seq)
                assertTrue(missed.terminals.isNotEmpty())
                // Unknown fields survive in the raw object.
                assertTrue(missed.terminals[0].raw.containsKey("die_with_parent"))
                assertEquals(2135L, (awaitItem() as CoreNotification.Event).seq)
                assertEquals(2136L, (awaitItem() as CoreNotification.Event).seq)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `recorded frames of a never credited event channel stop at the window size`() {
        val frames = Fixtures.lines("events-missed.frames.jsonl").take(30)
        assertTrue(frames.all { it["channel"].toString() == "1" })
    }
}
