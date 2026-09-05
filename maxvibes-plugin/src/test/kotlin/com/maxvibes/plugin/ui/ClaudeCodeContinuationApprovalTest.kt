package com.maxvibes.plugin.ui

import com.maxvibes.application.service.ChatTreeService
import com.maxvibes.application.service.ClaudeCodeStepResult
import com.maxvibes.application.service.CodingAgentInteractionService
import com.maxvibes.application.service.turn.AgentTurnOrchestrator
import com.maxvibes.application.service.turn.TurnAutopilot
import com.maxvibes.domain.model.approval.AgentActionKind
import com.maxvibes.domain.model.approval.ApprovalDecision
import com.maxvibes.domain.model.turn.TurnSignal
import com.maxvibes.plugin.testsupport.FakeChatPanelCallbacks
import com.maxvibes.plugin.testsupport.InMemoryChatSessionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClaudeCodeContinuationApprovalTest {
    @Test
    fun `manual continuation sends next turn with attachments instead of approving files`() = runBlocking {
        val service = mockk<CodingAgentInteractionService>()
        coEvery {
            service.handleUserInput(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns ClaudeCodeStepResult.Error("test result")
        val tree = ChatTreeService(InMemoryChatSessionRepository())
        val sessionId = tree.getActiveSession().id
        val callbacks = FakeChatPanelCallbacks()
        val autopilot = TurnAutopilot(
            AgentTurnOrchestrator({ _, _ -> ApprovalDecision.Ask }),
            continueTurn = { _, _ -> error("Must wait for manual approval") }
        )
        autopilot.startTurn(sessionId)
        autopilot.onStep(sessionId, TurnSignal.Pending(AgentActionKind.CONTINUATION))
        val actions = mutableListOf<suspend () -> ClaudeCodeStepResult>()
        val dispatcher = ClaudeCodeDispatcher(
            claudeCodeService = { service }, resolveSpecificPrompt = { null },
            chatTreeService = tree, callbacks = callbacks,
            presentQuestions = {}, presentCommands = { _, _, _ -> },
            executeAsync = { _, _, action -> actions.add(action) },
            turnAutopilot = { autopilot }
        )

        dispatcher.approve("trace", "errors")
        actions.single().invoke()

        assertNull(autopilot.parkedAction(sessionId))
        assertTrue(callbacks.userBubbles.isEmpty())
        coVerify(exactly = 1) {
            service.handleUserInput(
                sessionId = sessionId,
                userInput = match { it.startsWith("[USER APPROVED CONTINUATION]") },
                attachedContext = "trace", ideErrors = "errors"
            )
        }
        coVerify(exactly = 0) { service.approve(any(), any(), any(), any()) }
    }

    @Test
    fun `manual file approval still uses the approval service`() = runBlocking {
        val service = mockk<CodingAgentInteractionService>()
        coEvery { service.approve(any(), any(), any(), any()) } returns
                ClaudeCodeStepResult.Error("test result")
        val tree = ChatTreeService(InMemoryChatSessionRepository())
        val sessionId = tree.getActiveSession().id
        val autopilot = TurnAutopilot(
            AgentTurnOrchestrator({ _, _ -> ApprovalDecision.Ask }),
            continueTurn = { _, _ -> error("Must wait for manual approval") }
        )
        autopilot.startTurn(sessionId)
        autopilot.onStep(sessionId, TurnSignal.Pending(AgentActionKind.VIEW_REQUEST))
        val actions = mutableListOf<suspend () -> ClaudeCodeStepResult>()
        val dispatcher = ClaudeCodeDispatcher(
            claudeCodeService = { service }, resolveSpecificPrompt = { null },
            chatTreeService = tree, callbacks = FakeChatPanelCallbacks(),
            presentQuestions = {}, presentCommands = { _, _, _ -> },
            executeAsync = { _, _, action -> actions.add(action) },
            turnAutopilot = { autopilot }
        )

        dispatcher.approve("trace", "errors")
        actions.single().invoke()

        assertNull(autopilot.parkedAction(sessionId))
        coVerify(exactly = 1) {
            service.approve(sessionId, attachedContext = "trace", ideErrors = "errors")
        }
        coVerify(exactly = 0) {
            service.handleUserInput(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }
}
