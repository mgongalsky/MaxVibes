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
     * Ход, остановленный лимитом автономии, одним поднятием доверия не сдвинуть:
     * политика начала бы разрешать, а счётчик итераций остался бы на нуле, и шаг
     * припарковался бы снова с той же причиной — тумблер выглядел бы сломанным.
     * Поэтому бюджет сначала пополняется, на тех же условиях, что и при ручном
     * разрешении шага.
     *
     * Дальше сигнал всё равно прогоняется через оркестратор, поэтому остальные
     * правила продолжают действовать: снятие с ручника не то же самое, что обход.
     */
    fun resumeParked(sessionId: String): TurnOutcome? {
        val park = parked[sessionId] ?: return null
        if (park.reason == AwaitReason.BUDGET_EXHAUSTED) {
            turns[sessionId]?.let { turns[sessionId] = orchestrator.refillIfAllowed(it) }
        }
        return onStep(sessionId, TurnSignal.Pending(park.action))
    }

    /**
     * Человек принял решение по припаркованному действию: Approve в панели либо
     * Run или Decline на пузыре команды или проверки. Шаг считается ручным и
     * бюджет автономии не тратит.
     *
     * Отказ засчитывается наравне с запуском. Бюджет страхует от цикла *без*
     * человека, а человек только что вмешался; иначе один отклонённый билд
     * оставлял бы счётчик на нуле до конца хода, и дальше пришлось бы
     * подтверждать каждый шаг руками.
     *
     * Если ход остановил именно исчерпанный бюджет, а автономия разрешена, счёт
     * итераций начинается заново и работа продолжается сама. Остановка по политике
     * так не действует: там человека спросили не из-за лимита, и разрешать одно
     * действие не значит выдавать новый автономный цикл.
     *
     * Вызов на сессии без парковки ничего не делает — так автоматически
     * запущенная пачка команд может звать этот вход, не проверяя, кто её начал.
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
