package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.CoreEndpoint
import com.mtajchert.flocket.core.model.ForegroundProcess
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalRecord
import com.mtajchert.flocket.core.protocol.CoreNotification
import com.mtajchert.flocket.core.protocol.dto.ForegroundDto
import com.mtajchert.flocket.core.protocol.dto.StatusDto
import com.mtajchert.flocket.core.protocol.dto.TerminalDto
import com.mtajchert.flocket.core.protocol.dto.toAgentStatus
import com.mtajchert.flocket.core.protocol.dto.toRecord
import com.mtajchert.flocket.core.protocol.rpc.ProtocolJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** What the reducer tracks for one event subscription: terminals in core order and the last applied seq. */
data class ReducerState(
    val terminals: List<TerminalRecord> = emptyList(),
    val lastSeq: Long = 0,
)

/** Side effect the session must perform after a notification. */
sealed interface ReduceEffect {
    /** Unexpected gap / heartbeat ahead / malformed event on the unfiltered stream: resubscribe (SPEC §8.3, §8.4). */
    data class Resync(
        val reason: String,
    ) : ReduceEffect

    /** `missed`: state was replaced from a fresh snapshot; refetch documents/machines (SPEC §8.5). */
    data object SnapshotReplaced : ReduceEffect

    /** A document changed (SPEC §9): refetch it. */
    data class DocChanged(
        val name: String,
        val machine: String?,
    ) : ReduceEffect
}

data class ReduceResult(
    val state: ReducerState,
    val effect: ReduceEffect? = null,
)

/**
 * Pure reducer for the unfiltered event stream (protocol/SPEC.md §8.2–§8.5). No I/O, no clock:
 * [nowMs] is passed in to stamp [TerminalRecord.statusChangedAtMs] when a status word changes.
 */
class EventReducer(
    private val endpoint: CoreEndpoint,
) {
    /** Replace the terminal map from a snapshot (subscribe reply or `missed`), keeping local status stamps. */
    fun snapshot(
        previous: List<TerminalRecord>,
        terminals: List<TerminalDto>,
        seq: Long,
    ): ReducerState {
        val old = previous.associateBy { it.address }
        return ReducerState(terminals.map { recordFrom(it, old[TerminalAddress(it.address)]) }, seq)
    }

    fun reduce(
        state: ReducerState,
        notification: CoreNotification,
        nowMs: Long,
    ): ReduceResult =
        when (notification) {
            is CoreNotification.Heartbeat -> {
                if (notification.seq > state.lastSeq) {
                    ReduceResult(state, ReduceEffect.Resync("heartbeat seq ${notification.seq} > ${state.lastSeq}"))
                } else {
                    ReduceResult(state)
                }
            }

            is CoreNotification.Missed -> {
                ReduceResult(
                    snapshot(state.terminals, notification.terminals, notification.seq),
                    ReduceEffect.SnapshotReplaced,
                )
            }

            is CoreNotification.Event -> {
                when {
                    notification.seq <= state.lastSeq -> {
                        ReduceResult(state)
                    }

                    notification.seq > state.lastSeq + 1 -> {
                        ReduceResult(state, ReduceEffect.Resync("gap: ${notification.seq} after ${state.lastSeq}"))
                    }

                    else -> {
                        try {
                            applyEvent(state, notification, nowMs)
                        } catch (e: IllegalArgumentException) {
                            // kotlinx.serialization errors are IllegalArgumentExceptions: malformed data = desync.
                            ReduceResult(
                                state,
                                ReduceEffect.Resync("malformed ${notification.type} event: ${e.message}"),
                            )
                        }
                    }
                }
            }
        }

    private fun applyEvent(
        state: ReducerState,
        e: CoreNotification.Event,
        nowMs: Long,
    ): ReduceResult {
        val next = state.copy(lastSeq = e.seq)
        val address = e.terminal?.let(::TerminalAddress)
        val d = e.data
        if (e.type == "doc") {
            val name = d.str("name") ?: return ReduceResult(next)
            return ReduceResult(next, ReduceEffect.DocChanged(name, e.machine))
        }
        address ?: return ReduceResult(next)
        val terminals = state.terminals
        val updated: List<TerminalRecord> =
            when (e.type) {
                "created", "restarted" -> {
                    upsert(terminals, recordFrom(TerminalDto.parse(d), terminals.find(address)))
                }

                "removed" -> {
                    terminals.filterNot { it.address == address }
                }

                "lost" -> {
                    terminals.update(
                        address,
                    ) { it.copy(liveness = Liveness.Waiting(d.str("reason")), foreground = null) }
                }

                "finished" -> {
                    terminals.update(address) {
                        it.copy(
                            liveness =
                                Liveness.Finished(
                                    d.str("reason"),
                                    d.int("exit_code"),
                                    d.int("signal"),
                                    d.long("finished_ms"),
                                ),
                            foreground = null,
                        )
                    }
                }

                "keep" -> {
                    terminals.update(address) { it.copy(keep = d.bool("keep") ?: it.keep) }
                }

                "status" -> {
                    val status =
                        (d["status"] as? JsonObject)
                            ?.let {
                                ProtocolJson.decodeFromJsonElement(StatusDto.serializer(), it)
                            }.toAgentStatus()
                    terminals.update(address) {
                        val stamp = if (status.word != it.status.word) nowMs else it.statusChangedAtMs
                        it.copy(status = status, statusChangedAtMs = stamp)
                    }
                }

                "turn" -> {
                    terminals.update(address) { it.copy(turn = d.long("turn") ?: it.turn) }
                }

                "title" -> {
                    terminals.update(address) { it.copy(title = d.str("title") ?: it.title) }
                }

                "labels" -> {
                    val labels =
                        (d["labels"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }
                            ?: throw IllegalArgumentException("labels event without labels")
                    val record = d["record"] as? JsonObject
                    if (terminals.find(address) == null && record != null) {
                        terminals + recordFrom(TerminalDto.parse(record), null).copy(labels = labels)
                    } else {
                        terminals.update(address) { it.copy(labels = labels) }
                    }
                }

                "size" -> {
                    terminals.update(
                        address,
                    ) { it.copy(cols = d.int("cols") ?: it.cols, rows = d.int("rows") ?: it.rows) }
                }

                "foreground" -> {
                    val fg =
                        if (d.isEmpty()) {
                            null
                        } else {
                            ProtocolJson.decodeFromJsonElement(ForegroundDto.serializer(), d).let {
                                ForegroundProcess(it.pid, it.name, it.argv, it.cwd)
                            }
                        }
                    terminals.update(address) { it.copy(foreground = fg) }
                }

                else -> {
                    terminals // additive future types: ignore
                }
            }
        return ReduceResult(next.copy(terminals = updated))
    }

    private fun recordFrom(
        dto: TerminalDto,
        previous: TerminalRecord?,
    ): TerminalRecord {
        val record = dto.toRecord(endpoint)
        if (record.statusChangedAtMs != null || previous == null) return record
        // Snapshots/created records may lack status_ms: keep our local stamp while the word is unchanged.
        return if (previous.status.word ==
            record.status.word
        ) {
            record.copy(statusChangedAtMs = previous.statusChangedAtMs)
        } else {
            record
        }
    }

    private fun upsert(
        terminals: List<TerminalRecord>,
        record: TerminalRecord,
    ): List<TerminalRecord> {
        val i = terminals.indexOfFirst { it.address == record.address }
        return if (i < 0) terminals + record else terminals.toMutableList().also { it[i] = record }
    }

    private fun List<TerminalRecord>.find(address: TerminalAddress) = firstOrNull { it.address == address }

    private inline fun List<TerminalRecord>.update(
        address: TerminalAddress,
        f: (TerminalRecord) -> TerminalRecord,
    ): List<TerminalRecord> = map { if (it.address == address) f(it) else it }

    private val AgentStatus.word: String? get() = (this as? AgentStatus.Reported)?.rawState

    private fun JsonObject.str(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull
}
