package com.maxvibes.plugin.codex

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal JSON-RPC 2.0 writer used by the Codex App Server transport.
 *
 * Provider method names and parameter schemas deliberately stay outside this class.
 * It only owns envelope construction and request-id allocation.
 */
internal class CodexAppServerProtocol(
    firstRequestId: Long = 1L
) {
    data class OutboundRequest(
        val id: String,
        val jsonLine: String
    )

    private val nextRequestId = AtomicLong(firstRequestId)

    fun request(method: String, params: JsonElement? = null): OutboundRequest {
        require(method.isNotBlank()) { "JSON-RPC method must not be blank" }

        val id = nextRequestId.getAndIncrement().toString()
        val line = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id.toLong())
            put("method", method)
            params?.let { put("params", it) }
        }.toString()

        return OutboundRequest(id = id, jsonLine = line)
    }

    fun notification(method: String, params: JsonElement? = null): String {
        require(method.isNotBlank()) { "JSON-RPC method must not be blank" }

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            params?.let { put("params", it) }
        }.toString()
    }

    fun successResponse(id: String, result: JsonElement? = null): String {
        require(id.isNotBlank()) { "JSON-RPC id must not be blank" }

        return buildJsonObject {
            put("jsonrpc", "2.0")
            putRpcId(id)
            put("result", result ?: JsonNull)
        }.toString()
    }

    fun errorResponse(
        id: String,
        code: Int,
        message: String,
        data: JsonElement? = null
    ): String {
        require(id.isNotBlank()) { "JSON-RPC id must not be blank" }

        return buildJsonObject {
            put("jsonrpc", "2.0")
            putRpcId(id)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
                data?.let { put("data", it) }
            })
        }.toString()
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putRpcId(id: String) {
        id.toLongOrNull()?.let { numeric ->
            put("id", numeric)
        } ?: put("id", id)
    }
}
