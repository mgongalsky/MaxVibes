package com.maxvibes.application.service.turn

import com.maxvibes.domain.model.approval.AgentActionKind
import com.maxvibes.domain.model.turn.AgentTurn
import com.maxvibes.domain.model.turn.AutonomyBudget
import com.maxvibes.domain.model.turn.AwaitReason
import com.maxvibes.domain.model.turn.TurnOutcome
import com.maxvibes.domain.model.turn.TurnSignal
import java.util.concurrent.ConcurrentHashMap

/**
 * Держит ход между шагами и приводит решение оркестратора в действие.
 *
 * Знает только лямбды и ни одного UI-типа, поэтому проверяется без IDE.
 * [startTurn] зовётся из UI-потока, а [onStep] — из колбэка фоновой задачи,
 * отсюда конкурентные структуры.
 *
 * [continueTurn] получает вид действия: автопилот решает, *можно ли* продолжать,
 * а *как* именно продолжать — знает тот, кто его собрал.
 *
 * [budget] спрашивается на старте каждого хода, а не запоминается: лимит
 * автономии редактируется прямо в панели чата, и следующий цикл должен идти
 * уже по новому значению, без перезапуска IDE.
 */
class TurnAutopilot(
    private val orchestrator: AgentTurnOrchestrator,
    private val continueTurn: (sessionId: String, action: AgentActionKind?) -> Unit,
    private val onParked: (sessionId: String, reason: AwaitReason, action: AgentActionKind?) -> Unit =
        { _, _, _ -> },
    private val budget: () -> AutonomyBudget = { orchestrator.defaultBudget }
) {

    /** Причина хранится вместе с действием: от неё зависит, вернёт ли Approve бюджет. */
    private data class Parked(val action: AgentActionKind, val reason: AwaitReason)

    private val turns = ConcurrentHashMap<String, AgentTurn>()
    private val parked = ConcurrentHashMap<String, Parked>()

    /** Новое сообщение пользователя начинает ход заново: бюджет автономии восстанавливается. */
    fun startTurn(sessionId: String) {
        turns[sessionId] = orchestrator.begin(sessionId, budget())
        parked.remove(sessionId)
    }

    /**
     * Состояние хода записывается ДО [continueTurn] — если продолжение окажется
     * синхронным, следующий шаг увидит уже потраченный бюджет и цикл оборвётся.
     */
    fun onStep(sessionId: String, signal: TurnSignal): TurnOutcome {
        val turn = turns[sessionId] ?: orchestrator.begin(sessionId, budget())
        val transition = orchestrator.advance(turn, signal)
        turns[sessionId] = transition.turn

        when (val outcome = transition.outcome) {
            is TurnOutcome.Continue -> {
                parked.remove(sessionId)
                continueTurn(sessionId, outcome.next.action)
            }

            is TurnOutcome.AwaitHuman -> {
                val action = outcome.action
                if (action != null) {
                    parked[sessionId] = Parked(action, outcome.reason)
                } else {
                    parked.remove(sessionId)
                }
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
        val action = parked[sessionId]?.action ?: return null
        return onStep(sessionId, TurnSignal.Pending(action))
    }

    /**
     * Человек нажал Approve на припаркованном действии: сам шаг ручной и бюджет не тратит.
     *
     * Если ход остановил именно исчерпанный бюджет, а автономия разрешена, счёт
     * итераций начинается заново и работа продолжается сама. Остановка по политике
     * так не действует: там человека спросили не из-за лимита, и разрешать одно
     * действие не значит выдавать новый автономный цикл.
     */
    fun onHumanApproved(sessionId: String) {
        val approved = parked.remove(sessionId) ?: return
        val turn = turns[sessionId] ?: return
        turns[sessionId] = when (approved.reason) {
            AwaitReason.BUDGET_EXHAUSTED ->
                orchestrator.resumeAfterBudgetExhaustion(turn, approved.action).turn

            AwaitReason.POLICY_ASK, AwaitReason.AGENT_QUESTIONS ->
                orchestrator.resumeAfterHuman(turn, approved.action).turn
        }
    }

    fun parkedAction(sessionId: String): AgentActionKind? = parked[sessionId]?.action

    fun forget(sessionId: String) {
        turns.remove(sessionId)
        parked.remove(sessionId)
    }
}
