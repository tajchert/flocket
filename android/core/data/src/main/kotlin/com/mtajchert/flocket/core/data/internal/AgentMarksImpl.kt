package com.mtajchert.flocket.core.data.internal

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.AgentMarks
import com.mtajchert.flocket.core.data.ConnectionManager
import com.mtajchert.flocket.core.model.CoreInfo
import com.mtajchert.flocket.core.model.HerdrLabels
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.TerminalRecord
import com.mtajchert.flocket.core.model.TerminalRef
import com.mtajchert.flocket.core.model.isAgent
import com.mtajchert.flocket.core.model.isOnline
import com.mtajchert.flocket.core.model.seenTurn
import com.mtajchert.flocket.core.session.CoreSession

internal class AgentMarksImpl(
    private val connections: ConnectionManager,
) : AgentMarks {
    override fun supportsLabels(hostId: HostId): Boolean =
        connections
            .session(hostId)
            ?.state
            ?.value
            ?.supportsLabels == true

    override suspend fun markSeen(ref: TerminalRef): AppResult<Unit> =
        withRecord(ref) { session, record ->
            val seen = record.seenTurn
            if (!record.isAgent || (seen != null && seen >= record.turn)) {
                return@withRecord AppResult.Success(Unit)
            }
            write(session, record, HerdrLabels.SEEN, record.turn.toString())
        }

    override suspend fun markUnread(ref: TerminalRef): AppResult<Unit> =
        withRecord(ref) { session, record ->
            if (!record.isAgent || record.turn <= 0) return@withRecord AppResult.Success(Unit)
            write(session, record, HerdrLabels.SEEN, (record.turn - 1).toString())
        }

    override suspend fun setPinned(
        ref: TerminalRef,
        pinned: Boolean,
    ): AppResult<Unit> =
        withRecord(ref) { session, record -> write(session, record, HerdrLabels.PINNED, if (pinned) "1" else "") }

    private suspend fun write(
        session: CoreSession,
        record: TerminalRecord,
        key: String,
        value: String,
    ): AppResult<Unit> =
        when (val r = session.setLabels(record.address, mapOf(key to value))) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> r
        }

    private suspend fun withRecord(
        ref: TerminalRef,
        block: suspend (CoreSession, TerminalRecord) -> AppResult<Unit>,
    ): AppResult<Unit> {
        val session = connections.session(ref.endpoint.hostId) ?: return AppResult.Failure(AppError.ConnectionLost)
        val state = session.state.value
        if (!state.connection.isOnline) return AppResult.Failure(AppError.ConnectionLost)
        if (!state.supportsLabels) return AppResult.Failure(AppError.Unsupported(CoreInfo.TERMINAL_LABEL))
        val record =
            state.terminal(ref.address)
                ?: return AppResult.Failure(AppError.InvalidInput("terminal", "unknown terminal"))
        return block(session, record)
    }
}
