package com.mtajchert.flocket.core.protocol

import com.mtajchert.flocket.core.protocol.dto.DocGetResult
import com.mtajchert.flocket.core.protocol.dto.DocSetResult
import com.mtajchert.flocket.core.protocol.dto.InterruptResult
import com.mtajchert.flocket.core.protocol.dto.MachineViewDto
import com.mtajchert.flocket.core.protocol.dto.ReadResult
import com.mtajchert.flocket.core.protocol.dto.RemovedResult
import com.mtajchert.flocket.core.protocol.dto.RunResult
import com.mtajchert.flocket.core.protocol.dto.SendResult
import com.mtajchert.flocket.core.protocol.dto.ServerStatusDto
import com.mtajchert.flocket.core.protocol.dto.TemplateDto
import com.mtajchert.flocket.core.protocol.dto.TerminalDto
import com.mtajchert.flocket.core.protocol.dto.TerminalListResult
import com.mtajchert.flocket.core.protocol.rpc.ProtocolJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Suggested timeouts (SPEC §11). Mutating calls are never auto-retried regardless. */
object CallTimeouts {
    val READ: Duration = 15.seconds
    val RUN: Duration = 30.seconds
    val KILL: Duration = 45.seconds

    /** `terminal.send`/`keys` wait for ~3 s of typing quiet; be generous. */
    val SEND: Duration = 60.seconds
}

// Typed helpers over the generic [CoreConnection.call]. Each throws what `call` throws.

private fun <T> JsonElement.decode(s: KSerializer<T>): T = ProtocolJson.decodeFromJsonElement(s, this)

private fun obj(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)

private fun strMap(m: Map<String, String>): JsonObject = JsonObject(m.mapValues { JsonPrimitive(it.value) })

suspend fun CoreConnection.serverStatus(timeout: Duration = CallTimeouts.READ): ServerStatusDto =
    call("server.status", timeout = timeout).decode(ServerStatusDto.serializer())

suspend fun CoreConnection.terminalList(
    all: Boolean = false,
    labels: Map<String, String> = emptyMap(),
    timeout: Duration = CallTimeouts.READ,
): TerminalListResult {
    val r =
        call(
            "terminal.list",
            obj {
                if (all) put("all", true)
                if (labels.isNotEmpty()) put("labels", strMap(labels))
            },
            timeout,
        ).jsonObject
    return TerminalListResult(
        terminals = r["terminals"]?.jsonArray.orEmpty().map(TerminalDto::parse),
        hiddenFinished = (r["hidden_finished"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
        machines = r["machines"]?.let { m -> m.jsonArray.map { it.decode(MachineViewDto.serializer()) } },
    )
}

suspend fun CoreConnection.terminalGet(
    terminal: String,
    timeout: Duration = CallTimeouts.READ,
): TerminalDto = TerminalDto.parse(call("terminal.get", obj { put("terminal", terminal) }, timeout))

/** Parameters of `terminal.run` (SPEC §4.4). Always pass [cwd]; pass TERM/COLORTERM in [env]. */
data class RunParams(
    val argv: List<String> = emptyList(),
    val line: String? = null,
    val name: String? = null,
    val cwd: String,
    val on: String? = null,
    val env: Map<String, String> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
    val keep: Boolean = false,
)

suspend fun CoreConnection.terminalRun(
    p: RunParams,
    timeout: Duration = CallTimeouts.RUN,
): RunResult {
    val r =
        call(
            "terminal.run",
            obj {
                if (p.argv.isNotEmpty()) put("argv", buildJsonArray { p.argv.forEach { add(JsonPrimitive(it)) } })
                p.line?.let { put("line", it) }
                p.name?.let { put("name", it) }
                put("cwd", p.cwd)
                p.on?.let { put("on", it) }
                if (p.env.isNotEmpty()) put("env", strMap(p.env))
                if (p.labels.isNotEmpty()) put("labels", strMap(p.labels))
                if (p.keep) put("keep", true)
            },
            timeout,
        ).jsonObject
    return RunResult(TerminalDto.parse(r), r["first_message"] as? JsonObject)
}

suspend fun CoreConnection.terminalKill(
    terminal: String,
    timeout: Duration = CallTimeouts.KILL,
): TerminalDto = TerminalDto.parse(call("terminal.kill", obj { put("terminal", terminal) }, timeout))

suspend fun CoreConnection.terminalRm(
    terminal: String,
    timeout: Duration = CallTimeouts.READ,
): String = call("terminal.rm", obj { put("terminal", terminal) }, timeout).decode(RemovedResult.serializer()).removed

suspend fun CoreConnection.terminalLabel(
    terminal: String,
    labels: Map<String, String>,
    timeout: Duration = CallTimeouts.READ,
): TerminalDto =
    TerminalDto.parse(
        call(
            "terminal.label",
            obj {
                put("terminal", terminal)
                put("labels", strMap(labels))
            },
            timeout,
        ),
    )

suspend fun CoreConnection.terminalSend(
    terminal: String,
    text: String,
    force: Boolean = false,
    timeout: Duration = CallTimeouts.SEND,
): SendResult =
    call(
        "terminal.send",
        obj {
            put("terminal", terminal)
            put("text", text)
            if (force) put("force", true)
        },
        timeout,
    ).decode(SendResult.serializer())

suspend fun CoreConnection.terminalKeys(
    terminal: String,
    keys: List<String>,
    timeout: Duration = CallTimeouts.SEND,
): SendResult =
    call(
        "terminal.keys",
        obj {
            put("terminal", terminal)
            put("keys", buildJsonArray { keys.forEach { add(JsonPrimitive(it)) } })
        },
        timeout,
    ).decode(SendResult.serializer())

suspend fun CoreConnection.terminalInterrupt(
    terminal: String,
    timeout: Duration = CallTimeouts.KILL,
): InterruptResult =
    call(
        "terminal.interrupt",
        obj {
            put("terminal", terminal)
        },
        timeout,
    ).decode(InterruptResult.serializer())

suspend fun CoreConnection.terminalRead(
    terminal: String,
    lines: Int = 0,
    timeout: Duration = CallTimeouts.READ,
): ReadResult =
    call(
        "terminal.read",
        obj {
            put("terminal", terminal)
            if (lines > 0) put("lines", lines)
        },
        timeout,
    ).decode(ReadResult.serializer())

suspend fun CoreConnection.templatesList(timeout: Duration = CallTimeouts.READ): List<TemplateDto> =
    call("templates.list", timeout = timeout).jsonObject["templates"]?.jsonArray.orEmpty().map {
        it.decode(TemplateDto.serializer())
    }

suspend fun CoreConnection.machineList(timeout: Duration = CallTimeouts.READ): List<MachineViewDto> =
    call("machine.list", timeout = timeout).jsonObject["machines"]?.jsonArray.orEmpty().map {
        it.decode(MachineViewDto.serializer())
    }

suspend fun CoreConnection.docGet(
    name: String,
    on: String? = null,
    timeout: Duration = CallTimeouts.READ,
): DocGetResult =
    call(
        "doc.get",
        obj {
            put("name", name)
            on?.let { put("on", it) }
        },
        timeout,
    ).decode(DocGetResult.serializer())

/** Compare-and-set write: [version] must equal the current version, else `RpcException(kind="conflict")`. */
suspend fun CoreConnection.docSet(
    name: String,
    value: JsonElement,
    version: Long,
    on: String? = null,
    timeout: Duration = CallTimeouts.READ,
): DocSetResult =
    call(
        "doc.set",
        obj {
            put("name", name)
            put("value", value)
            put("version", version)
            on?.let { put("on", it) }
        },
        timeout,
    ).decode(DocSetResult.serializer())
