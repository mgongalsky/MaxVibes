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
    val steps: List<TurnStep> = emptyList(),
    val iterationsBeforeRefill: Int = 0
) {

    /** Автономные итерации, потраченные с момента последнего восстановления бюджета. */
    val autonomousIterationCount: Int
        get() = spentIterations - iterationsBeforeRefill

    val lastStep: TurnStep? get() = steps.lastOrNull()

    fun canProceedAutomatically(): Boolean = budget.allows(autonomousIterationCount)

    fun nextStep(action: AgentActionKind?, automatic: Boolean): TurnStep =
        TurnStep(ordinal = steps.size, action = action, automatic = automatic)

    fun record(step: TurnStep): AgentTurn = copy(steps = steps + step)

    /**
     * Человек вмешался и разрешил идти дальше — счёт итераций начинается заново.
     *
     * Сдвигается отметка, а не очищается журнал: по журналу видно всю траекторию
     * хода, и стирать историю ради счётчика значит терять диагностику.
     */
    fun refillBudget(): AgentTurn = copy(iterationsBeforeRefill = spentIterations)

    private val spentIterations: Int get() = steps.count { it.consumesBudget() }
}

/**
 * Единицу бюджета тратит только самостоятельная передача хода модели.
 *
 * Остальные виды действий бесплатны намеренно: разрешать ли их — дело политики
 * approve, а бюджет ограничивает другое, число самостоятельных заходов модели.
 * `when` без `else` обязателен: новый вид действия должен ломать компиляцию
 * здесь, чтобы цену ему назначили осознанно, а не унаследовали молча.
 */
private fun TurnStep.consumesBudget(): Boolean = automatic && when (action) {
    AgentActionKind.CONTINUATION -> true
    AgentActionKind.VIEW_REQUEST,
    AgentActionKind.MODIFICATION,
    AgentActionKind.COMMAND,
    AgentActionKind.BUILD,
    AgentActionKind.TESTS -> false

    null -> false
}
