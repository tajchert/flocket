package com.mtajchert.flocket.core.protocol

import com.mtajchert.flocket.core.protocol.rpc.ProtocolJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Loads the shared wire fixtures from the repo (`protocol/fixtures`), never copies. */
object Fixtures {
    val dir: File = File(System.getProperty("flocket.fixtures.dir") ?: error("flocket.fixtures.dir not set"))

    fun lines(name: String): List<JsonObject> =
        File(dir, name).readLines().filter { it.isNotBlank() }.map { ProtocolJson.parseToJsonElement(it).jsonObject }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun text(name: String): String = File(dir, name).readText().trim()

    /** Raw chunks of one direction (`in` = core to client, `out` = client to core). */
    fun chunks(
        name: String,
        dir: String,
    ): List<ByteArray> =
        lines(name)
            .filter {
                it["dir"]?.jsonPrimitive?.content == dir && it.containsKey("hex")
            }.map { hex(it["hex"]!!.jsonPrimitive.content) }
}
