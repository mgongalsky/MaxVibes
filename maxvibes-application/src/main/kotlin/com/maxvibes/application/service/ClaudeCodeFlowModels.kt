package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.domain.model.interaction.AttachedImage

// Input for a user-originated coding-agent dialog turn.
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

// Temporary compatibility aliases while existing Claude Code callers migrate.
typealias ClaudeCodeTurnCommand = CodingAgentTurnCommand
typealias ReceivedClaudeTurn = ReceivedCodingAgentTurn
