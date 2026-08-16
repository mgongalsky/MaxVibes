package com.maxvibes.application.service

import com.maxvibes.domain.model.interaction.InteractionResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodingAgentResponseProcessorMalformedModsTest {

    private val malformed = listOf("#1: нет обязательных полей: type (есть: kind, path, content)")

    @Test
    fun `malformed modifications are announced in the resulting message`() {
        val outcome = CodingAgentResponseProcessor.process(
            InteractionResponse(message = "Готово.", malformedModifications = malformed),
            CodingAgentResponseProcessor.Context()
        )

        val result = outcome.result as ClaudeCodeStepResult.Completed
        assertTrue(result.message.contains("modifications"))
        assertTrue(result.message.contains("#1"))
        assertTrue(result.message.contains("Готово."))
    }

    @Test
    fun `malformed notice reaches assistant history so the agent sees its format error`() {
        val outcome = CodingAgentResponseProcessor.process(
            InteractionResponse(message = "Готово.", malformedModifications = malformed),
            CodingAgentResponseProcessor.Context()
        )

        val history = outcome.intents
            .filterIsInstance<CodingAgentResponseProcessor.Intent.AppendAssistantHistory>()
        assertEquals(1, history.size)
        assertTrue(history.first().message.contains("#1"))
    }

    @Test
    fun `clean response keeps its message untouched`() {
        val outcome = CodingAgentResponseProcessor.process(
            InteractionResponse(message = "Готово."),
            CodingAgentResponseProcessor.Context()
        )

        assertEquals("Готово.", (outcome.result as ClaudeCodeStepResult.Completed).message)
    }
}
