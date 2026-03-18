package com.maxvibes.domain.model.chat

import java.time.Instant
import java.util.UUID

/**
 * Immutable domain model representing a single message in a chat session.
 *
 * @param id        Unique message identifier.
 * @param role      Who sent the message: USER, ASSISTANT, or SYSTEM.
 * @param content   Raw text content of the message.
 * @param timestamp Creation time (epoch millis).
 * @param requestedFiles File paths requested by the LLM in this ASSISTANT message.
 *   Populated only when the LLM response included a non-empty `requestedFiles` field.
 *   Used by [com.maxvibes.application.service.ClipboardInteractionService.redoLastRequest]
 *   to restore file context when the in-memory clipboard workspace belongs to a different
 *   session (Scenario B). Empty for USER and SYSTEM messages.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = Instant.now().toEpochMilli(),
    val requestedFiles: List<String> = emptyList()
)
