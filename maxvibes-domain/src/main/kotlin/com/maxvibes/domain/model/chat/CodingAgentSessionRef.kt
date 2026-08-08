package com.maxvibes.domain.model.chat

/** Coding-agent implementation associated with persisted remote session state. */
enum class CodingAgentProvider {
    CLAUDE_CODE,
    CODEX
}

/**
 * Provider-aware reference to remote coding-agent session state.
 *
 * [remoteSessionId] is provider-defined: Claude Code uses its CLI session id,
 * while Codex will use the corresponding thread/session identifier.
 * [needsFullContext] requests a full MaxVibes context replay on the next turn.
 */
data class CodingAgentSessionRef(
    val provider: CodingAgentProvider,
    val remoteSessionId: String? = null,
    val needsFullContext: Boolean = true
)
