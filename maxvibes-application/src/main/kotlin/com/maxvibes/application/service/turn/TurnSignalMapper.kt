package com.maxvibes.application.service.turn

import com.maxvibes.application.service.ClaudeCodeStepResult
import com.maxvibes.domain.model.approval.AgentActionKind
import com.maxvibes.domain.model.turn.TurnSignal

/**
 * Translates a coding-agent step result into a turn signal.
 *
 * Deliberately kept out of the UI: the decision depends on nothing but the
 * result itself, so living inside the dispatcher only made it untestable.
 */
object TurnSignalMapper {

    fun from(result: ClaudeCodeStepResult): TurnSignal = when (result) {
        is ClaudeCodeStepResult.WaitingForApprove -> TurnSignal.Pending(AgentActionKind.VIEW_REQUEST)
        is ClaudeCodeStepResult.AwaitingModApprove -> TurnSignal.Pending(AgentActionKind.MODIFICATION)
        is ClaudeCodeStepResult.AwaitingQuestions -> TurnSignal.Questions
        is ClaudeCodeStepResult.Completed -> fromCompleted(result)
        is ClaudeCodeStepResult.Error -> TurnSignal.Failed(result.message)
        is ClaudeCodeStepResult.TransportError -> TurnSignal.Failed(result.detail)
    }

    /** A failed modification cancels the held batch, so the pending commands never run. */
    private fun fromCompleted(result: ClaudeCodeStepResult.Completed): TurnSignal =
        if (result.commands.isNotEmpty() && result.modifications.all { it.success }) {
            TurnSignal.Pending(AgentActionKind.COMMAND)
        } else {
            TurnSignal.Completed
        }
}
