package com.maxvibes.application.service.turn

import com.maxvibes.domain.model.approval.AgentActionKind
import com.maxvibes.domain.model.approval.ApprovalDecision
import com.maxvibes.domain.model.turn.AgentTurn
import com.maxvibes.domain.model.turn.AutonomyBudget
import com.maxvibes.domain.model.turn.AwaitReason
import com.maxvibes.domain.model.turn.TurnOutcome
import com.maxvibes.domain.model.turn.TurnSignal
import com.maxvibes.domain.model.turn.TurnTransition

/**
 * Владелец хода: после каждого шага решает, продолжает ли агент сам или ход
 * переходит человеку.
 *
 * Машина синхронная и без побочных эффектов — транспорт и UI подставляет
 * вызывающий, поэтому все переходы проверяются юнит-тестами без IDE.
 *
 * [decideApproval] получает сессию хода, а не только вид действия: разрешение
 * может зависеть от сессии (тумблер «разрешать всё» действует только на свою),
 * и брать её из «текущей открытой» было бы догадкой.
 */
class AgentTurnOrchestrator(
    private val decideApproval: (sessionId: String, kind: AgentActionKind) -> ApprovalDecision,
    /** Открыт наружу, чтобы автопилот отдавал этот же бюджет и не заводил второй дефолт. */
    val defaultBudget: AutonomyBudget = AutonomyBudget.DEFAULT
) {

    fun begin(sessionId: String, budget: AutonomyBudget = defaultBudget): AgentTurn =
        AgentTurn(sessionId = sessionId, budget = budget)

    fun advance(turn: AgentTurn, signal: TurnSignal): TurnTransition = when (signal) {
        is TurnSignal.Pending -> advancePending(turn, signal.action)
        TurnSignal.Questions -> park(turn, AwaitReason.AGENT_QUESTIONS, action = null)
        TurnSignal.Completed -> TurnTransition(turn, TurnOutcome.Finished)
        is TurnSignal.Failed -> TurnTransition(turn, TurnOutcome.Aborted(signal.cause))
    }

    /**
     * Человек разрешил припаркованное действие.
     *
     * Шаг записывается как ручной и бюджет автономии не тратит: бюджет защищает
     * от цикла без человека, а человек только что вмешался.
     */
    fun resumeAfterHuman(turn: AgentTurn, action: AgentActionKind): TurnTransition {
        val step = turn.nextStep(action = action, automatic = false)
        return TurnTransition(turn.record(step), TurnOutcome.Continue(step))
    }

    /**
     * Человек разблокировал ход, остановленный именно исчерпанным бюджетом.
     *
     * Бюджет восстанавливается только если автономное продолжение вообще
     * разрешено: иначе один ручной Approve молча включал бы автономный режим,
     * которого пользователь не включал. Разрешение спрашивается у той же точки,
     * что и всегда, поэтому сессионный тумблер и политика проекта учитываются
     * оба и не могут разъехаться.
     */
    fun resumeAfterBudgetExhaustion(turn: AgentTurn, action: AgentActionKind): TurnTransition {
        val resumed = resumeAfterHuman(turn, action)
        if (decideApproval(turn.sessionId, AgentActionKind.CONTINUATION) == ApprovalDecision.Ask) {
            return resumed
        }
        return resumed.copy(turn = resumed.turn.refillBudget())
    }

    /**
     * Политика проверяется раньше бюджета: если человека всё равно спрашивают,
     * причиной парковки должна быть политика, а не лимит автономии.
     */
    private fun advancePending(turn: AgentTurn, action: AgentActionKind): TurnTransition {
        if (decideApproval(turn.sessionId, action) == ApprovalDecision.Ask) {
            return park(turn, AwaitReason.POLICY_ASK, action)
        }
        if (!turn.canProceedAutomatically()) {
            return park(turn, AwaitReason.BUDGET_EXHAUSTED, action)
        }
        val step = turn.nextStep(action = action, automatic = true)
        return TurnTransition(turn.record(step), TurnOutcome.Continue(step))
    }

    private fun park(turn: AgentTurn, reason: AwaitReason, action: AgentActionKind?): TurnTransition =
        TurnTransition(turn, TurnOutcome.AwaitHuman(reason, action))
}
