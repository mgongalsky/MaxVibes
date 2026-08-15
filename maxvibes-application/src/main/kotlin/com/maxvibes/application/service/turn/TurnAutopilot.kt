package com.maxvibes.application.service.turn

import com.maxvibes.domain.model.approval.AgentActionKind
import com.maxvibes.domain.model.turn.AgentTurn
import com.maxvibes.domain.model.turn.AwaitReason
import com.maxvibes.domain.model.turn.TurnOutcome
import com.maxvibes.domain.model.turn.TurnSignal
import java.util.concurrent.ConcurrentHashMap

/**
 * Держит ход между шагами и приводит решение оркестратора в действие.
 *
 * Знает только две лямбды и ни одного UI-типа, поэтому проверяется без IDE.
 * [startTurn] зовётся из UI-потока, а [onStep] — из колбэка фоновой задачи,
 * отсюда конкурентные структуры.
 *
 * [continueTurn] получает вид действия: автопилот решает, *можно ли* продолжать,
 * а *как* именно продолжать — знает тот, кто его собрал.
 */
class TurnAutopilot(
    private val orchestrator: AgentTurnOrchestrator,
    private val continueTurn: (sessionId: String, action: AgentActionKind?) -> Unit,
    private val onParked: (sessionId: String, reason: AwaitReason, action: AgentActionKind?) -> Unit =
        { _, _, _ -> }
) {

    private val turns = ConcurrentHashMap<String, AgentTurn>()
    private val parkedActions = ConcurrentHashMap<String, AgentActionKind>()

    /** Новое сообщение пользователя начинает ход заново: бюджет автономии восстанавливается. */
    fun startTurn(sessionId: String) {
        turns[sessionId] = orchestrator.begin(sessionId)
        parkedActions.remove(sessionId)
    }

    /**
     * Состояние хода записывается ДО [continueTurn] — если продолжение окажется
     * синхронным, следующий шаг увидит уже потраченный бюджет и цикл оборвётся.
     */
    fun onStep(sessionId: String, signal: TurnSignal): TurnOutcome {
        val turn = turns[sessionId] ?: orchestrator.begin(sessionId)
        val transition = orchestrator.advance(turn, signal)
        turns[sessionId] = transition.turn

        when (val outcome = transition.outcome) {
            is TurnOutcome.Continue -> {
                parkedActions.remove(sessionId)
                continueTurn(sessionId, outcome.next.action)
            }

            is TurnOutcome.AwaitHuman -> {
                val action = outcome.action
                if (action != null) parkedActions[sessionId] = action else parkedActions.remove(sessionId)
                onParked(sessionId, outcome.reason, action)
            }

            TurnOutcome.Finished, is TurnOutcome.Aborted -> forget(sessionId)
        }

        return transition.outcome
    }

    /**
     * Пересчитывает решение по припаркованному действию — например, после того
     * как пользователь поднял уровень доверия. Возвращает null, если парковки нет.
     *
     * Сигнал прогоняется через оркестратор заново, поэтому бюджет автономии
     * продолжает действовать: снятие с ручника не то же самое, что обход правил.
     */
    fun resumeParked(sessionId: String): TurnOutcome? {
        val action = parkedActions[sessionId] ?: return null
        return onStep(sessionId, TurnSignal.Pending(action))
    }

    /** Человек нажал Approve на припаркованном действии: шаг ручной и бюджет не тратит. */
    fun onHumanApproved(sessionId: String) {
        val action = parkedActions.remove(sessionId) ?: return
        val turn = turns[sessionId] ?: return
        turns[sessionId] = orchestrator.resumeAfterHuman(turn, action).turn
    }

    fun parkedAction(sessionId: String): AgentActionKind? = parkedActions[sessionId]

    fun forget(sessionId: String) {
        turns.remove(sessionId)
        parkedActions.remove(sessionId)
    }
}
