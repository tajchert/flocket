package com.mtajchert.flocket.core.data.internal

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.ConnectionManager
import com.mtajchert.flocket.core.data.HostOverview
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.CoreEndpoint
import com.mtajchert.flocket.core.model.CoreInfo
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalRecord
import com.mtajchert.flocket.core.model.TerminalRef
import com.mtajchert.flocket.core.session.CoreSession
import com.mtajchert.flocket.core.session.SessionState
import com.mtajchert.flocket.core.testing.FakeCoreSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMarksTest {
    private val hostId = HostId("h1")
    private val endpoint = CoreEndpoint(hostId)
    private val ref = TerminalRef(endpoint, TerminalAddress("t1"))

    private fun info(withLabel: Boolean = true) =
        CoreInfo(
            "c",
            "c",
            "1",
            1,
            emptySet(),
            if (withLabel) setOf(CoreInfo.TERMINAL_LABEL) else emptySet(),
        )

    private fun record(
        turn: Long,
        labels: Map<String, String> = emptyMap(),
        agent: Boolean = true,
    ) = TerminalRecord(
        ref = ref,
        labels = labels,
        liveness = Liveness.Running,
        status = if (agent) AgentStatus.Reported(AgentState.Idle, "idle") else AgentStatus.Unknown,
        turn = turn,
        cols = 80,
        rows = 24,
        createdAtMs = 0,
    )

    private class Manager(
        val session: CoreSession?,
    ) : ConnectionManager {
        override val overview: StateFlow<List<HostOverview>> = MutableStateFlow(emptyList())

        override fun session(hostId: HostId): CoreSession? = session

        override fun providePassword(
            hostId: HostId,
            password: CharArray,
        ) = Unit
    }

    private fun fixture(
        rec: TerminalRecord = record(turn = 3),
        connection: ConnectionState = ConnectionState.Online(info()),
        withLabel: Boolean = true,
    ): Pair<AgentMarksImpl, FakeCoreSession> {
        val session =
            FakeCoreSession(endpoint, SessionState(endpoint, connection, info(withLabel), listOf(rec)))
        session.labelsHandler = { _, _ -> AppResult.Success(rec) }
        return AgentMarksImpl(Manager(session)) to session
    }

    @Test
    fun `markSeen writes the current turn when the label is missing`() =
        runTest {
            val (marks, session) = fixture()
            assertTrue(marks.markSeen(ref) is AppResult.Success)
            assertEquals(listOf(TerminalAddress("t1") to mapOf("herdr.seen" to "3")), session.labelWrites)
        }

    @Test
    fun `markSeen writes when the label is lower and skips when caught up`() =
        runTest {
            val (lower, s1) = fixture(record(3, mapOf("herdr.seen" to "1")))
            lower.markSeen(ref)
            assertEquals(1, s1.labelWrites.size)
            val (equal, s2) = fixture(record(3, mapOf("herdr.seen" to "3")))
            equal.markSeen(ref)
            assertTrue(s2.labelWrites.isEmpty())
        }

    @Test
    fun `markSeen raises the label again after another client marked it unread`() =
        runTest {
            val (marks, session) = fixture()
            marks.markSeen(ref)
            session.update { it.copy(terminals = listOf(record(3, labels = mapOf("herdr.seen" to "2")))) }
            marks.markSeen(ref)
            assertEquals(2, session.labelWrites.size)
        }

    @Test
    fun `a failed markSeen is retried`() =
        runTest {
            val (marks, session) = fixture()
            session.labelsHandler = { _, _ -> AppResult.Failure(AppError.ConnectionLost) }
            assertTrue(marks.markSeen(ref) is AppResult.Failure)
            marks.markSeen(ref)
            assertEquals(2, session.labelWrites.size)
        }

    @Test
    fun `markSeen ignores plain shells`() =
        runTest {
            val (marks, session) = fixture(record(3, agent = false))
            assertTrue(marks.markSeen(ref) is AppResult.Success)
            assertTrue(session.labelWrites.isEmpty())
        }

    @Test
    fun `markUnread writes turn minus one and lets markSeen write again`() =
        runTest {
            val (marks, session) = fixture()
            marks.markSeen(ref)
            marks.markUnread(ref)
            marks.markSeen(ref)
            assertEquals(
                listOf("3", "2", "3"),
                session.labelWrites.map { it.second.getValue("herdr.seen") },
            )
        }

    @Test
    fun `markUnread at turn 0 is a no-op`() =
        runTest {
            val (marks, session) = fixture(record(0))
            assertTrue(marks.markUnread(ref) is AppResult.Success)
            assertTrue(session.labelWrites.isEmpty())
        }

    @Test
    fun `setPinned writes 1 and an empty value to unpin`() =
        runTest {
            val (marks, session) = fixture()
            marks.setPinned(ref, true)
            marks.setPinned(ref, false)
            assertEquals(
                listOf(mapOf("herdr.pinned" to "1"), mapOf("herdr.pinned" to "")),
                session.labelWrites.map { it.second },
            )
        }

    @Test
    fun `nothing is written unless the host is online`() =
        runTest {
            val (marks, session) = fixture(connection = ConnectionState.Reconnecting(1, AppError.ConnectionLost, 0))
            assertEquals(AppError.ConnectionLost, (marks.markSeen(ref) as AppResult.Failure).error)
            assertTrue(marks.setPinned(ref, true) is AppResult.Failure)
            assertTrue(session.labelWrites.isEmpty())
        }

    @Test
    fun `a core without terminal label is Unsupported and nothing is written`() =
        runTest {
            val (marks, session) = fixture(withLabel = false)
            assertFalse(marks.supportsLabels(hostId))
            assertEquals(
                AppError.Unsupported("terminal.label"),
                (marks.markSeen(ref) as AppResult.Failure).error,
            )
            assertTrue(session.labelWrites.isEmpty())
        }

    @Test
    fun `supportsLabels follows the core info and unknown hosts are unsupported`() {
        val (marks, _) = fixture()
        assertTrue(marks.supportsLabels(hostId))
        assertFalse(AgentMarksImpl(Manager(null)).supportsLabels(hostId))
    }
}
