package com.maxvibes.application.port.output

import com.maxvibes.domain.model.interaction.InteractionResponse

/** Normalized result of one successful [AgentCliPort.send] call. */
data class AgentCliSendResult(
    val response: InteractionResponse,
    /** Provider-owned resumable conversation id observed during this turn. */
    val observedSessionId: String?,
    /** Full provider reasoning/thinking text when exposed by the transport. */
    val thinkingText: String? = null,
    /** Provider-reported per-turn statistics when available. */
    val stats: SessionStats? = null
)
