package com.mtajchert.flocket.core.protocol.dto

import com.mtajchert.flocket.core.protocol.rpc.ProtocolJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class CoreIdDto(
    val id: String = "",
    val name: String = "",
)

/** `hello` result (SPEC §2.1). */
@Serializable
data class HelloResult(
    val core: CoreIdDto = CoreIdDto(),
    val features: List<String> = emptyList(),
    val framed: Boolean = false,
    val generation: Int = 0,
    val methods: List<String> = emptyList(),
    val version: String = "",
)

/** Program-reported status record (OSC 7501, SPEC §6.3). */
@Serializable
data class StatusDto(
    val state: String = "",
    val kind: String? = null,
    val progress: Int? = null,
    val app: String? = null,
    val title: String? = null,
    val msg: String? = null,
)

@Serializable
data class ForegroundDto(
    val pid: Int = 0,
    val name: String = "",
    val argv: List<String> = emptyList(),
    val cwd: String = "",
)

/**
 * `Terminal` record (SPEC §6.2). Everything but [address] is defaulted. [raw] keeps the complete
 * JSON object as received so unknown fields survive; decode with [TerminalDto.parse].
 */
@Serializable
data class TerminalDto(
    val address: String,
    val name: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val argv: List<String> = emptyList(),
    val cwd: String = "",
    val state: String = "live",
    val pid: Int? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    val signal: Int? = null,
    val reason: String? = null,
    @SerialName("created_ms") val createdMs: Long = 0,
    @SerialName("finished_ms") val finishedMs: Long? = null,
    val screen: String = "exact",
    val cols: Int = 0,
    val rows: Int = 0,
    val identity: String = "",
    val status: StatusDto? = null,
    @SerialName("status_ms") val statusMs: Long? = null,
    val records: Map<String, StatusDto> = emptyMap(),
    val turn: Long = 0,
    val reported: Boolean = false,
    val title: String = "",
    val parent: String? = null,
    val keep: Boolean = false,
    val template: String? = null,
    val foreground: ForegroundDto? = null,
    @Transient val raw: JsonObject = JsonObject(emptyMap()),
) {
    val isFinished: Boolean get() = state == "finished"

    companion object {
        fun parse(obj: JsonObject): TerminalDto = ProtocolJson.decodeFromJsonElement(serializer(), obj).copy(raw = obj)

        fun parse(element: JsonElement): TerminalDto =
            parse(element as? JsonObject ?: throw IllegalArgumentException("terminal is not an object"))
    }
}

/** `terminal.list` result. */
data class TerminalListResult(
    val terminals: List<TerminalDto>,
    val hiddenFinished: Int,
    val machines: List<MachineViewDto>?,
)

@Serializable
data class MachineViewDto(
    val name: String = "",
    val target: String = "",
    val via: Boolean = false,
    val temporary: Boolean = false,
    val session: String? = null,
    val id: String = "",
    val host: String = "",
    val version: String? = null,
    val state: String = "",
    @SerialName("since_ms") val sinceMs: Long? = null,
    val why: String? = null,
)

@Serializable
data class TemplateDto(
    val name: String = "",
    val description: String = "",
    val command: String = "",
    val error: String? = null,
)

/** Viewer display facts (SPEC §6.6). Always send full objects (FACTS semantics differ between 0.1.15 and 0.1.16). */
@Serializable
data class DisplayDto(
    val fg: String? = null,
    val bg: String? = null,
    val cursor: String? = null,
    val cell: List<Int>? = null,
)

@Serializable
data class HolderInfoDto(
    val pid: Long = 0,
    val instance: String? = null,
    val current: Boolean = false,
    val terminals: Int = 0,
)

/** `server.status` result. */
@Serializable
data class ServerStatusDto(
    val core: CoreIdDto = CoreIdDto(),
    val version: String = "",
    val pid: Long = 0,
    @SerialName("uptime_ms") val uptimeMs: Long = 0,
    val holders: List<HolderInfoDto> = emptyList(),
    val live: Int = 0,
    val finished: Int = 0,
    @SerialName("installed_by") val installedBy: String? = null,
)

/** `doc.get` result: [value] is null for a never-written or deleted document. */
@Serializable
data class DocGetResult(
    val value: JsonElement? = null,
    val version: Long = 0,
)

@Serializable
data class DocSetResult(
    val version: Long = 0,
)

/** `terminal.send` / `terminal.keys` result. */
@Serializable
data class SendResult(
    val status: String? = null,
    val turn: Long = 0,
    val started: Boolean? = null,
)

/** `terminal.interrupt` result; [outcome] is `stopped|exited|still_working|unknown` (others possible). */
@Serializable
data class InterruptResult(
    val outcome: String = "unknown",
    val key: String = "",
)

@Serializable
data class ReadResult(
    val text: String = "",
    val complete: Boolean = true,
    val how: String = "screen",
    val why: String? = null,
)

/** `machine.exec` result (stdout/stderr are base64). */
@Serializable
data class ExecResult(
    val stdout: String = "",
    val stderr: String = "",
    val truncated: Boolean = false,
    @SerialName("timed_out") val timedOut: Boolean = false,
    val ms: Long = 0,
    val code: Int? = null,
    val signal: String? = null,
)

/** `terminal.put` result: where the file landed (next to the terminal, on its machine). */
@Serializable
data class PutResult(
    val path: String = "",
    val bytes: Long = 0,
)

/** `file.write` result. */
@Serializable
data class FileResult(
    val path: String = "",
    val bytes: Long = 0,
)

@Serializable
data class RemovedResult(
    val removed: String = "",
)

/** `terminal.run` result: the terminal record plus optional template data. */
data class RunResult(
    val terminal: TerminalDto,
    val firstMessage: JsonObject?,
)

/** `events.subscribe` raw result. */
@Serializable
data class SubscribeResult(
    val seq: Long = 0,
    val terminals: List<JsonObject> = emptyList(),
    val channel: Int? = null,
)

/** `terminal.attach` raw result. */
@Serializable
data class AttachResult(
    val channel: Int,
    val terminal: JsonObject = JsonObject(emptyMap()),
)
