package com.maxvibes.application.service

import com.maxvibes.application.testsupport.FakeChatSessionRepository
import com.maxvibes.application.testsupport.FakeClaudeCodePort
import com.maxvibes.application.testsupport.FakeCodeRepository
import com.maxvibes.application.testsupport.FakeNotificationPort
import com.maxvibes.application.testsupport.FakeProjectContextPort
import com.maxvibes.application.testsupport.FakePromptPort
import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.code.CodeGranularity
import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.domain.model.code.RequestedViewInfo
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionCommand
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.interaction.InteractionQuestion
import com.maxvibes.domain.model.interaction.InteractionResponse
import com.maxvibes.domain.model.planning.PlanDiagram
import com.maxvibes.domain.model.planning.PlanStep
import com.maxvibes.domain.model.planning.TaskPlan
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pin tests for processResponse branches NOT covered by the scenario matrix:
 * plan snapshot semantics, questions turn, requestedViews persistence,
 * reasoning combination, blank-message fallback, plan-only mode, diagram passthrough.
 *
 * All tests go through the public [ClaudeCodeInteractionService.handleUserInput] so the
 * STEP 2A extraction of ResponseProcessor must keep them green without touching asserts.
 */
class ClaudeCodeInteractionServicePinTest {

    private val sessionId = "session-1"

    private lateinit var repo: FakeChatSessionRepository
    private lateinit var port: FakeClaudeCodePort
    private lateinit var contextPort: FakeProjectContextPort
    private lateinit var codeRepo: FakeCodeRepository
    private lateinit var service: ClaudeCodeInteractionService

    @BeforeEach
    fun setUp() {
        repo = FakeChatSessionRepository()
        port = FakeClaudeCodePort()
        contextPort = FakeProjectContextPort()
        codeRepo = FakeCodeRepository()
        repo.saveSession(ChatSession(id = sessionId))
        service = ClaudeCodeInteractionService(
            contextProvider = contextPort,
            claudeCodePort = port,
            codeRepository = codeRepo,
            notificationPort = FakeNotificationPort(),
            promptPort = FakePromptPort(),
            sessionManager = ClipboardSessionManager(repo),
            chatSessionRepository = repo
        )
    }

    private fun somePlan() = TaskPlan(
        title = "Feature X",
        steps = listOf(PlanStep(id = "1", title = "Do it"))
    )

    @Test
    fun `plan snapshot with steps replaces session plan`() = runBlocking {
        port.enqueueResponse(InteractionResponse(message = "ok", plan = somePlan()))

        service.handleUserInput(sessionId, "task")

        val saved = repo.getSessionById(sessionId)!!.plan
        assertNotNull(saved)
        assertEquals("Feature X", saved.title)
        assertEquals(listOf("1"), saved.steps.map { it.id })
    }

    @Test
    fun `plan snapshot with empty steps clears session plan`() = runBlocking {
        repo.saveSession(repo.getSessionById(sessionId)!!.withPlan(somePlan()))
        port.enqueueResponse(InteractionResponse(message = "ok", plan = TaskPlan(title = "cleared")))

        service.handleUserInput(sessionId, "task")

        assertNull(repo.getSessionById(sessionId)!!.plan)
    }

    @Test
    fun `absent plan field leaves session plan unchanged`() = runBlocking {
        repo.saveSession(repo.getSessionById(sessionId)!!.withPlan(somePlan()))
        port.enqueueResponse(InteractionResponse(message = "ok"))

        service.handleUserInput(sessionId, "task")

        assertEquals("Feature X", repo.getSessionById(sessionId)!!.plan?.title)
    }

    @Test
    fun `questions turn returns AwaitingQuestions and keeps session active`() = runBlocking {
        port.enqueueResponse(
            InteractionResponse(
                message = "Which one?",
                questions = listOf(InteractionQuestion(id = "q1", question = "A or B?", options = listOf("A", "B")))
            )
        )

        val step = service.handleUserInput(sessionId, "task")

        val awaiting = assertIs<ClaudeCodeStepResult.AwaitingQuestions>(step)
        assertEquals(listOf("q1"), awaiting.questions.map { it.id })
        assertEquals(ClipboardSessionStatus.SESSION_ACTIVE, service.status(sessionId))
    }

    @Test
    fun `commands mixed with questions are skipped - result is AwaitingQuestions`() = runBlocking {
        port.enqueueResponse(
            InteractionResponse(
                message = "Which one?",
                questions = listOf(InteractionQuestion(id = "q1", question = "A or B?")),
                commands = listOf(InteractionCommand(command = "gradlew test", reason = "verify"))
            )
        )

        val step = service.handleUserInput(sessionId, "task")

        assertIs<ClaudeCodeStepResult.AwaitingQuestions>(step)
    }

    @Test
    fun `requested views are persisted into last assistant domain message`() = runBlocking {
        repo.saveSession(
            repo.getSessionById(sessionId)!!.withMessage(
                ChatMessage(role = MessageRole.ASSISTANT, content = "earlier answer")
            )
        )
        port.enqueueResponse(
            InteractionResponse(
                message = "need file",
                codeViewRequests = listOf(CodeViewRequest("src/Foo.kt", CodeGranularity.FULL))
            )
        )

        service.handleUserInput(sessionId, "task")

        val lastAssistant = repo.getSessionById(sessionId)!!.messages.last { it.role == MessageRole.ASSISTANT }
        assertEquals(
            listOf(RequestedViewInfo("src/Foo.kt", CodeGranularity.FULL, null)),
            lastAssistant.requestedViews
        )
    }

    @Test
    fun `views persistence is a no-op when session has no assistant message`() = runBlocking {
        port.enqueueResponse(
            InteractionResponse(
                message = "need file",
                codeViewRequests = listOf(CodeViewRequest("src/Foo.kt", CodeGranularity.FULL))
            )
        )

        val step = service.handleUserInput(sessionId, "task")

        assertIs<ClaudeCodeStepResult.WaitingForApprove>(step)
        assertTrue(repo.getSessionById(sessionId)!!.messages.all { it.requestedViews.isEmpty() })
    }

    @Test
    fun `thinking text and llm reasoning are combined with blank line`() = runBlocking {
        port.enqueueResponse(
            InteractionResponse(message = "ok", reasoning = "because R"),
            thinkingText = "thinking T"
        )

        val step = service.handleUserInput(sessionId, "task")

        val completed = assertIs<ClaudeCodeStepResult.Completed>(step)
        assertEquals("thinking T\n\nbecause R", completed.llmReasoning)
    }

    @Test
    fun `blank message falls back to Done`() = runBlocking {
        port.enqueueResponse(InteractionResponse(message = ""))

        val step = service.handleUserInput(sessionId, "task")

        val completed = assertIs<ClaudeCodeStepResult.Completed>(step)
        assertEquals("Done.", completed.message)
    }

    @Test
    fun `plan-only mode does not hold or apply modifications`() = runBlocking {
        port.enqueueResponse(
            InteractionResponse(
                message = "discussion",
                modifications = listOf(
                    InteractionModification(type = "CREATE_FILE", path = "file:src/New.kt", content = "class New")
                )
            )
        )

        val step = service.handleUserInput(sessionId, "task", planOnly = true)

        val completed = assertIs<ClaudeCodeStepResult.Completed>(step)
        assertTrue(completed.modifications.isEmpty())
        assertTrue(completed.success)
        assertEquals(0, codeRepo.appliedBatches.size)
        assertEquals(ClipboardSessionStatus.SESSION_ACTIVE, service.status(sessionId))
    }

    @Test
    fun `diagram is passed through to the step result`() = runBlocking {
        port.enqueueResponse(
            InteractionResponse(message = "with diagram", diagram = PlanDiagram(title = "cut"))
        )

        val step = service.handleUserInput(sessionId, "task")

        val completed = assertIs<ClaudeCodeStepResult.Completed>(step)
        assertEquals("cut", completed.diagram?.title)
    }
}
