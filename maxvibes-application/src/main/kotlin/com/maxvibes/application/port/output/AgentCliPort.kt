package com.maxvibes.application.port.output

import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.shared.result.Result

/**
 * Provider-neutral transport contract for a local coding-agent CLI.
 *
 * Implementations own their provider-specific process lifecycle, wire protocol,
 * session dialect and parsing. The application layer sees only normalized
 * [ClipboardRequest], [AgentCliSendResult], [AgentCliError] and [AgentStreamEvent].
 */
interface AgentCliPort {

    /** Returns true when the configured provider executable is callable. */
    fun isAvailable(): Boolean

    suspend fun ensureStarted(
        resumeSessionId: String?,
        systemPrompt: String? = null
    ): Result<Unit, AgentCliError>

    /** Sends one normalized MaxVibes protocol turn and waits for its final response. */
    suspend fun send(
        request: ClipboardRequest
    ): Result<AgentCliSendResult, AgentCliError>

    /** Terminates the provider process/session and releases transport resources. */
    fun shutdown()

    /**
     * Interrupts an in-flight turn. Implementations with no dedicated interrupt
     * primitive may fall back to terminating the transport process.
     */
    fun abort() {
        shutdown()
    }
}
