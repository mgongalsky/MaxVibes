package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.interaction.InteractionResponse

/**
 * Input for a user-originated Claude Code dialog turn.
 *
 * Groups the public entry-point parameters so orchestration services can evolve
 * without passing long positional argument lists between extracted components.
 */
data class UserInputCommand(
    val sessionId: String,
    val userInput: String,
    val history: List<ChatMessageDTO> = emptyList(),
    val attachedContext: String? = null,
    val planOnly: Boolean = false,
    val ideErrors: String? = null,
    val globalContextFiles: List<String> = emptyList(),
    val specificPromptContent: String? = null,
    val attachedImages: List<AttachedImage> = emptyList()
)

/**
 * Transport-level command for one Claude Code request.
 *
 * It intentionally contains no approval or response-processing semantics.
 */
data class ClaudeCodeTurnCommand(
    val sessionId: String,
    val freshFiles: Map<String, String> = emptyMap(),
    val firstMessage: Boolean = false,
    val attachedContext: String? = null,
    val ideErrors: String? = null,
    val specificPromptContent: String? = null,
    val commandResults: String? = null,
    val attachedImages: List<AttachedImage> = emptyList()
)

/**
 * Normalized successful transport result consumed by response handling.
 */
data class ReceivedClaudeTurn(
    val response: InteractionResponse,
    val inputTokens: Int,
    val outputTokens: Int,
    val thinkingText: String? = null,
    val durationMs: Long = 0L,
    val costUsd: Double? = null,
    val numTurns: Int? = null
)
