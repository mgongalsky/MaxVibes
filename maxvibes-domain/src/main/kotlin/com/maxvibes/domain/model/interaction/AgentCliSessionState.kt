package com.maxvibes.domain.model.interaction

/**
 * Persisted provider-owned conversation state for an Agent CLI chat.
 *
 * [remoteSessionId] is deliberately provider-neutral: Claude uses a session id,
 * while Codex can store its thread id in the same slot.
 */
data class AgentCliSessionState(
    val provider: AgentCliProvider,
    val remoteSessionId: String? = null,
    val needsFullContext: Boolean = true
)
