package com.maxvibes.application.service.turn

import com.maxvibes.application.service.ClaudeCodeStepResult
import com.maxvibes.domain.model.approval.AgentActionKind
import com.maxvibes.domain.model.turn.TurnSignal
import com.maxvibes.domain.model.turn.TurnIntent

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

    /**
     * Порядок веток — это порядок приоритетов, а не стиль.
     *
     * Неудавшаяся правка обрывает ход всегда: единственный автоматический ответ
     * на неё — повторная попытка агента, а она упрётся в ту же ошибку и потратит
     * бюджет впустую. Отложенные команды идут раньше продолжения, потому что их
     * вывод всё равно возвращает ход агенту.
     */
    private fun fromCompleted(result: ClaudeCodeStepResult.Completed): TurnSignal {
        val allApplied = result.modifications.all { it.success }
        return when {
            !allApplied -> TurnSignal.Completed
            result.commands.isNotEmpty() -> TurnSignal.Pending(AgentActionKind.COMMAND)
            result.turnIntent == TurnIntent.CONTINUE -> TurnSignal.Pending(AgentActionKind.CONTINUATION)
            else -> TurnSignal.Completed
        }
    }
}
