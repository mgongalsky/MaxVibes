package com.maxvibes.application.service

import com.maxvibes.domain.model.interaction.InteractionResponse

@Deprecated("Use CodingAgentResponseProcessor")
object ClaudeCodeResponseProcessor {
    fun process(
        response: InteractionResponse,
        ctx: CodingAgentResponseProcessor.Context
    ): CodingAgentResponseProcessor.Outcome =
        CodingAgentResponseProcessor.process(response, ctx)
}
