package com.maxvibes.application.service.turn

import com.maxvibes.domain.model.approval.AgentActionKind
import com.maxvibes.domain.model.approval.ApprovalDecision
import com.maxvibes.domain.model.approval.ApprovalSource
import com.maxvibes.domain.model.turn.AutonomyBudget
import com.maxvibes.domain.model.turn.AwaitReason
import com.maxvibes.domain.model.turn.TurnOutcome
import com.maxvibes.domain.model.turn.TurnSignal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TurnAutopilotTest {

    private val allow = ApprovalDecision.Allow(ApprovalSource.POLICY)

    private fun orchestrator(
        budget: AutonomyBudget = AutonomyBudget.DEFAULT,
        decide: (String, AgentActionKind) -> ApprovalDecision = { _, _ -> allow }
    ) = AgentTurnOrchestrator(decideApproval = decide, defaultBudget = budget)

    @Test
    fun `an allowed step continues the turn for its own session`() {
        val continued = mutableListOf<String>()
        val sut = TurnAutopilot(orchestrator(), continueTurn = { sessionId, _ -> continued += sessionId })
        sut.startTurn("s1")

        val outcome = sut.onStep("s1", TurnSignal.Pending(AgentActionKind.VIEW_REQUEST))

        assertTrue(outcome is TurnOutcome.Continue)
        assertEquals(listOf("s1"), continued)
    }

    @Test
    fun `the continuation is told which action to carry out`() {
        val continued = mutableListOf<Pair<String, AgentActionKind?>>()
        val sut = TurnAutopilot(
            orchestrator(),
            continueTurn = { sessionId, action -> continued += sessionId to action }
        )
        sut.startTurn("s1")

        sut.onStep("s1", TurnSignal.Pending(AgentActionKind.COMMAND))

        assertEquals(listOf("s1" to AgentActionKind.COMMAND), continued)
    }

    @Test
    fun `a parked step reports the reason and does not continue`() {
        val continued = mutableListOf<String>()
        val parked = mutableListOf<Pair<AwaitReason, AgentActionKind?>>()
        val sut = TurnAutopilot(
            orchestrator(decide = { _, _ -> ApprovalDecision.Ask }),
            continueTurn = { sessionId, _ -> continued += sessionId },
            onParked = { _, reason, action -> parked += reason to action }
        )
        sut.startTurn("s1")

        sut.onStep("s1", TurnSignal.Pending(AgentActionKind.MODIFICATION))

        assertTrue(continued.isEmpty())
        assertEquals(listOf(AwaitReason.POLICY_ASK to AgentActionKind.MODIFICATION), parked)
        assertEquals(AgentActionKind.MODIFICATION, sut.parkedAction("s1"))
    }

    @Test
    fun `a manual approval is remembered without spending the budget`() {
        val continued = mutableListOf<String>()
        val sut = TurnAutopilot(
            orchestrator(budget = AutonomyBudget(1), decide = { _, _ -> ApprovalDecision.Ask }),
            continueTurn = { sessionId, _ -> continued += sessionId }
        )
        sut.startTurn("s1")
        sut.onStep("s1", TurnSignal.Pending(AgentActionKind.MODIFICATION))

        sut.onHumanApproved("s1")

        assertNull(sut.parkedAction("s1"))
        assertTrue(continued.isEmpty())
    }

    @Test
    fun `approving with nothing parked is a no-op`() {
        val sut = TurnAutopilot(orchestrator(), continueTurn = { _, _ -> })
        sut.startTurn("s1")

        sut.onHumanApproved("s1")

        assertNull(sut.parkedAction("s1"))
    }

    @Test
    fun `questions park the turn without an action`() {
        val parked = mutableListOf<Pair<AwaitReason, AgentActionKind?>>()
        val sut = TurnAutopilot(
            orchestrator(),
            continueTurn = { _, _ -> },
            onParked = { _, reason, action -> parked += reason to action }
        )
        sut.startTurn("s1")

        sut.onStep("s1", TurnSignal.Questions)

        assertEquals(listOf(AwaitReason.AGENT_QUESTIONS to null), parked)
        assertNull(sut.parkedAction("s1"))
    }

    @Test
    fun `a finished turn forgets its state so the next turn starts fresh`() {
        val continued = mutableListOf<String>()
        val sut = TurnAutopilot(
            orchestrator(budget = AutonomyBudget(1)),
            continueTurn = { sessionId, _ -> continued += sessionId }
        )
        sut.startTurn("s1")
        sut.onStep("s1", TurnSignal.Pending(AgentActionKind.VIEW_REQUEST))

        sut.onStep("s1", TurnSignal.Completed)
        val outcome = sut.onStep("s1", TurnSignal.Pending(AgentActionKind.VIEW_REQUEST))

        assertTrue(outcome is TurnOutcome.Continue, "a forgotten turn must get a fresh budget")
        assertEquals(listOf("s1", "s1"), continued)
    }

    @Test
    fun `an aborted turn forgets its state`() {
        val sut = TurnAutopilot(
            orchestrator(decide = { _, _ -> ApprovalDecision.Ask }),
            continueTurn = { _, _ -> }
        )
        sut.startTurn("s1")
        sut.onStep("s1", TurnSignal.Pending(AgentActionKind.MODIFICATION))

        sut.onStep("s1", TurnSignal.Failed("transport died"))

        assertNull(sut.parkedAction("s1"))
    }

    @Test
    fun `sessions keep independent budgets`() {
        val continued = mutableListOf<String>()
        val sut = TurnAutopilot(
            orchestrator(budget = AutonomyBudget(1)),
            continueTurn = { sessionId, _ -> continued += sessionId }
        )
        sut.startTurn("s1")
        sut.startTurn("s2")

        sut.onStep("s1", TurnSignal.Pending(AgentActionKind.VIEW_REQUEST))
        sut.onStep("s2", TurnSignal.Pending(AgentActionKind.VIEW_REQUEST))

        assertEquals(listOf("s1", "s2"), continued)
    }

    @Test
    fun `the approval decision is asked for the stepping session`() {
        val asked = mutableListOf<String>()
        val sut = TurnAutopilot(
            orchestrator(decide = { sessionId, _ ->
                asked += sessionId
                allow
            }),
            continueTurn = { _, _ -> }
        )
        sut.startTurn("s1")
        sut.startTurn("s2")

        sut.onStep("s2", TurnSignal.Pending(AgentActionKind.MODIFICATION))

        assertEquals(listOf("s2"), asked)
    }

    @Test
    fun `a new user message restores the budget`() {
        val continued = mutableListOf<String>()
        val sut = TurnAutopilot(
            orchestrator(budget = AutonomyBudget(1)),
            continueTurn = { sessionId, _ -> continued += sessionId }
        )
        sut.startTurn("s1")
        sut.onStep("s1", TurnSignal.Pending(AgentActionKind.VIEW_REQUEST))
        sut.onStep("s1", TurnSignal.Pending(AgentActionKind.VIEW_REQUEST))

        sut.startTurn("s1")
        val outcome = sut.onStep("s1", TurnSignal.Pending(AgentActionKind.VIEW_REQUEST))

        assertTrue(outcome is TurnOutcome.Continue)
        assertEquals(2, continued.size)
    }

    @Test
    fun `a self feeding agent is stopped by the budget instead of looping forever`() {
        var continues = 0
        var parkedReason: AwaitReason? = null
        lateinit var sut: TurnAutopilot
        sut = TurnAutopilot(
            orchestrator(budget = AutonomyBudget(3)),
            continueTurn = { sessionId, _ ->
                continues++
                sut.onStep(sessionId, TurnSignal.Pending(AgentActionKind.VIEW_REQUEST))
            },
            onParked = { _, reason, _ -> parkedReason = reason }
        )
        sut.startTurn("s1")

        sut.onStep("s1", TurnSignal.Pending(AgentActionKind.VIEW_REQUEST))

        assertEquals(3, continues)
        assertEquals(AwaitReason.BUDGET_EXHAUSTED, parkedReason)
    }

    @Test
    fun `a parked action resumes once the policy starts allowing it`() {
        var decision: ApprovalDecision = ApprovalDecision.Ask
        val continued = mutableListOf<Pair<String, AgentActionKind?>>()
        val sut = TurnAutopilot(
            orchestrator(decide = { _, _ -> decision }),
            continueTurn = { sessionId, action -> continued += sessionId to action }
        )
        sut.startTurn("s1")
        sut.onStep("s1", TurnSignal.Pending(AgentActionKind.COMMAND))
        assertTrue(continued.isEmpty())

        decision = allow
        val outcome = sut.resumeParked("s1")

        assertTrue(outcome is TurnOutcome.Continue)
        assertEquals(listOf("s1" to AgentActionKind.COMMAND), continued)
        assertNull(sut.parkedAction("s1"))
    }

    @Test
    fun `resuming a turn that is not parked changes nothing`() {
        val continued = mutableListOf<String>()
        val sut = TurnAutopilot(
            orchestrator(),
            continueTurn = { sessionId, _ -> continued += sessionId }
        )
        sut.startTurn("s1")

        assertNull(sut.resumeParked("s1"))
        assertTrue(continued.isEmpty())
    }

    @Test
    fun `resuming does not bypass an exhausted budget`() {
        var decision: ApprovalDecision = ApprovalDecision.Ask
        var parkedReason: AwaitReason? = null
        val sut = TurnAutopilot(
            orchestrator(budget = AutonomyBudget(0), decide = { _, _ -> decision }),
            continueTurn = { _, _ -> },
            onParked = { _, reason, _ -> parkedReason = reason }
        )
        sut.startTurn("s1")
        sut.onStep("s1", TurnSignal.Pending(AgentActionKind.MODIFICATION))

        decision = allow
        sut.resumeParked("s1")

        assertEquals(AwaitReason.BUDGET_EXHAUSTED, parkedReason)
        assertEquals(AgentActionKind.MODIFICATION, sut.parkedAction("s1"))
    }
}
