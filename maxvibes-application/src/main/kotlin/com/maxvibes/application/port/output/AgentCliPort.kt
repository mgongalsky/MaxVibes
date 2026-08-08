package com.maxvibes.application.port.output

import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.shared.result.Result

/**
 * Provider-independent port for a stateful local coding-agent transport.
 *
 * Implementations own provider-specific process lifecycle, wire protocol and
 * remote session semantics. Application code communicates only in MaxVibes
 * protocol types and receives normalized [AgentStreamEvent]s separately.
 */
interface AgentCliPort {
    /** Quick liveness check for the configured agent executable. */
    fun isAvailable(): Boolean

    /**
     * Starts or resumes the provider transport when necessary.
     *
     * [resumeSessionId] is an opaque provider-owned session/thread identifier.
     * [systemPrompt] is supplied only when the provider needs MaxVibes instructions
     * during transport startup; the adapter decides how those instructions are delivered.
     */
    suspend fun ensureStarted(
        resumeSessionId: String?,
        systemPrompt: String? = null
    ): Result<Unit, AgentCliError>

    /** Sends one MaxVibes protocol request and waits for the provider turn to finish. */
    suspend fun send(
        request: ClipboardRequest
    ): Result<AgentCliSendResult, AgentCliError>

    /** Terminates the underlying transport and releases its resources. */
    fun shutdown()

    /** Forcefully aborts an in-flight turn. */
    fun abort() {
        shutdown()
    }
}
