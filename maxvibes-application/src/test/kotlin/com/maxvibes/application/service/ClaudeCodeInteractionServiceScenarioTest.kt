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
import com.maxvibes.domain.model.interaction.InteractionResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Scenario matrix from STEP_2: drives the real service + real [ClipboardSessionManager]
 * over recording fakes, one test per protocol scenario.
 */
class ClaudeCodeInteractionServiceScenarioTest {

    private val sessionId = "session-1"

    private lateinit var repo: FakeChatSessionRepository
    private lateinit var port: FakeClaudeCodePort
    private lateinit var contextPort: FakeProjectContextPort
    private lateinit var codeRepo: FakeCodeRepository
    private lateinit var notifications: FakeNotificationPort
    private lateinit var sessionManager: ClipboardSessionManager

    @BeforeEach
    fun setUp() {
        repo = FakeChatSessionRepository()
        port = FakeClaudeCodePort()
        contextPort = FakeProjectContextPort()
        codeRepo = FakeCodeRepository()
        notifications = FakeNotificationPort()
        sessionManager = ClipboardSessionManager(repo)
        repo.saveSession(ChatSession(id = sessionId))
    }

    private fun newService() = ClaudeCodeInteractionService(
        contextProvider = contextPort,
        claudeCodePort = port,
        codeRepository = codeRepo,
        notificationPort = notifications,
        promptPort = FakePromptPort(),
        sessionManager = sessionManager,
        chatSessionRepository = repo
    )

    @Test
    fun `full cycle - views, approve, files delivered, completed`() = runBlocking {
        val service = newService()
        contextPort.fileContents["src/Foo.kt"] = "class Foo"
        port.enqueueResponse(
            InteractionResponse(
                message = "Need Foo first",
                codeViewRequests = listOf(CodeViewRequest("src/Foo.kt", CodeGranularity.FULL))
            )
        )

        val step1 = service.handleUserInput(sessionId, "Refactor Foo")

        val waiting = assertIs<ClaudeCodeStepResult.WaitingForApprove>(step1)
        assertEquals(listOf("src/Foo.kt"), waiting.requestedViews.map { it.path })
        assertEquals(ClipboardSessionStatus.AWAITING_APPROVE, service.status(sessionId))

        // Emulate the UI: it persists the ASSISTANT message with its requestedViews.
        repo.saveSession(
            repo.getSessionById(sessionId)!!.withMessage(
                ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = "Need Foo first",
                    requestedViews = listOf(RequestedViewInfo("src/Foo.kt", CodeGranularity.FULL, null))
                )
            )
        )

        port.enqueueResponse(InteractionResponse(message = "Done, no changes needed"))
        val step2 = service.approve(sessionId)

        assertIs<ClaudeCodeStepResult.Completed>(step2)
        assertEquals(ClipboardSessionStatus.SESSION_ACTIVE, service.status(sessionId))
        assertEquals(2, port.sentRequests.size)
        assertEquals(mapOf("src/Foo.kt" to "class Foo"), port.sentRequests[1].freshFiles)
    }

    @Test
    fun `pending modifications - approve applies them and releases held commands`() = runBlocking {
        val service = newService()
        port.enqueueResponse(
            InteractionResponse(
                message = "Proposing a new file",
                modifications = listOf(
                    InteractionModification(type = "CREATE_FILE", path = "file:src/New.kt", content = "class New")
                ),
                commands = listOf(InteractionCommand(command = "gradlew test", reason = "verify")),
                commitMessage = "feat: add New"
            )
        )

        val step1 = service.handleUserInput(sessionId, "Add New")

        val awaiting = assertIs<ClaudeCodeStepResult.AwaitingModApprove>(step1)
        assertEquals(1, awaiting.proposedModifications.size)
        assertEquals(1, awaiting.heldCommands)
        assertEquals(ClipboardSessionStatus.AWAITING_APPROVE, service.status(sessionId))
        assertEquals(0, codeRepo.appliedBatches.size)

        val step2 = service.approve(sessionId)

        val completed = assertIs<ClaudeCodeStepResult.Completed>(step2)
        assertTrue(completed.success)
        assertEquals("feat: add New", completed.commitMessage)
        assertEquals(1, completed.commands.size)
        assertEquals("gradlew test", completed.commands.first().command)
        assertEquals(1, codeRepo.appliedBatches.size)
        assertEquals(ClipboardSessionStatus.SESSION_ACTIVE, service.status(sessionId))
        // approve of pending mods must not trigger a second LLM send
        assertEquals(1, port.sentRequests.size)
    }

    @Test
    fun `pending modifications - new message rejects with feedback prefix`() = runBlocking {
        val service = newService()
        port.enqueueResponse(
            InteractionResponse(
                message = "Proposing a new file",
                modifications = listOf(
                    InteractionModification(type = "CREATE_FILE", path = "file:src/New.kt", content = "class New")
                )
            )
        )
        service.handleUserInput(sessionId, "Add New")

        port.enqueueResponse(InteractionResponse(message = "OK, doing X instead"))
        val step2 = service.handleUserInput(sessionId, "Actually do X instead")

        assertIs<ClaudeCodeStepResult.Completed>(step2)
        assertEquals(0, codeRepo.appliedBatches.size)
        assertEquals(ClipboardSessionStatus.SESSION_ACTIVE, service.status(sessionId))
        val rejectMessage = port.sentRequests[1].currentMessage
        assertTrue(rejectMessage.startsWith("[USER REJECTED your 1 proposed modification(s)"))
        assertTrue(rejectMessage.contains("Actually do X instead"))
    }

    @Test
    fun `commands turn - completed with commands, submitCommandResults continues`() = runBlocking {
        val service = newService()
        port.enqueueResponse(
            InteractionResponse(
                message = "Running tests",
                commands = listOf(InteractionCommand(command = "gradlew test", reason = "verify"))
            )
        )

        val step1 = service.handleUserInput(sessionId, "Build and test")

        val completed = assertIs<ClaudeCodeStepResult.Completed>(step1)
        assertEquals(1, completed.commands.size)
        assertEquals(ClipboardSessionStatus.SESSION_ACTIVE, service.status(sessionId))

        port.enqueueResponse(InteractionResponse(message = "All green"))
        val step2 = service.submitCommandResults(sessionId, "exit code 0")

        assertIs<ClaudeCodeStepResult.Completed>(step2)
        assertEquals(2, port.sentRequests.size)
        assertEquals("exit code 0", port.sentRequests[1].commandResults)
    }

    @Test
    fun `mixed response - commands with views skips commands`() = runBlocking {
        val service = newService()
        port.enqueueResponse(
            InteractionResponse(
                message = "Need file and want to run command",
                codeViewRequests = listOf(CodeViewRequest("src/Foo.kt", CodeGranularity.FULL)),
                commands = listOf(InteractionCommand(command = "gradlew test", reason = "verify"))
            )
        )

        val step = service.handleUserInput(sessionId, "task")

        val waiting = assertIs<ClaudeCodeStepResult.WaitingForApprove>(step)
        assertEquals(1, waiting.skippedCommands)
        assertEquals(ClipboardSessionStatus.AWAITING_APPROVE, service.status(sessionId))
    }

    @Test
    fun `mixed response - views with modifications skips views`() = runBlocking {
        val service = newService()
        port.enqueueResponse(
            InteractionResponse(
                message = "Changing and asking at once",
                codeViewRequests = listOf(CodeViewRequest("src/Foo.kt", CodeGranularity.FULL)),
                modifications = listOf(
                    InteractionModification(type = "CREATE_FILE", path = "file:src/New.kt", content = "class New")
                )
            )
        )

        val step = service.handleUserInput(sessionId, "task")

        val awaiting = assertIs<ClaudeCodeStepResult.AwaitingModApprove>(step)
        assertEquals(1, awaiting.skippedViews)
        assertEquals(ClipboardSessionStatus.AWAITING_APPROVE, service.status(sessionId))
    }

    @Test
    fun `workspace restore - new service instance continues an active session`() = runBlocking {
        repo.saveSession(
            repo.getSessionById(sessionId)!!
                .withMessage(ChatMessage(role = MessageRole.USER, content = "earlier task"))
                .withClipboardStatus(ClipboardSessionStatus.SESSION_ACTIVE)
        )
        val service = newService()
        port.enqueueResponse(InteractionResponse(message = "Continuing where we left off"))

        val step = service.handleUserInput(sessionId, "continue")

        assertIs<ClaudeCodeStepResult.Completed>(step)
        assertEquals(1, port.sentRequests.size)
        assertEquals(ClipboardSessionStatus.SESSION_ACTIVE, service.status(sessionId))
    }
}
