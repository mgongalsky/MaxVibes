package com.maxvibes.application.service

import com.maxvibes.domain.model.chat.CodingAgentProvider

internal enum class CodingAgentSystemPromptDelivery {
    PROCESS_START,
    REQUEST
}

/** Provider-specific behavior that must not leak into the shared coding-agent flow. */
internal data class CodingAgentProviderPolicy(
    val displayName: String,
    val logTag: String,
    val systemPromptDelivery: CodingAgentSystemPromptDelivery
) {
    val omitSystemInstructionFromRequest: Boolean
        get() = systemPromptDelivery == CodingAgentSystemPromptDelivery.PROCESS_START

    companion object {
        fun forProvider(provider: CodingAgentProvider): CodingAgentProviderPolicy = when (provider) {
            CodingAgentProvider.CLAUDE_CODE -> CodingAgentProviderPolicy(
                displayName = provider.displayName,
                logTag = "ClaudeCode",
                systemPromptDelivery = CodingAgentSystemPromptDelivery.PROCESS_START
            )

            CodingAgentProvider.CODEX -> CodingAgentProviderPolicy(
                displayName = provider.displayName,
                logTag = "Codex",
                systemPromptDelivery = CodingAgentSystemPromptDelivery.REQUEST
            )
        }
    }
}
