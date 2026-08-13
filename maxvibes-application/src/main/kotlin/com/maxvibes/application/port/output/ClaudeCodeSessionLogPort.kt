package com.maxvibes.application.port.output

/**
 * Per-dialog verbose transcript for a coding-agent transport.
 *
 * Implementations keep the full untruncated transport exchange for the active
 * chat session. The provider-specific adapter decides what counts as inbound,
 * outbound, stderr and lifecycle metadata.
 */
interface CodingAgentSessionLogPort {
    fun begin(chatSessionId: String)
    fun event(text: String, data: Map<String, Any?>? = null)
    fun outbound(line: String)
    fun inbound(line: String)
    fun stderr(line: String)
    fun logFilePath(chatSessionId: String): String?
}

/** Compatibility name retained for the existing Claude adapter and UI wiring. */
typealias ClaudeCodeSessionLogPort = CodingAgentSessionLogPort
