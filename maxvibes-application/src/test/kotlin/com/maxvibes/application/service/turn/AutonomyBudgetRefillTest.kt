package com.maxvibes.application.service.turn

import com.maxvibes.domain.model.approval.AgentActionKind
import com.maxvibes.domain.model.approval.ApprovalDecision
import com.maxvibes.domain.model.approval.ApprovalSource
import com.maxvibes.domain.model.turn.AutonomyBudget
import com.maxvibes.domain.model.turn.AwaitReason
import com.maxvibes.domain.model.turn.TurnOutcome
import com.maxvibes.domain.model.turn.TurnSignal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Что делает ручной Approve с бюджетом автономии.
 *
 * Разделение здесь тонкое: подтверждение действия, остановленного лимитом,
 * начинает новый автономный цикл, а подтверждение действия, остановленного
 * политикой, остаётся одноразовым.
 */
class AutonomyBudgetRefillTest {

    private val allow = ApprovalDecision.Allow(ApprovalSource.POLICY)

    private fun orchestrator(
        budget: AutonomyBudget = AutonomyBudget.DEFAULT,
        decide: (String, AgentActionKind) -> ApprovalDecision = { _, _ -> allow }
    ) = AgentTurnOrchestrator(decideApproval = decide, defaultBudget = budget)

    @Test
    fun `approve after budget exhaustion starts a new autonomous cycle when auto is on`() {
        val sut = TurnAutopilot(orchestrator(budget = AutonomyBudget(1)), continueTurn = { _, _ -> })
        sut.startTurn("s1")
        sut.onStep("s1", TurnSignal.Pending(AgentActionKind.CONTINUATION))
        val exhausted = sut.onStep("s1", TurnSignal.Pending(AgentActionKind.CONTINUATION))
        assertEquals(
            TurnOutcome.AwaitHuman(AwaitReason.BUDGET_EXHAUSTED, AgentActionKind.CONTINUATION),
            exhausted
        )

        sut.onHumanApproved("s1")
        val outcome = sut.onStep("s1", TurnSignal.Pending(AgentActionKind.CONTINUATION))

        assertTrue(outcome is TurnOutcome.Continue, "the budget must be back after a manual unblock")
    }

    @Test
    fun `approve stays one-shot when continuation is not allowed`() {
        val sut = TurnAutopilot(
            orchestrator(
                budget = AutonomyBudget.NONE,
                decide = { _, kind ->
                    if (kind == AgentActionKind.CONTINUATION) ApprovalDecision.Ask else allow
                }
            ),
            continueTurn = { _, _ -> }
        )
        sut.startTurn("s1")
        sut.onStep("s1", TurnSignal.Pending(AgentActionKind.MODIFICATION))

        sut.onHumanApproved("s1")
        val outcome = sut.onStep("s1", TurnSignal.Pending(AgentActionKind.MODIFICATION))

        assertEquals(
            TurnOutcome.AwaitHuman(AwaitReason.BUDGET_EXHAUSTED, AgentActionKind.MODIFICATION),
            outcome,
            "approving one action must not switch autonomy on"
        )
    }

    @Test
    fun `approve on a policy ask does not hand out a new autonomous cycle`() {
        val sut = TurnAutopilot(
            orchestrator(
                budget = AutonomyBudget(1),
                decide = { _, kind ->
                    if (kind == AgentActionKind.MODIFICATION) ApprovalDecision.Ask else allow
                }
            ),
            continueTurn = { _, _ -> }
        )
        sut.startTurn("s1")
        sut.onStep("s1", TurnSignal.Pending(AgentActionKind.CONTINUATION))
        sut.onStep("s1", TurnSignal.Pending(AgentActionKind.MODIFICATION))

        sut.onHumanApproved("s1")
        val outcome = sut.onStep("s1", TurnSignal.Pending(AgentActionKind.CONTINUATION))

        assertEquals(
            TurnOutcome.AwaitHuman(AwaitReason.BUDGET_EXHAUSTED, AgentActionKind.CONTINUATION),
            outcome,
            "a policy stop is not a budget stop and must not refill it"
        )
    }

    @Test
    fun `refilling the budget keeps the journal of the turn`() {
        val sut = orchestrator(budget = AutonomyBudget(1))
        var turn = sut.begin("s1")
        turn = sut.advance(turn, TurnSignal.Pending(AgentActionKind.CONTINUATION)).turn

        val resumed = sut.resumeAfterBudgetExhaustion(turn, AgentActionKind.CONTINUATION).turn

        assertEquals(0, resumed.autonomousIterationCount)
        assertEquals(2, resumed.steps.size)
    }
}
