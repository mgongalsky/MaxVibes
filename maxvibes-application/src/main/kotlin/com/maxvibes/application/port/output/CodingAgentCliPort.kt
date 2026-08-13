package com.maxvibes.application.port.output

import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.shared.result.Result

// Provider-independent port for a stateful coding-agent CLI transport.
// Implementations own provider-specific lifecycle, wire protocol and remote session semantics.
interface CodingAgentCliPort {
    fun isAvailable(): Boolean

    suspend fun ensureStarted(
        resumeSessionId: String?,
        systemPrompt: String? = null
    ): Result<Unit, CodingAgentCliError>

    suspend fun send(
        request: ClipboardRequest
    ): Result<CodingAgentCliSendResult, CodingAgentCliError>

    fun shutdown()

    fun abort() {
        shutdown()
    }
}
