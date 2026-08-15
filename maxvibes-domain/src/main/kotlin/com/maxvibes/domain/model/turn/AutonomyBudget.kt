package com.maxvibes.domain.model.turn

/**
 * Ограничивает, сколько шагов подряд агент может пройти без участия человека.
 *
 * Предохранитель против самоподдерживающегося цикла: агент, который каждый раз
 * просит ещё файлы, иначе будет крутиться бесконечно. Считаются только
 * автоматические шаги — ручные апрувы участие человека не тратят.
 */
data class AutonomyBudget(val maxAutomaticSteps: Int) {

    init {
        require(maxAutomaticSteps >= 0) { "maxAutomaticSteps must not be negative, got $maxAutomaticSteps" }
    }

    fun allows(consumedAutomaticSteps: Int): Boolean = consumedAutomaticSteps < maxAutomaticSteps

    fun remaining(consumedAutomaticSteps: Int): Int =
        (maxAutomaticSteps - consumedAutomaticSteps).coerceAtLeast(0)

    companion object {
        val DEFAULT: AutonomyBudget = AutonomyBudget(maxAutomaticSteps = 12)

        /** Каждый шаг требует человека — поведение до появления оркестратора. */
        val NONE: AutonomyBudget = AutonomyBudget(maxAutomaticSteps = 0)
    }
}
