package com.maxvibes.application.port.output

import com.maxvibes.domain.model.interaction.InteractionResponse

/** Normalized successful result of one [AgentCliPort.send] call. */
data class AgentCliSendResult(
    val response: InteractionResponse,
    /** Opaque provider session/thread id observed during this turn. */
    val observedSessionId: String?,
    /** Optional provider reasoning text when the transport exposes it. */
    val thinkingText: String? = null,
    /** Optional terminal per-turn statistics reported by the provider. */
    val stats: SessionStats? = null
)
