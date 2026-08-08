package com.maxvibes.plugin.codex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexAppServerEventParserTest {

    private val parser = CodexAppServerEventParser()

    @Test
    fun `parses successful response`() {
        val message = parser.parse(
            """
            {"jsonrpc":"2.0","id":1,"result":{"threadId":"thread-1"}}
        """.trimIndent()
        )

        assertTrue(message is CodexAppServerEventParser.Message.Response)
        message as CodexAppServerEventParser.Message.Response
        assertEquals("1", message.id)
        assertNull(message.error)
        assertEquals(
            "thread-1",
            (message.result as JsonObject)["threadId"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `parses error response`() {
        val message = parser.parse(
            """
            {"jsonrpc":"2.0","id":"req-7","error":{"code":-32602,"message":"bad params"}}
        """.trimIndent()
        )

        assertTrue(message is CodexAppServerEventParser.Message.Response)
        message as CodexAppServerEventParser.Message.Response
        assertEquals("req-7", message.id)
        assertEquals(-32602, message.error?.code)
        assertEquals("bad params", message.error?.message)
    }

    @Test
    fun `parses notification without assuming Codex schema`() {
        val message = parser.parse(
            """
            {"jsonrpc":"2.0","method":"turn/completed","params":{"turn":{"id":"turn-1"}}}
        """.trimIndent()
        )

        assertTrue(message is CodexAppServerEventParser.Message.Notification)
        message as CodexAppServerEventParser.Message.Notification
        assertEquals("turn/completed", message.method)
    }

    @Test
    fun `parses server request separately from notification`() {
        val message = parser.parse(
            """
            {"jsonrpc":"2.0","id":42,"method":"server/request","params":{}}
        """.trimIndent()
        )

        assertTrue(message is CodexAppServerEventParser.Message.Request)
        message as CodexAppServerEventParser.Message.Request
        assertEquals("42", message.id)
        assertEquals("server/request", message.method)
    }

    @Test
    fun `malformed input becomes unknown`() {
        val message = parser.parse("not-json")

        assertTrue(message is CodexAppServerEventParser.Message.Unknown)
    }
}
