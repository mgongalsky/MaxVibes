package com.maxvibes.plugin.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CodexAppServerProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `request allocates monotonic ids`() {
        val protocol = CodexAppServerProtocol(firstRequestId = 7)

        val first = protocol.request("initialize")
        val second = protocol.request("thread/start")

        assertEquals("7", first.id)
        assertEquals("8", second.id)
        assertEquals(7L, json.parseToJsonElement(first.jsonLine).jsonObject["id"]?.jsonPrimitive?.content?.toLong())
        assertEquals(8L, json.parseToJsonElement(second.jsonLine).jsonObject["id"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun `request preserves params`() {
        val protocol = CodexAppServerProtocol()
        val params = buildJsonObject { put("cwd", "C:/project") }

        val outbound = protocol.request("thread/start", params)
        val root = json.parseToJsonElement(outbound.jsonLine).jsonObject

        assertEquals("2.0", root["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals("thread/start", root["method"]?.jsonPrimitive?.content)
        assertEquals("C:/project", root["params"]?.jsonObject?.get("cwd")?.jsonPrimitive?.content)
    }

    @Test
    fun `notification has no id`() {
        val root = json.parseToJsonElement(
            CodexAppServerProtocol().notification("initialized")
        ).jsonObject

        assertEquals("initialized", root["method"]?.jsonPrimitive?.content)
        assertFalse(root.containsKey("id"))
    }

    @Test
    fun `success response uses null result when omitted`() {
        val root = json.parseToJsonElement(
            CodexAppServerProtocol().successResponse("42")
        ).jsonObject

        assertEquals("42", root["id"]?.jsonPrimitive?.content)
        assertSame(JsonNull, root["result"])
    }

    @Test
    fun `error response carries structured error`() {
        val root = json.parseToJsonElement(
            CodexAppServerProtocol().errorResponse("request-a", -32601, "unsupported")
        ).jsonObject
        val error = root["error"]?.jsonObject

        assertEquals("request-a", root["id"]?.jsonPrimitive?.content)
        assertEquals("-32601", error?.get("code")?.jsonPrimitive?.content)
        assertEquals("unsupported", error?.get("message")?.jsonPrimitive?.content)
    }
}
