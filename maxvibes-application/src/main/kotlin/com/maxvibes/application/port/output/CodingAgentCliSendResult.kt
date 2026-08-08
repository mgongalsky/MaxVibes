package com.maxvibes.application.port.output

import com.maxvibes.domain.model.interaction.InteractionResponse

// Normalized successful result of one CodingAgentCliPort.send call.
data class CodingAgentCliSendResult(
    val response: InteractionResponse,
    val observedSessionId: String?,
    val thinkingText: String? = null,
    val stats: SessionStats? = null
)
