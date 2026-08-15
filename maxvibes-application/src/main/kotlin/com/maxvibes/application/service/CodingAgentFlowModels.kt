package com.maxvibes.application.service

import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.interaction.InteractionResponse

// Provider-independent transport command for one coding-agent turn.
data class CodingAgentTurnCommand(
    val sessionId: String,
    val freshFiles: Map<String, String> = emptyMap(),
    val firstMessage: Boolean = false,
    val attachedContext: String? = null,
    val ideErrors: String? = null,
    val specificPromptContent: String? = null,
    val commandResults: String? = null,
    // Kept apart from commandResults on purpose: the agent must be able to tell a red build
    // from a failed shell command, otherwise the terminal cannot stay a fallback channel.
    val checkResults: String? = null,
    val attachedImages: List<AttachedImage> = emptyList()
)

// Normalized successful coding-agent turn consumed by response handling.
data class ReceivedCodingAgentTurn(
    val response: InteractionResponse,
    val inputTokens: Int,
    val outputTokens: Int,
    val thinkingText: String? = null,
    val durationMs: Long = 0L,
    val costUsd: Double? = null,
    val numTurns: Int? = null
)
