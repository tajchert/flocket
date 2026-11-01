package com.mtajchert.flocket.core.protocol.rpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The one Json configuration of the protocol layer: unknown fields are always tolerated. */
val ProtocolJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = false
    }

/** JSON-RPC 2.0 request. [id] is always set: notifications get no reply (SPEC gotcha #21). */
@Serializable
data class RpcRequest(
    val jsonrpc: String = "2.0",
    val id: Long,
    val method: String,
    val params: JsonObject = JsonObject(emptyMap()),
)

/**
 * Any message the core can send on channel 0 (or a notification line on an event channel):
 * a reply (`id` + `result`/`error`) or a call/notification (`method` + `params`).
 */
@Serializable
data class RpcMessage(
    val id: JsonElement? = null,
    val method: String? = null,
    val params: JsonElement? = null,
    val result: JsonElement? = null,
    val error: RpcErrorBody? = null,
)

@Serializable
data class RpcErrorBody(
    val code: Int = 0,
    val message: String = "",
    val data: RpcErrorData? = null,
)

@Serializable
data class RpcErrorData(
    val kind: String? = null,
    val hint: String? = null,
)

/** The core answered a call with an error. Switch on [kind] (`error.data.kind`), not [code]. */
class RpcException(
    val kind: String,
    message: String,
    val hint: String?,
    val code: Int,
) : RuntimeException(message) {
    companion object {
        fun from(error: RpcErrorBody): RpcException =
            RpcException(
                kind = error.data?.kind ?: kindForCode(error.code),
                message = error.message,
                hint = error.data?.hint?.takeIf { it.isNotEmpty() },
                code = error.code,
            )

        private fun kindForCode(code: Int) =
            when (code) {
                -32700 -> "parse_error"
                -32600 -> "invalid_request"
                -32601 -> "unknown_method"
                -32602 -> "invalid_params"
                -32603 -> "internal"
                else -> "unknown"
            }
    }
}

internal fun RpcMessage.longId(): Long? =
    (id as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf {
            !it.isString
        }?.content
        ?.toLongOrNull()
