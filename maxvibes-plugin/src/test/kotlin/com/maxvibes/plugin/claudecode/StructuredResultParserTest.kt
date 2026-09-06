package com.maxvibes.plugin.claudecode

import com.maxvibes.plugin.clipboard.JsonInteractionProtocolCodec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StructuredResultParserTest {
    private val parser = StreamJsonEventParser()

    @Test
    fun `structured object takes precedence over result prose`() {
        val end = assertIs<StreamJsonEventParser.Line.TurnEnd>(
            parser.parse(
                """{"type":"result","is_error":false,"result":"Generated output",
                "structured_output":{"message":"Готово","modifications":[],"turnIntent":"DONE"},
                "usage":{"input_tokens":10,"cache_read_input_tokens":5,"output_tokens":7}}"""
            )
        )
        assertEquals("Готово", JsonInteractionProtocolCodec().decode(end.finalText!!)!!.message)
        assertEquals(15, end.inputTokens)
        assertEquals(7, end.outputTokens)
    }

    @Test
    fun `legacy result remains available without structured object`() {
        for (extra in listOf("", ",\"structured_output\":null")) {
            val end = assertIs<StreamJsonEventParser.Line.TurnEnd>(
                parser.parse(
                    "{\"type\":\"result\",\"result\":\"legacy\"$extra}"
                )
            )
            assertEquals("legacy", end.finalText)
        }
    }

    @Test
    fun `error result takes precedence over structured output`() {
        val end = assertIs<StreamJsonEventParser.Line.TurnEnd>(
            parser.parse(
                """{"type":"result","is_error":true,"result":"Schema generation failed",
                "structured_output":{"message":"Partial"}}"""
            )
        )
        assertTrue(end.isError)
        assertEquals("Schema generation failed", end.finalText)
    }
}
