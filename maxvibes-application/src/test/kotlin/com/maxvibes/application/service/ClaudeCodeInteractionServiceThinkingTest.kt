package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.application.port.output.ClaudeCodePort
import com.maxvibes.application.port.output.ClaudeCodeSendResult
import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.application.port.output.NotificationPort
import com.maxvibes.application.port.output.ProjectContextPort
import com.maxvibes.application.port.output.PromptPort
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.code.CodeGranularity
import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.domain.model.context.FileNode
import com.maxvibes.domain.model.context.FileTree
import com.maxvibes.domain.model.context.ProjectContext
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionResponse
import com.maxvibes.shared.result.Result
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * ThinkingBubble: verifies that the full CLI thinking text delivered via
 * [ClaudeCodeSendResult.thinkingText] is merged into the step result's
 * `llmReasoning` — CLI thinking first, then the JSON `reasoning` field —
 * for both Completed and WaitingForApprove outcomes.
 *
 * Harness: all ports are MockK mocks; [ClaudeCodeActivityTracker] is real
 * (plain in-memory class); [ClipboardSessionManager] is mocked with a
 * constant IDLE status so every test drives the first-message path.
 * [processResponse] is shared by all paths, so the merge logic is fully
 * covered regardless of entry point.
 */
class ClaudeCodeInteractionServiceThinkingTest {

    private val sessionId = "session-1"

    private lateinit var contextProvider: ProjectContextPort
    private lateinit var claudeCodePort: ClaudeCodePort
    private lateinit var codeRepository: CodeRepository
    private lateinit var notificationPort: NotificationPort
    private lateinit var promptPort: PromptPort
    private lateinit var sessionManager: ClipboardSessionManager
    private lateinit var chatSessionRepository: ChatSessionRepository
    private lateinit var service: ClaudeCodeInteractionService

    @BeforeEach
    fun setUp() {
        contextProvider = mockk()
        claudeCodePort = mockk()
        codeRepository = mockk()
        notificationPort = mockk(relaxed = true)
        promptPort = mockk()
        sessionManager = mockk()
        chatSessionRepository = mockk()

        coEvery { contextProvider.getProjectContext() } returns Result.Success(testProjectContext())
        every { promptPort.claudeCodeSystem() } returns "CLAUDE CODE SYSTEM PROMPT"
        every { sessionManager.statusFor(sessionId) } returns ClipboardSessionStatus.IDLE
        every { sessionManager.transition(sessionId, any()) } returns true
        every { chatSessionRepository.getSessionById(sessionId) } returns ChatSession(id = sessionId)
        every { chatSessionRepository.saveSession(any()) } just Runs
        coEvery { claudeCodePort.ensureStarted(any(), any()) } returns Result.Success(Unit)

        service = ClaudeCodeInteractionService(
            contextProvider = contextProvider,
            claudeCodePort = claudeCodePort,
            codeRepository = codeRepository,
            notificationPort = notificationPort,
            promptPort = promptPort,
            logger = null,
            sessionManager = sessionManager,
            chatSessionRepository = chatSessionRepository,
            activityTracker = ClaudeCodeActivityTracker(),
            sessionLog = null
        )
    }

    // ── Completed ───────────────────────────────────────────────────────────

    @Test
    fun `cli thinking alone lands in Completed llmReasoning`() = runBlocking {
        stubSend(
            ClaudeCodeSendResult(
                response = InteractionResponse(message = "done"),
                observedSessionId = "claude-sid",
                thinkingText = "FULL THOUGHTS"
            )
        )

        val result = service.handleUserInput(sessionId, "do something")

        assertTrue(result is ClaudeCodeStepResult.Completed)
        result as ClaudeCodeStepResult.Completed
        assertEquals("done", result.message)
        assertEquals("FULL THOUGHTS", result.llmReasoning)
    }

    @Test
    fun `cli thinking merges before json reasoning`() = runBlocking {
        stubSend(
            ClaudeCodeSendResult(
                response = InteractionResponse(message = "done", reasoning = "json reasoning"),
                observedSessionId = "claude-sid",
                thinkingText = "FULL THOUGHTS"
            )
        )

        val result = service.handleUserInput(sessionId, "do something") as ClaudeCodeStepResult.Completed

        assertEquals("FULL THOUGHTS\n\njson reasoning", result.llmReasoning)
    }

    @Test
    fun `no thinking and no reasoning yields null llmReasoning`() = runBlocking {
        stubSend(
            ClaudeCodeSendResult(
                response = InteractionResponse(message = "done"),
                observedSessionId = "claude-sid"
            )
        )

        val result = service.handleUserInput(sessionId, "do something") as ClaudeCodeStepResult.Completed

        assertNull(result.llmReasoning)
    }

    // ── WaitingForApprove ───────────────────────────────────────────────────

    @Test
    fun `thinking is preserved on WaitingForApprove`() = runBlocking {
        stubSend(
            ClaudeCodeSendResult(
                response = InteractionResponse(
                    message = "need files",
                    codeViewRequests = listOf(
                        CodeViewRequest(
                            filePath = "src/main/kotlin/Foo.kt",
                            granularity = CodeGranularity.FULL,
                            elementPath = null
                        )
                    )
                ),
                observedSessionId = "claude-sid",
                thinkingText = "FULL THOUGHTS"
            )
        )

        val result = service.handleUserInput(sessionId, "do something")

        assertTrue(result is ClaudeCodeStepResult.WaitingForApprove)
        assertEquals("FULL THOUGHTS", (result as ClaudeCodeStepResult.WaitingForApprove).llmReasoning)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun stubSend(payload: ClaudeCodeSendResult) {
        coEvery { claudeCodePort.send(any(), any()) } returns Result.Success(payload)
    }

    private fun testProjectContext() = ProjectContext(
        name = "TestProject",
        rootPath = "C:/test",
        fileTree = FileTree(
            root = FileNode(name = "TestProject", path = "", isDirectory = true),
            totalFiles = 0,
            totalDirectories = 1
        )
    )
}
