package com.maxvibes.plugin.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Tolerant parser for one JSON-RPC message emitted by Codex App Server.
 *
 * This class intentionally understands only the JSON-RPC envelope. Codex-specific
 * thread/turn/item notification semantics belong to [CodexAppServerAdapter], not to
 * a shared or prematurely generalized wire parser.
 *
 * Malformed or unknown messages never throw into the persistent stdout reader.
 */
internal class CodexAppServerEventParser {

    sealed interface Message {
        data class Response(
            val id: String,
            val result: JsonElement?,
            val error: RpcError?
        ) : Message

        /** Server-to-client JSON-RPC request. The adapter must answer it if supported. */
        data class Request(
            val id: String,
            val method: String,
            val params: JsonElement?
        ) : Message

        data class Notification(
            val method: String,
            val params: JsonElement?
        ) : Message

        data class Unknown(val reason: String) : Message
    }

    data class RpcError(
        val code: Int?,
        val message: String?,
        val data: JsonElement?
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(rawLine: String): Message {
        val root = runCatching { json.parseToJsonElement(rawLine) }.getOrNull()
            ?: return Message.Unknown("malformed json")
        val obj = root as? JsonObject
            ?: return Message.Unknown("json-rpc message is not an object")

        val method = obj.string("method")
        val id = obj.rpcId()

        if (method != null) {
            return if (id != null) {
                Message.Request(
                    id = id,
                    method = method,
                    params = obj["params"]
                )
            } else {
                Message.Notification(
                    method = method,
                    params = obj["params"]
                )
            }
        }

        if (id != null && (obj.containsKey("result") || obj.containsKey("error"))) {
            return Message.Response(
                id = id,
                result = obj["result"],
                error = (obj["error"] as? JsonObject)?.let { error ->
                    RpcError(
                        code = (error["code"] as? JsonPrimitive)?.intOrNull,
                        message = error.string("message"),
                        data = error["data"]
                    )
                }
            )
        }

        return Message.Unknown("unrecognized json-rpc envelope")
    }

    private fun JsonObject.rpcId(): String? {
        val primitive = this["id"] as? JsonPrimitive ?: return null
        return primitive.contentOrNull
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
