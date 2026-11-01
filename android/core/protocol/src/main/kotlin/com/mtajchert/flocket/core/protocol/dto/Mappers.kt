package com.mtajchert.flocket.core.protocol.dto

import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.BlockKind
import com.mtajchert.flocket.core.model.CoreEndpoint
import com.mtajchert.flocket.core.model.CoreInfo
import com.mtajchert.flocket.core.model.ForegroundProcess
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.MachineInfo
import com.mtajchert.flocket.core.model.MachineState
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalRecord
import com.mtajchert.flocket.core.model.TerminalRef
import com.mtajchert.flocket.core.model.TerminalTemplate

fun HelloResult.toCoreInfo(): CoreInfo =
    CoreInfo(
        id = core.id,
        name = core.name,
        version = version,
        generation = generation,
        features = features.toSet(),
        methods = methods.toSet(),
    )

/** Missing/null status is [AgentStatus.Unknown], never "idle" (SPEC gotcha #12). */
fun StatusDto?.toAgentStatus(): AgentStatus =
    if (this == null) {
        AgentStatus.Unknown
    } else {
        AgentStatus.Reported(
            state = AgentState.fromWire(state),
            rawState = state,
            kind = kind?.let(BlockKind::fromWire),
            progress = progress,
            app = app?.takeIf { it.isNotEmpty() },
            title = title?.takeIf { it.isNotEmpty() },
            message = msg?.takeIf { it.isNotEmpty() },
        )
    }

/** `finished` => Finished; `live` without a pid => Waiting (kept terminal, SPEC gotcha #14); else Running. */
fun TerminalDto.toLiveness(): Liveness =
    when {
        state == "finished" -> Liveness.Finished(reason, exitCode, signal, finishedMs)
        pid == null -> Liveness.Waiting(reason)
        else -> Liveness.Running
    }

fun TerminalDto.toRecord(
    endpoint: CoreEndpoint,
    statusChangedAtMs: Long? = statusMs,
): TerminalRecord =
    TerminalRecord(
        ref = TerminalRef(endpoint, TerminalAddress(address)),
        name = name,
        labels = labels,
        argv = argv,
        cwd = cwd,
        liveness = toLiveness(),
        status = status.toAgentStatus(),
        turn = turn,
        title = title,
        cols = cols,
        rows = rows,
        createdAtMs = createdMs,
        statusChangedAtMs = statusChangedAtMs,
        foreground = foreground?.let { ForegroundProcess(it.pid, it.name, it.argv, it.cwd) },
        keep = keep,
        template = template,
        parent = parent,
    )

fun TemplateDto.toModel(): TerminalTemplate = TerminalTemplate(name, description, command, error)

fun MachineViewDto.toModel(): MachineInfo =
    MachineInfo(
        name = name,
        state = MachineState.fromWire(state),
        rawState = state,
        version = version,
        temporary = temporary,
        disconnectedSinceMs = sinceMs,
        why = why,
    )
