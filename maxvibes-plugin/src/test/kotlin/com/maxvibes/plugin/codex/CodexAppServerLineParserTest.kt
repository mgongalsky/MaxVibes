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
}
