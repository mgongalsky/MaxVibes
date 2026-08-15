package com.maxvibes.domain.model.turn

import com.maxvibes.domain.model.approval.AgentActionKind

/**
 * Один шаг хода: какое действие агент предложил и потребовал ли он человека.
 *
 * [action] равен null, когда агент вернул только текст — такой шаг завершает ход.
 */
data class TurnStep(
    val ordinal: Int,
    val action: AgentActionKind?,
    val automatic: Boolean
)

/**
 * Агрегат хода: иммутабельный журнал шагов и бюджет автономии для сессии.
 *
 * Журнал ведётся копированием, поэтому в тесте видно всю траекторию хода —
 * какие шаги прошли сами, а какие ушли человеку — без моков и шпионов.
 */
data class AgentTurn(
    val sessionId: String,
    val budget: AutonomyBudget = AutonomyBudget.DEFAULT,
    val steps: List<TurnStep> = emptyList()
) {

    val automaticStepCount: Int get() = steps.count { it.automatic }

    val lastStep: TurnStep? get() = steps.lastOrNull()

    fun canProceedAutomatically(): Boolean = budget.allows(automaticStepCount)

    fun nextStep(action: AgentActionKind?, automatic: Boolean): TurnStep =
        TurnStep(ordinal = steps.size, action = action, automatic = automatic)

    fun record(step: TurnStep): AgentTurn = copy(steps = steps + step)
}
