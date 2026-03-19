package com.maxvibes.domain.model.chat

import java.time.Instant
import java.util.UUID

/**
 * Immutable domain model representing a single message in a chat session.
 *
 * @param id                       Unique message identifier.
 * @param role                     Who sent the message: USER, ASSISTANT, or SYSTEM.
 * @param content                  Raw text content of the message.
 * @param timestamp                Creation time (epoch millis).
 * @param requestedFiles           File paths requested BY the LLM in this ASSISTANT message
 *   ("please give me these files next"). Used by ClipboardInteractionService.redoLastRequest.
 *   Empty for USER and SYSTEM messages.
 * @param attachedFiles            File paths gathered and sent TO the LLM in this round
 *   (the "freshFiles" clipboard payload). Persisted so the "Gathered files" bubble footer
 *   survives session reload. Empty for USER/SYSTEM and API-mode ASSISTANT messages.
 * @param appliedModificationPaths String representations of [ElementPath] for each
 *   [ModificationResult.Success] from this ASSISTANT message. Persisted so the clickable
 *   modification links in the bubble footer survive session reload.
 *   Empty for USER and SYSTEM messages.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = Instant.now().toEpochMilli(),
    val requestedFiles: List<String> = emptyList(),
    val attachedFiles: List<String> = emptyList(),
    val appliedModificationPaths: List<String> = emptyList()
)
