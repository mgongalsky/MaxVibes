package com.maxvibes.plugin.codex

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CodexFinalAnswerTest {
    private val parser = CodexAppServerLineParser()
    private val stateClass = CodexAppServerAdapter::class.java.declaredClasses.single { it.simpleName == "TurnState" }

    private fun newState(): Any = stateClass.getDeclaredConstructor(java.lang.Long.TYPE)
        .apply { isAccessible = true }.newInstance(0L)

    private fun message(state: Any, id: String, text: String, phase: String?) {
        val raw = buildJsonObject {
            put("method", "item/completed")
            putJsonObject("params") {
                putJsonObject("item") {
                    put("type", "agentMessage")
                    put("id", id)
                    put("text", text)
                    if (phase != null) put("phase", phase)
                }
            }
        }.toString()
        val parsed = assertIs<CodexAppServerLineParser.Line.NarrationMessage>(parser.parse(raw))
        assertEquals(phase, parsed.phase)
        stateClass.getDeclaredMethod("setMessage", String::class.java, String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(state, parsed.itemId, parsed.text, parsed.phase)
    }

    private fun finalText(state: Any): String = stateClass.getDeclaredMethod("snapshotFinalText")
        .apply { isAccessible = true }.invoke(state) as String

    @Test
    fun `final JSON excludes commentary even when commentary contains JSON`() {
        val state = newState()
        message(state, "progress", "Example: {\"message\":\"not final\"}", "commentary")
        val expected = "{\"message\":\"done\",\"turnIntent\":\"DONE\"}"
        message(state, "answer", expected, "final_answer")
        assertEquals(expected, finalText(state))
    }

    @Test
    fun `messages without phases retain legacy behavior`() {
        val state = newState()
        message(state, "a", "first", null)
        message(state, "b", "second", null)
        assertEquals("first" + System.lineSeparator().repeat(2) + "second", finalText(state))
    }

    @Test
    fun `empty final answer does not fall back to commentary`() {
        val state = newState()
        message(state, "progress", "Still working", "commentary")
        message(state, "answer", "", "final_answer")
        assertEquals("", finalText(state))
    }

    @Test
    fun `commentary alone is not a final answer`() {
        val state = newState()
        message(state, "progress", "Still working", "commentary")
        assertEquals("", finalText(state))
    }
}
