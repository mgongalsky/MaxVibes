package com.maxvibes.application.service.turn

import com.maxvibes.application.port.output.LoggerPort
import com.maxvibes.domain.model.approval.AgentActionKind
import com.maxvibes.domain.model.turn.AgentTurn
import com.maxvibes.domain.model.turn.AutonomyBudget
import com.maxvibes.domain.model.turn.AwaitReason
import com.maxvibes.domain.model.turn.TurnOutcome
import com.maxvibes.domain.model.turn.TurnSignal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Держит ход между шагами и приводит решение оркестратора в действие.
 * Состояние записывается до вызова продолжения, которое может быть синхронным.
 * Лимит читается на старте каждого хода, чтобы учитывать изменения настроек.
 */
class TurnAutopilot(
    private val orchestrator: AgentTurnOrchestrator,
    private val continueTurn: (sessionId: String, action: AgentActionKind?) -> Unit,
    private val onParked: (sessionId: String, reason: AwaitReason, action: AgentActionKind?) -> Unit =
        { _, _, _ -> },
    private val budget: () -> AutonomyBudget = { orchestrator.defaultBudget },
    private val logger: LoggerPort? = null
) {
    private data class Parked(val action: AgentActionKind, val reason: AwaitReason)

    private val turns = ConcurrentHashMap<String, AgentTurn>()
    private val parked = ConcurrentHashMap<String, Parked>()
    private val turnIds = ConcurrentHashMap<String, String>()

    /** Новое сообщение пользователя начинает ход заново и восстанавливает бюджет. */
    fun startTurn(sessionId: String) {
        val previous = turns[sessionId]
        val previousPark = parked[sessionId]
        val turn = orchestrator.begin(sessionId, budget())
        turns[sessionId] = turn
        parked.remove(sessionId)
        val previousTurnId = turnIds.put(sessionId, UUID.randomUUID().toString())
        trace("turn_started", turn, mapOf(
            "origin" to "user_message",
            "previousTurnId" to previousTurnId,
            "previousUsed" to previous?.autonomousIterationCount,
            "previousAwaitReason" to previousPark?.reason?.name
        ))
    }

    fun onStep(sessionId: String, signal: TurnSignal): TurnOutcome {
        val turn = turns[sessionId] ?: orchestrator.begin(sessionId, budget()).also {
            turnIds[sessionId] = UUID.randomUUID().toString()
            trace("turn_started", it, mapOf("origin" to "missing_turn"))
        }
        val transition = orchestrator.advance(turn, signal)
        turns[sessionId] = transition.turn

        when (val outcome = transition.outcome) {
            is TurnOutcome.Continue -> {
                parked.remove(sessionId)
                trace("decision", transition.turn, mapOf(
                    "action" to outcome.next.action?.name,
                    "outcome" to "AUTO_CONTINUE",
                    "step" to outcome.next.ordinal,
                    "usedBefore" to turn.autonomousIterationCount
                ))
                continueTurn(sessionId, outcome.next.action)
            }
            is TurnOutcome.AwaitHuman -> {
                val action = outcome.action
                if (action != null) {
                    parked[sessionId] = Parked(action, outcome.reason)
                } else {
                    parked.remove(sessionId)
                }
                trace("decision", transition.turn, mapOf(
                    "action" to action?.name,
                    "outcome" to "AWAIT_HUMAN",
                    "reason" to outcome.reason.name
                ))
                onParked(sessionId, outcome.reason, action)
            }
            TurnOutcome.Finished, is TurnOutcome.Aborted -> {
                trace("decision", transition.turn, mapOf(
                    "outcome" to if (outcome == TurnOutcome.Finished) "FINISHED" else "ABORTED"
                ))
                forget(sessionId)
            }
        }
        return transition.outcome
    }

    /** Перепроверяет припаркованное действие после изменения доверия. */
    fun resumeParked(sessionId: String): TurnOutcome? {
        val park = parked[sessionId]
        trace("resume_requested", turns[sessionId], mapOf(
            "sessionId" to sessionId,
            "action" to park?.action?.name,
            "reason" to park?.reason?.name,
            "hasPendingAction" to (park != null)
        ))
        if (park == null) return null
        if (park.reason == AwaitReason.BUDGET_EXHAUSTED) {
            turns[sessionId]?.let {
                val refilled = orchestrator.refillIfAllowed(it)
                turns[sessionId] = refilled
                trace("budget_rechecked", refilled, mapOf("usedBefore" to it.autonomousIterationCount))
            }
        }
        return onStep(sessionId, TurnSignal.Pending(park.action))
    }

    /**
     * Ручное решение по припаркованному действию не расходует бюджет.
     * После остановки по бюджету он восстанавливается, только если автономия разрешена.
     * Координаторы вызывают этот метод также после Decline и автоматически выполненных
     * пачек; без парковки вызов ничего не меняет.
     */
    fun onHumanApproved(sessionId: String) {
        val approved = parked.remove(sessionId) ?: return
        val turn = turns[sessionId]
        if (turn == null) {
            trace("human_resolution_ignored", null, mapOf(
                "sessionId" to sessionId,
                "action" to approved.action.name,
                "reason" to "MISSING_TURN"
            ))
            return
        }
        val resumed = when (approved.reason) {
            AwaitReason.BUDGET_EXHAUSTED ->
                orchestrator.resumeAfterBudgetExhaustion(turn, approved.action).turn
            AwaitReason.POLICY_ASK, AwaitReason.AGENT_QUESTIONS ->
                orchestrator.resumeAfterHuman(turn, approved.action).turn
        }
        turns[sessionId] = resumed
        trace("human_resolution", resumed, mapOf(
            "action" to approved.action.name,
            "awaitReason" to approved.reason.name,
            "usedBefore" to turn.autonomousIterationCount
        ))
    }

    fun parkedAction(sessionId: String): AgentActionKind? = parked[sessionId]?.action

    fun forget(sessionId: String) {
        val turn = turns.remove(sessionId)
        val park = parked.remove(sessionId)
        if (turn != null || park != null) {
            trace("turn_forgotten", turn, mapOf(
                "sessionId" to sessionId,
                "action" to park?.action?.name,
                "awaitReason" to park?.reason?.name
            ))
        }
        turnIds.remove(sessionId)
    }

    /** Diagnostics must not change approval behavior, even when a logger fails. */
    private fun trace(event: String, turn: AgentTurn?, details: Map<String, Any?> = emptyMap()) {
        val sink = logger ?: return
        val sessionId = turn?.sessionId ?: details["sessionId"] as? String
        runCatching {
            sink.info("Autonomy", event, mapOf(
                "sessionId" to sessionId,
                "turnId" to sessionId?.let { turnIds[it] },
                "step" to turn?.steps?.size,
                "used" to turn?.autonomousIterationCount,
                "limit" to turn?.budget?.maxAutonomousIterations
            ) + details)
        }
    }
}
