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
 * @param requestedFiles File paths requested BY the LLM in this ASSISTANT message
 *   (populated from the `requestedFiles` JSON field of the LLM response).
 * @param attachedFiles  File paths that were gathered and sent TO the LLM in this round
 *   (clipboard mode: the files actually attached in the generated JSON).
 * @param appliedModificationPaths String representations of [ElementPath] for each
 *   successfully applied modification. Persisted so the bubble footer can be
 *   reconstructed after IDE restart without storing full [Modification] objects.
 * @param reasoning  Optional LLM reasoning / thinking block shown in the bubble footer.
 * @param tokenInfo  Human-readable token summary line shown in the bubble footer
 *   (e.g. "~1 200 tokens · 3 files · first_message").
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = Instant.now().toEpochMilli(),
    val requestedFiles: List<String> = emptyList(),
    val attachedFiles: List<String> = emptyList(),
    val appliedModificationPaths: List<String> = emptyList(),
    val reasoning: String? = null,
    val tokenInfo: String? = null
)
