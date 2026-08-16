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

class AgentTurnOrchestratorTest {

    private val allow = ApprovalDecision.Allow(ApprovalSource.POLICY)

    private fun orchestrator(
        budget: AutonomyBudget = AutonomyBudget.DEFAULT,
        decide: (String, AgentActionKind) -> ApprovalDecision = { _, _ -> allow }
    ) = AgentTurnOrchestrator(decideApproval = decide, defaultBudget = budget)

    @Test
    fun `begin creates an empty turn with the given budget`() {
        val turn = orchestrator(budget = AutonomyBudget(3)).begin("s1")

        assertEquals("s1", turn.sessionId)
        assertEquals(AutonomyBudget(3), turn.budget)
        assertTrue(turn.steps.isEmpty())
    }

    @Test
    fun `the decision is asked for the session that owns the turn`() {
        val asked = mutableListOf<Pair<String, AgentActionKind>>()
        val sut = orchestrator(decide = { sessionId, kind ->
            asked += sessionId to kind
            allow
        })

        sut.advance(sut.begin("s42"), TurnSignal.Pending(AgentActionKind.COMMAND))

        assertEquals(listOf("s42" to AgentActionKind.COMMAND), asked)
    }

    @Test
    fun `an allowed view request continues the turn without spending budget`() {
        val sut = orchestrator(budget = AutonomyBudget(2))
        val turn = sut.begin("s1")

        val transition = sut.advance(turn, TurnSignal.Pending(AgentActionKind.VIEW_REQUEST))

        val outcome = transition.outcome as TurnOutcome.Continue
        assertEquals(AgentActionKind.VIEW_REQUEST, outcome.next.action)
        assertTrue(outcome.next.automatic)
        assertEquals(0, transition.turn.autonomousIterationCount)
    }

    @Test
    fun `only a continuation spends an iteration of the budget`() {
        val sut = orchestrator(budget = AutonomyBudget(2))
        var turn = sut.begin("s1")

        listOf(
            AgentActionKind.VIEW_REQUEST,
            AgentActionKind.MODIFICATION,
            AgentActionKind.COMMAND,
            AgentActionKind.BUILD,
            AgentActionKind.TESTS
        ).forEach { action ->
            turn = sut.advance(turn, TurnSignal.Pending(action)).turn
        }
        assertEquals(0, turn.autonomousIterationCount)

        turn = sut.advance(turn, TurnSignal.Pending(AgentActionKind.CONTINUATION)).turn

        assertEquals(1, turn.autonomousIterationCount)
        assertEquals(6, turn.steps.size)
    }

    @Test
    fun `ask policy parks the turn and leaves the log untouched`() {
        val sut = orchestrator(decide = { _, _ -> ApprovalDecision.Ask })
        val turn = sut.begin("s1")

        val transition = sut.advance(turn, TurnSignal.Pending(AgentActionKind.MODIFICATION))

        assertEquals(
            TurnOutcome.AwaitHuman(AwaitReason.POLICY_ASK, AgentActionKind.MODIFICATION),
            transition.outcome
        )
        assertTrue(transition.turn.steps.isEmpty())
    }

    @Test
    fun `exhausted budget parks even an allowed action`() {
        val sut = orchestrator(budget = AutonomyBudget.NONE)
        val turn = sut.begin("s1")

        val transition = sut.advance(turn, TurnSignal.Pending(AgentActionKind.COMMAND))

        assertEquals(
            TurnOutcome.AwaitHuman(AwaitReason.BUDGET_EXHAUSTED, AgentActionKind.COMMAND),
            transition.outcome
        )
    }

    @Test
    fun `ask policy outranks an exhausted budget`() {
        val sut = orchestrator(budget = AutonomyBudget.NONE, decide = { _, _ -> ApprovalDecision.Ask })
        val turn = sut.begin("s1")

        val transition = sut.advance(turn, TurnSignal.Pending(AgentActionKind.COMMAND))

        assertEquals(
            TurnOutcome.AwaitHuman(AwaitReason.POLICY_ASK, AgentActionKind.COMMAND),
            transition.outcome
        )
    }

    @Test
    fun `agent questions always go to the human`() {
        val sut = orchestrator()
        val turn = sut.begin("s1")

        val transition = sut.advance(turn, TurnSignal.Questions)

        assertEquals(TurnOutcome.AwaitHuman(AwaitReason.AGENT_QUESTIONS, null), transition.outcome)
    }

    @Test
    fun `completion ends the turn`() {
        val sut = orchestrator()

        val transition = sut.advance(sut.begin("s1"), TurnSignal.Completed)

        assertEquals(TurnOutcome.Finished, transition.outcome)
    }

    @Test
    fun `failure aborts the turn with the cause`() {
        val sut = orchestrator()

        val transition = sut.advance(sut.begin("s1"), TurnSignal.Failed("transport died"))

        assertEquals(TurnOutcome.Aborted("transport died"), transition.outcome)
    }

    @Test
    fun `manual approval continues the turn without spending budget`() {
        val sut = orchestrator(budget = AutonomyBudget.NONE)
        val turn = sut.begin("s1")

        val transition = sut.resumeAfterHuman(turn, AgentActionKind.MODIFICATION)

        val outcome = transition.outcome as TurnOutcome.Continue
        assertTrue(!outcome.next.automatic)
        assertEquals(0, transition.turn.autonomousIterationCount)
        assertEquals(1, transition.turn.steps.size)
    }

    @Test
    fun `policy is applied per action kind`() {
        val sut = orchestrator(decide = { _, kind ->
            if (kind == AgentActionKind.VIEW_REQUEST) allow else ApprovalDecision.Ask
        })
        val turn = sut.begin("s1")

        val views = sut.advance(turn, TurnSignal.Pending(AgentActionKind.VIEW_REQUEST))
        val mods = sut.advance(turn, TurnSignal.Pending(AgentActionKind.MODIFICATION))

        assertTrue(views.outcome is TurnOutcome.Continue)
        assertEquals(
            TurnOutcome.AwaitHuman(AwaitReason.POLICY_ASK, AgentActionKind.MODIFICATION),
            mods.outcome
        )
    }

    @Test
    fun `budget runs out after a series of autonomous iterations`() {
        val sut = orchestrator(budget = AutonomyBudget(2))
        var turn = sut.begin("s1")

        repeat(2) {
            val transition = sut.advance(turn, TurnSignal.Pending(AgentActionKind.CONTINUATION))
            assertTrue(transition.outcome is TurnOutcome.Continue)
            turn = transition.turn
        }

        val parked = sut.advance(turn, TurnSignal.Pending(AgentActionKind.CONTINUATION))

        assertEquals(
            TurnOutcome.AwaitHuman(AwaitReason.BUDGET_EXHAUSTED, AgentActionKind.CONTINUATION),
            parked.outcome
        )
        assertEquals(2, turn.autonomousIterationCount)
    }
}
