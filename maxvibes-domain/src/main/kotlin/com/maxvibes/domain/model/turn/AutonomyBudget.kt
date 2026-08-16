package com.maxvibes.domain.model.turn

/**
 * Ограничивает, сколько раз подряд ход может уйти модели без участия человека.
 *
 * Единица — автономная итерация, то есть один самостоятельный запуск модели, а не
 * отдельное действие внутри него. Чтение кода, применение уже разрешённых политикой
 * правок и разрешённые команды сами по себе бюджет не тратят: раньше исследование из
 * нескольких запросов файлов съедало лимит целиком, хотя работа почти не двигалась.
 */
data class AutonomyBudget(val maxAutonomousIterations: Int) {

    init {
        require(maxAutonomousIterations >= 0) {
            "maxAutonomousIterations must not be negative, got $maxAutonomousIterations"
        }
    }

    fun allows(consumedIterations: Int): Boolean = consumedIterations < maxAutonomousIterations

    fun remaining(consumedIterations: Int): Int =
        (maxAutonomousIterations - consumedIterations).coerceAtLeast(0)

    companion object {
        val DEFAULT: AutonomyBudget = AutonomyBudget(maxAutonomousIterations = 12)

        /** Каждый шаг требует человека — поведение до появления оркестратора. */
        val NONE: AutonomyBudget = AutonomyBudget(maxAutonomousIterations = 0)
    }
}
