package com.maxvibes.plugin.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodexAppServerLineParserTest {
    private val parser = CodexAppServerLineParser()

    @Test
    fun `parses rpc response`() {
        val line = parser.parse(
            "{\"id\":7,\"result\":{\"thread\":{\"id\":\"thr-1\"}}}"
        )

        val response = assertIs<CodexAppServerLineParser.Line.Response>(line)
        assertEquals(7L, response.id)
        assertEquals(null, response.error)
    }

    @Test
    fun `parses agent message delta`() {
        val line = parser.parse(
            "{\"method\":\"item/agentMessage/delta\",\"params\":{\"itemId\":\"msg-1\",\"delta\":\"hello\"}}"
        )

        val delta = assertIs<CodexAppServerLineParser.Line.NarrationDelta>(line)
        assertEquals("msg-1", delta.itemId)
        assertEquals("hello", delta.text)
    }

    @Test
    fun `parses completed agent message as authoritative text`() {
        val line = parser.parse(
            "{\"method\":\"item/completed\",\"params\":{\"item\":{\"id\":\"msg-2\",\"type\":\"agentMessage\",\"text\":\"final\"}}}"
        )

        val message = assertIs<CodexAppServerLineParser.Line.NarrationMessage>(line)
        assertEquals("msg-2", message.itemId)
        assertEquals("final", message.text)
    }

    @Test
    fun `parses token usage`() {
        val line = parser.parse(
            "{\"method\":\"thread/tokenUsage/updated\",\"params\":{\"tokenUsage\":{\"inputTokens\":12,\"outputTokens\":5}}}"
        )

        val usage = assertIs<CodexAppServerLineParser.Line.TokenUsage>(line)
        assertEquals(12, usage.inputTokens)
        assertEquals(5, usage.outputTokens)
    }

    @Test
    fun `parses failed turn`() {
        val line = parser.parse(
            "{\"method\":\"turn/completed\",\"params\":{\"turn\":{\"id\":\"turn-1\",\"status\":\"failed\",\"error\":{\"message\":\"boom\"}}}}"
        )

        val completed = assertIs<CodexAppServerLineParser.Line.TurnCompleted>(line)
        assertEquals("turn-1", completed.turnId)
        assertEquals("failed", completed.status)
        assertEquals("boom", completed.errorMessage)
    }

    @Test
    fun `tool completion maps failed status`() {
        val line = parser.parse(
            "{\"method\":\"item/completed\",\"params\":{\"item\":{\"id\":\"tool-1\",\"type\":\"commandExecution\",\"status\":\"failed\",\"command\":\"gradle test\"}}}"
        )

        val tool = assertIs<CodexAppServerLineParser.Line.ToolFinished>(line)
        assertFalse(tool.ok)
        assertTrue(tool.summary.orEmpty().contains("gradle test"))
    }

    @Test
    fun `parses thread started notification`() {
        val line = parser.parse(
            "{\"method\":\"thread/started\",\"params\":{\"thread\":{\"id\":\"thr-42\",\"model\":\"gpt-test\"}}}"
        )

        val started = assertIs<CodexAppServerLineParser.Line.ThreadStarted>(line)
        assertEquals("thr-42", started.threadId)
        assertEquals("gpt-test", started.model)
    }

    @Test
    fun `parses reasoning delta separately from narration`() {
        val line = parser.parse(
            "{\"method\":\"item/reasoning/summaryTextDelta\",\"params\":{\"itemId\":\"reason-1\",\"delta\":\"thinking\"}}"
        )

        val delta = assertIs<CodexAppServerLineParser.Line.ReasoningDelta>(line)
        assertEquals("reason-1", delta.itemId)
        assertEquals("thinking", delta.text)
    }

    @Test
    fun `parses rpc error response without throwing`() {
        val line = parser.parse(
            "{\"id\":9,\"error\":{\"code\":-32602,\"message\":\"bad params\"}}"
        )

        val response = assertIs<CodexAppServerLineParser.Line.Response>(line)
        assertEquals(9L, response.id)
        assertEquals(-32602, response.error?.code)
        assertEquals("bad params", response.error?.message)
    }

    @Test
    fun `malformed json becomes unknown instead of failing parser`() {
        val line = parser.parse("not-json")

        val unknown = assertIs<CodexAppServerLineParser.Line.Unknown>(line)
        assertEquals(null, unknown.method)
    }

    @Test
    fun `unknown notification is preserved as unknown method`() {
        val line = parser.parse(
            "{\"method\":\"future/newNotification\",\"params\":{}}"
        )

        val unknown = assertIs<CodexAppServerLineParser.Line.Unknown>(line)
        assertEquals("future/newNotification", unknown.method)
    }
}
