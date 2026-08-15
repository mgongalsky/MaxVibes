package com.maxvibes.domain.model.turn

import com.maxvibes.domain.model.approval.AgentActionKind

/**
 * Почему ход остановлен и передан человеку.
 *
 * Отдельный тип, а не текст: UI выбирает по нему разную подачу — предложить
 * апрув, показать исчерпанный бюджет или вывести вопросы агента.
 */
enum class AwaitReason {
    /** Политика для этого вида действия — ASK. */
    POLICY_ASK,

    /** Автоматические шаги кончились, дальше только вручную. */
    BUDGET_EXHAUSTED,

    /** Агент сам задал вопросы и ждёт ответа. */
    AGENT_QUESTIONS
}

/**
 * Результат одного шага хода с точки зрения оркестратора.
 *
 * Возвращается наружу вместо того, чтобы оркестратор сам дёргал UI: так весь
 * цикл проверяется юнит-тестами без Swing и без транспорта.
 */
sealed interface TurnOutcome {

    /** Ход остаётся у агента: следующий шаг разрешён и может быть выполнен сразу. */
    data class Continue(val next: TurnStep) : TurnOutcome

    /** Ход передан человеку. [action] — что именно ждёт решения, null для [AwaitReason.AGENT_QUESTIONS]. */
    data class AwaitHuman(
        val reason: AwaitReason,
        val action: AgentActionKind? = null
    ) : TurnOutcome

    /** Агент завершил работу: продолжать нечего. */
    object Finished : TurnOutcome

    /** Ход прерван ошибкой или отменой пользователя. */
    data class Aborted(val cause: String) : TurnOutcome
}
