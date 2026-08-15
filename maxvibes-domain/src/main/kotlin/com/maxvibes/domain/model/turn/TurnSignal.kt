package com.maxvibes.domain.model.turn

import com.maxvibes.domain.model.approval.AgentActionKind

/**
 * Что агент вернул на очередном шаге — в терминах хода, а не транспорта.
 *
 * Транспортный слой переводит свой результат в этот тип, поэтому оркестратор
 * не знает ни про Claude Code, ни про Codex и проверяется без них.
 */
sealed interface TurnSignal {

    /** Агент предложил действие, требующее разрешения: файлы, модификации или команды. */
    data class Pending(val action: AgentActionKind) : TurnSignal

    /** Агент задал вопросы — отвечает только человек, политика тут ни при чём. */
    object Questions : TurnSignal

    /** Агент вернул только текст: продолжать нечего. */
    object Completed : TurnSignal

    /** Шаг не удался. */
    data class Failed(val cause: String) : TurnSignal
}

/** Новое состояние хода и решение, кому ход принадлежит дальше. */
data class TurnTransition(
    val turn: AgentTurn,
    val outcome: TurnOutcome
)
