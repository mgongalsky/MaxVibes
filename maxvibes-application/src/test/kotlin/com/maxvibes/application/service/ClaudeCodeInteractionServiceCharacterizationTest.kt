package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.application.port.output.ClaudeCodeError
import com.maxvibes.application.port.output.ClaudeCodePort
import com.maxvibes.application.port.output.ClaudeCodeSendResult
import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.application.port.output.NotificationPort
import com.maxvibes.application.testsupport.FakeProjectContextPort
import com.maxvibes.application.testsupport.FakePromptPort
import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.code.CodeGranularity
import com.maxvibes.domain.model.code.CodeView
import com.maxvibes.domain.model.code.RequestedViewInfo
import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionCommand
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.interaction.InteractionResponse
import com.maxvibes.shared.result.Result
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.ArrayDeque
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClaudeCodeInteractionServiceCharacterizationTest {

    private val sessionId = "session-1"

    private lateinit var repository: InMemoryChatSessionRepository
    private lateinit var contextProvider: FakeProjectContextPort
    private lateinit var transport: RecordingClaudeCodePort
    private lateinit var codeRepository: CodeRepository
    private lateinit var notificationPort: NotificationPort
    private lateinit var sessionManager: ClipboardSessionManager
    private lateinit var service: ClaudeCodeInteractionService

    @BeforeEach
    fun setUp() {
        repository = InMemoryChatSessionRepository()
        contextProvider = FakeProjectContextPort()
        transport = RecordingClaudeCodePort()
        codeRepository = mockk(relaxed = true)
        notificationPort = mockk(relaxed = true)
        sessionManager = ClipboardSessionManager(repository)
        service = ClaudeCodeInteractionService(
            contextProvider = contextProvider,
            claudeCodePort = transport,
            codeRepository = codeRepository,
            notificationPort = notificationPort,
            promptPort = FakePromptPort(),
            sessionManager = sessionManager,
            chatSessionRepository = repository
        )
        repository.saveSession(ChatSession(id = sessionId))
    }

    @Test
    fun `first send carries full context and continuation carries only delta`() = runBlocking {
        transport.enqueueResponse(
            response = InteractionResponse(message = "First completed"),
            observedSessionId = "claude-1"
        )

        assertIs<ClaudeCodeStepResult.Completed>(
            service.handleUserInput(
                sessionId = sessionId,
                userInput = "Initial task"
            )
        )

        val firstRequest = transport.sentRequests.single()
        assertTrue(firstRequest.fileTree.isNotBlank())
        assertEquals(listOf("Initial task"), firstRequest.chatHistory.map { it.content })
        assertEquals("Initial task", firstRequest.currentMessage)

        transport.enqueueResponse(
            InteractionResponse(message = "Second completed")
        )

        assertIs<ClaudeCodeStepResult.Completed>(
            service.handleUserInput(
                sessionId = sessionId,
                userInput = "Follow-up"
            )
        )

        val secondRequest = transport.sentRequests.last()
        assertEquals("", secondRequest.fileTree)
        assertTrue(secondRequest.chatHistory.isEmpty())
        assertEquals("Follow-up", secondRequest.currentMessage)
        assertEquals("claude-1", transport.ensureCalls.last().first)
    }

    @Test
    fun `resume failure starts fresh and rebuilds request with full context`() = runBlocking {
        repository.saveSession(
            ChatSession(
                id = sessionId,
                messages = listOf(
                    ChatMessage(
                        role = MessageRole.USER,
                        content = "Original task"
                    )
                ),
                clipboardStatus = ClipboardSessionStatus.SESSION_ACTIVE,
                claudeCodeSessionId = "old-claude-session",
                claudeCodeNeedsFullContext = false
            )
        )
        transport.enqueueEnsure(
            Result.Failure(
                ClaudeCodeError.ResumeFailed(
                    sessionId = "old-claude-session",
                    stderr = "session not found"
                )
            )
        )
        transport.enqueueEnsure(Result.Success(Unit))
        transport.enqueueResponse(
            response = InteractionResponse(message = "Recovered"),
            observedSessionId = "new-claude-session"
        )

        assertIs<ClaudeCodeStepResult.Completed>(
            service.handleUserInput(
                sessionId = sessionId,
                userInput = "Continue after restart"
            )
        )

        assertEquals(
            listOf("old-claude-session", null),
            transport.ensureCalls.map { it.first }
        )
        val request = transport.sentRequests.single()
        assertTrue(request.fileTree.isNotBlank())
        assertEquals(
            listOf("Original task", "Continue after restart"),
            request.chatHistory.map { it.content }
        )

        val persisted = repository.getSessionById(sessionId)!!
        assertEquals("new-claude-session", persisted.claudeCodeSessionId)
        assertFalse(persisted.claudeCodeNeedsFullContext)
    }

    @Test
    fun `approve applies held modifications and releases held commands`() = runBlocking {
        coEvery { codeRepository.applyModifications(any()) } returns emptyList()
        transport.enqueueResponse(
            InteractionResponse(
                message = "Proposed change",
                modifications = listOf(
                    InteractionModification(
                        type = "CREATE_FILE",
                        path = "file:src/New.kt",
                        content = "class New"
                    )
                ),
                commands = listOf(
                    InteractionCommand(
                        command = "gradlew.bat test",
                        reason = "verify changes"
                    )
                ),
                commitMessage = "feat: add New"
            )
        )

        val proposal = service.handleUserInput(
            sessionId = sessionId,
            userInput = "Create New"
        )
        val awaiting = assertIs<ClaudeCodeStepResult.AwaitingModApprove>(proposal)
        assertEquals(1, awaiting.heldCommands)
        assertEquals(
            ClipboardSessionStatus.AWAITING_APPROVE,
            service.status(sessionId)
        )

        val approved = assertIs<ClaudeCodeStepResult.Completed>(
            service.approve(sessionId)
        )

        assertTrue(approved.success)
        assertEquals("feat: add New", approved.commitMessage)
        assertEquals("gradlew.bat test", approved.commands.single().command)
        assertEquals(
            ClipboardSessionStatus.SESSION_ACTIVE,
            service.status(sessionId)
        )
        coVerify(exactly = 1) {
            codeRepository.applyModifications(match { it.size == 1 })
        }
    }

    @Test
    fun `typing while modifications await approval rejects them and sends feedback`() = runBlocking {
        transport.enqueueResponse(
            InteractionResponse(
                message = "Proposed change",
                modifications = listOf(
                    InteractionModification(
                        type = "CREATE_FILE",
                        path = "file:src/New.kt",
                        content = "class New"
                    )
                )
            )
        )
        assertIs<ClaudeCodeStepResult.AwaitingModApprove>(
            service.handleUserInput(
                sessionId = sessionId,
                userInput = "Create New"
            )
        )

        transport.enqueueResponse(
            InteractionResponse(message = "Understood")
        )
        assertIs<ClaudeCodeStepResult.Completed>(
            service.handleUserInput(
                sessionId = sessionId,
                userInput = "Use a different approach"
            )
        )

        val rejectionRequest = transport.sentRequests.last()
        assertTrue(
            rejectionRequest.currentMessage.contains(
                "USER REJECTED your 1 proposed modification"
            )
        )
        assertTrue(
            rejectionRequest.currentMessage.endsWith(
                "Use a different approach"
            )
        )
        coVerify(exactly = 0) {
            codeRepository.applyModifications(any())
        }
    }

    @Test
    fun `approve restores workspace and resolves full and partial views`() = runBlocking {
        contextProvider.fileContents["src/Full.kt"] = "full body"
        val partialView = mockk<CodeView>()
        every { partialView.content } returns "partial body"
        coEvery {
            codeRepository.getCodeView(
                match {
                    it.filePath == "src/Partial.kt" &&
                            it.granularity == CodeGranularity.SIGNATURES
                }
            )
        } returns partialView

        repository.saveSession(
            ChatSession(
                id = sessionId,
                messages = listOf(
                    ChatMessage(
                        role = MessageRole.USER,
                        content = "Inspect files"
                    ),
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = "Need views",
                        requestedViews = listOf(
                            RequestedViewInfo(
                                path = "src/Full.kt",
                                granularity = CodeGranularity.FULL
                            ),
                            RequestedViewInfo(
                                path = "src/Partial.kt",
                                granularity = CodeGranularity.SIGNATURES
                            )
                        )
                    )
                ),
                clipboardStatus = ClipboardSessionStatus.AWAITING_APPROVE,
                claudeCodeSessionId = "claude-1",
                claudeCodeNeedsFullContext = false
            )
        )
        transport.enqueueResponse(
            InteractionResponse(message = "Views received")
        )

        assertIs<ClaudeCodeStepResult.Completed>(
            service.approve(sessionId)
        )

        val request = transport.sentRequests.single()
        assertEquals(
            mapOf(
                "src/Full.kt" to "full body",
                "src/Partial.kt" to "partial body"
            ),
            request.freshFiles
        )
        assertEquals(
            listOf("src/Full.kt"),
            contextProvider.gatheredPathLists.single()
        )
        assertEquals("Need views", request.currentMessage)
        assertEquals("", request.fileTree)
        coVerify(exactly = 1) {
            codeRepository.getCodeView(any())
        }
    }

    @Test
    fun `command results are forwarded as a minimal protocol continuation`() = runBlocking {
        repository.saveSession(
            ChatSession(
                id = sessionId,
                messages = listOf(
                    ChatMessage(
                        role = MessageRole.USER,
                        content = "Run checks"
                    )
                ),
                clipboardStatus = ClipboardSessionStatus.SESSION_ACTIVE,
                claudeCodeSessionId = "claude-1",
                claudeCodeNeedsFullContext = false
            )
        )
        transport.enqueueResponse(
            InteractionResponse(message = "Checks processed")
        )

        assertIs<ClaudeCodeStepResult.Completed>(
            service.submitCommandResults(
                sessionId = sessionId,
                resultsForLlm = "gradlew.bat test: exit code 0"
            )
        )

        val request = transport.sentRequests.single()
        assertEquals(
            "gradlew.bat test: exit code 0",
            request.commandResults
        )
        assertEquals("", request.fileTree)
        assertTrue(request.chatHistory.isEmpty())
    }

    private class InMemoryChatSessionRepository : ChatSessionRepository {
        private val sessions = mutableMapOf<String, ChatSession>()
        private var activeSessionId: String? = null
        private var globalContextFiles: List<String> = emptyList()

        override fun getAllSessions(): List<ChatSession> =
            sessions.values.toList()

        override fun getSessionById(id: String): ChatSession? =
            sessions[id]

        override fun getActiveSessionId(): String? =
            activeSessionId

        override fun setActiveSessionId(sessionId: String) {
            activeSessionId = sessionId
        }

        override fun saveSession(session: ChatSession) {
            sessions[session.id] = session
        }

        override fun deleteSession(sessionId: String) {
            sessions.remove(sessionId)
        }

        override fun getGlobalContextFiles(): List<String> =
            globalContextFiles

        override fun setGlobalContextFiles(files: List<String>) {
            globalContextFiles = files
        }
    }

    private class RecordingClaudeCodePort : ClaudeCodePort {
        val ensureCalls = mutableListOf<Pair<String?, String?>>()
        val sentRequests = mutableListOf<ClipboardRequest>()

        private val ensureResults =
            ArrayDeque<Result<Unit, ClaudeCodeError>>()
        private val sendResults =
            ArrayDeque<Result<ClaudeCodeSendResult, ClaudeCodeError>>()

        override fun isAvailable(): Boolean = true

        fun enqueueEnsure(result: Result<Unit, ClaudeCodeError>) {
            ensureResults.addLast(result)
        }

        fun enqueueResponse(
            response: InteractionResponse,
            observedSessionId: String? = null
        ) {
            sendResults.addLast(
                Result.Success(
                    ClaudeCodeSendResult(
                        response = response,
                        observedSessionId = observedSessionId
                    )
                )
            )
        }

        override suspend fun ensureStarted(
            resumeSessionId: String?,
            systemPrompt: String?
        ): Result<Unit, ClaudeCodeError> {
            ensureCalls += resumeSessionId to systemPrompt
            return if (ensureResults.isEmpty()) {
                Result.Success(Unit)
            } else {
                ensureResults.removeFirst()
            }
        }

        override suspend fun send(
            request: ClipboardRequest
        ): Result<ClaudeCodeSendResult, ClaudeCodeError> {
            sentRequests += request
            return if (sendResults.isEmpty()) {
                Result.Success(
                    ClaudeCodeSendResult(
                        response = InteractionResponse(message = "Done."),
                        observedSessionId = null
                    )
                )
            } else {
                sendResults.removeFirst()
            }
        }

        override fun shutdown() = Unit
    }
}
