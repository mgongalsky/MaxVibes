package com.maxvibes.application.service

import com.maxvibes.domain.model.chat.CodingAgentProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodingAgentProviderPolicyTest {
    @Test
    fun `Claude Code receives system prompt at process start`() {
        val policy = CodingAgentProviderPolicy.forProvider(CodingAgentProvider.CLAUDE_CODE)

        assertEquals("Claude Code", policy.displayName)
        assertEquals(CodingAgentSystemPromptDelivery.PROCESS_START, policy.systemPromptDelivery)
        assertTrue(policy.omitSystemInstructionFromRequest)
    }

    @Test
    fun `Codex receives system prompt in request protocol`() {
        val policy = CodingAgentProviderPolicy.forProvider(CodingAgentProvider.CODEX)

        assertEquals("Codex", policy.displayName)
        assertEquals(CodingAgentSystemPromptDelivery.REQUEST, policy.systemPromptDelivery)
        assertFalse(policy.omitSystemInstructionFromRequest)
    }
}
