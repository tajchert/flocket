package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.model.AgentShare
import com.mtajchert.flocket.core.model.TerminalAddress
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Reads the `flocket-share` document (`{"v":1,"shares":[{"id","terminal","paths","note","at"}]}`) written by
 * the agent-side helper into [AgentShare]s, newest first.
 *
 * Tolerant: keys are read by name (hc stores them sorted), unknown fields and any `v` are accepted, entries
 * without an id, terminal or at least one path are skipped, and a missing/wrong-shaped document is empty.
 * Never throws.
 */
object ShareDocumentParser {
    const val DOCUMENT = "flocket-share"

    fun parse(
        value: JsonElement?,
        machine: String?,
    ): List<AgentShare> =
        try {
            val shares = (value as? JsonObject)?.get("shares") as? JsonArray
            shares
                ?.mapNotNull { (it as? JsonObject)?.let { o -> entry(o, machine) } }
                ?.sortedWith(compareByDescending<AgentShare> { it.atEpochSeconds }.thenBy { it.id })
                .orEmpty()
        } catch (e: Exception) {
            emptyList()
        }

    private fun entry(
        o: JsonObject,
        machine: String?,
    ): AgentShare? {
        val id = o.string("id")?.takeIf { it.isNotBlank() } ?: return null
        val terminal = o.string("terminal")?.takeIf { it.isNotBlank() } ?: return null
        val paths =
            (o["paths"] as? JsonArray).orEmpty().mapNotNull { o2 ->
                (o2 as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
        if (paths.isEmpty()) return null
        return AgentShare(
            id = id,
            address = TerminalAddress(if (machine == null) terminal else "$machine/$terminal"),
            machine = machine,
            paths = paths,
            note = o.string("note")?.takeIf { it.isNotBlank() },
            atEpochSeconds = (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
