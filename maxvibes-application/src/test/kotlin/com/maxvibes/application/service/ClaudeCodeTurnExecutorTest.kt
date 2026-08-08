package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.application.port.output.ClaudeCodeError
import com.maxvibes.application.port.output.ClaudeCodeSendResult
import com.maxvibes.application.port.output.PromptTemplates
import com.maxvibes.application.testsupport.FakeProjectContextPort
import com.maxvibes.application.testsupport.InMemoryChatSessionRepository
import com.maxvibes.application.testsupport.RecordingClaudeCodePort
import com.maxvibes.application.testsupport.RecordingClaudeCodeSessionLogPort
import com.maxvibes.application.testsupport.RecordingNotificationPort
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.interaction.InteractionResponse
import com.maxvibes.shared.result.Result
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClaudeCodeTurnExecutorTest {
    private val sessionId = "session-1"

    private lateinit var repository: InMemoryChatSessionRepository
    private lateinit var transport: RecordingClaudeCodePort
    private lateinit var notifications: RecordingNotificationPort
    private lateinit var sessionLog: RecordingClaudeCodeSessionLogPort
    private lateinit var executor: ClaudeCodeTurnExecutor

    @BeforeEach
    fun setUp() {
        repository = InMemoryChatSessionRepository()
        transport = RecordingClaudeCodePort()
        notifications = RecordingNotificationPort()
        sessionLog = RecordingClaudeCodeSessionLogPort()
        executor = ClaudeCodeTurnExecutor(
            claudeCodePort = transport,
            chatSessionRepository = repository,
            notificationPort = notifications,
            sessionLog = sessionLog
        )
    }

    @Test
    fun `missing domain session returns application error without touching transport`() = runBlocking {
        val result = executor.execute(
            ClaudeCodeTurnCommand(sessionId = sessionId),
            state()
        )

        val failure = assertIs<CodingAgentTurnExecutionResult.Failure>(result)
        assertEquals(
            "Session not found: $sessionId",
            assertIs<ClaudeCodeStepResult.Error>(failure.result).message
        )
        assertTrue(transport.ensureCalls.isEmpty())
        assertTrue(transport.sentRequests.isEmpty())
        assertTrue(notifications.progress.isEmpty())
    }

    @Test
    fun `first message sends full context even when persisted flag is false`() = runBlocking {
        repository.put(
            ChatSession(
                id = sessionId,
                claudeCodeNeedsFullContext = false
            )
        )
        transport.enqueueResponse(
            InteractionResponse(message = "Done"),
            observedSessionId = "claude-1"
        )
        val state = state()

        val result = executor.execute(
            ClaudeCodeTurnCommand(
                sessionId = sessionId,
                firstMessage = true
            ),
            state
        )

        assertIs<CodingAgentTurnExecutionResult.Success>(result)
        val request = transport.sentRequests.single()
        assertTrue(request.fileTree.isNotBlank())
        assertEquals(listOf("Task"), request.chatHistory.map { it.content })
        assertEquals("Task", request.currentMessage)
        assertEquals(null, transport.ensureCalls.single().resumeSessionId)
        assertEquals("SYSTEM", transport.ensureCalls.single().systemPrompt)
        assertEquals(1, notifications.progress.size)
    }

    @Test
    fun `normal continuation sends minimal request and preserves delta fields`() = runBlocking {
        repository.put(
            ChatSession(
                id = sessionId,
                claudeCodeSessionId = "claude-1",
                claudeCodeNeedsFullContext = false
            )
        )
        transport.enqueueResponse(InteractionResponse(message = "Done"))

        val result = executor.execute(
            ClaudeCodeTurnCommand(
                sessionId = sessionId,
                freshFiles = mapOf("src/Foo.kt" to "class Foo"),
                attachedContext = "selected code",
                ideErrors = "compiler error",
                specificPromptContent = "review carefully",
                commandResults = "tests passed"
            ),
            state(message = "Continue")
        )

        assertIs<CodingAgentTurnExecutionResult.Success>(result)
        val request = transport.sentRequests.single()
        assertEquals("", request.fileTree)
        assertTrue(request.chatHistory.isEmpty())
        assertEquals("Continue", request.currentMessage)
        assertEquals(mapOf("src/Foo.kt" to "class Foo"), request.freshFiles)
        assertEquals("selected code", request.attachedContext)
        assertEquals("compiler error", request.ideErrors)
        assertEquals("review carefully", request.specificPrompt)
        assertEquals("tests passed", request.commandResults)
        assertEquals("claude-1", transport.ensureCalls.single().resumeSessionId)
    }

    @Test
    fun `persisted full context flag forces complete replay on continuation`() = runBlocking {
        repository.put(
            ChatSession(
                id = sessionId,
                claudeCodeSessionId = "claude-1",
                claudeCodeNeedsFullContext = true
            )
        )
        transport.enqueueResponse(InteractionResponse(message = "Recovered"))

        val result = executor.execute(
            ClaudeCodeTurnCommand(sessionId = sessionId),
            state(message = "Continue")
        )

        assertIs<CodingAgentTurnExecutionResult.Success>(result)
        val request = transport.sentRequests.single()
        assertTrue(request.fileTree.isNotBlank())
        assertEquals(listOf("Continue"), request.chatHistory.map { it.content })
    }

    @Test
    fun `ensure startup failure returns transport error and never sends request`() = runBlocking {
        repository.put(ChatSession(id = sessionId))
        transport.enqueueEnsure(
            Result.Failure(ClaudeCodeError.BinaryNotFound)
        )

        val result = executor.execute(
            ClaudeCodeTurnCommand(sessionId = sessionId),
            state()
        )

        val failure = assertIs<CodingAgentTurnExecutionResult.Failure>(result)
        assertEquals(
            "Claude Code binary not found. Check the path in MaxVibes settings.",
            assertIs<ClaudeCodeStepResult.TransportError>(failure.result).detail
        )
        assertTrue(transport.sentRequests.isEmpty())
        assertTrue(notifications.progress.isEmpty())
    }

    @Test
    fun `resume failure retries fresh with full request and persists new observed session`() = runBlocking {
        repository.put(
            ChatSession(
                id = sessionId,
                claudeCodeSessionId = "old-claude",
                claudeCodeNeedsFullContext = false
            )
        )
        transport.enqueueEnsure(
            Result.Failure(
                ClaudeCodeError.ResumeFailed(
                    sessionId = "old-claude",
                    stderr = "not found"
                )
            )
        )
        transport.enqueueEnsure(Result.Success(Unit))
        transport.enqueueResponse(
            response = InteractionResponse(message = "Recovered"),
            observedSessionId = "new-claude"
        )

        val result = executor.execute(
            ClaudeCodeTurnCommand(sessionId = sessionId),
            state(message = "Continue")
        )

        assertIs<CodingAgentTurnExecutionResult.Success>(result)
        assertEquals(
            listOf("old-claude", null),
            transport.ensureCalls.map { it.resumeSessionId }
        )
        val request = transport.sentRequests.single()
        assertTrue(request.fileTree.isNotBlank())
        assertEquals(listOf("Continue"), request.chatHistory.map { it.content })

        assertTrue(repository.savedSessions.size >= 2)
        val fallbackSnapshot = repository.savedSessions.first()
        assertEquals(null, fallbackSnapshot.claudeCodeSessionId)
        assertTrue(fallbackSnapshot.claudeCodeNeedsFullContext)

        val persisted = repository.getSessionById(sessionId)!!
        assertEquals("new-claude", persisted.claudeCodeSessionId)
        assertFalse(persisted.claudeCodeNeedsFullContext)
        assertTrue(
            sessionLog.events.any {
                it.text == "resume failed — falling back to fresh start"
            }
        )
    }

    @Test
    fun `failure of fresh start after resume failure returns second transport error`() = runBlocking {
        repository.put(
            ChatSession(
                id = sessionId,
                claudeCodeSessionId = "old-claude",
                claudeCodeNeedsFullContext = false
            )
        )
        transport.enqueueEnsure(
            Result.Failure(
                ClaudeCodeError.ResumeFailed(
                    sessionId = "old-claude",
                    stderr = "missing"
                )
            )
        )
        transport.enqueueEnsure(
            Result.Failure(
                ClaudeCodeError.ProcessFailed(
                    exitCode = 2,
                    stderr = "startup failed"
                )
            )
        )

        val result = executor.execute(
            ClaudeCodeTurnCommand(sessionId = sessionId),
            state()
        )

        val failure = assertIs<CodingAgentTurnExecutionResult.Failure>(result)
        val detail = assertIs<ClaudeCodeStepResult.TransportError>(failure.result).detail
        assertTrue(detail.contains("exited with code 2"))
        assertTrue(detail.contains("startup failed"))
        assertTrue(transport.sentRequests.isEmpty())
        val persisted = repository.getSessionById(sessionId)!!
        assertEquals(null, persisted.claudeCodeSessionId)
        assertTrue(persisted.claudeCodeNeedsFullContext)
    }

    @Test
    fun `send failure is mapped and records failure event`() = runBlocking {
        repository.put(
            ChatSession(
                id = sessionId,
                claudeCodeNeedsFullContext = false
            )
        )
        transport.enqueueSend(
            Result.Failure(ClaudeCodeError.Timeout)
        )

        val result = executor.execute(
            ClaudeCodeTurnCommand(sessionId = sessionId),
            state()
        )

        val failure = assertIs<CodingAgentTurnExecutionResult.Failure>(result)
        assertEquals(
            "Claude Code did not respond in time.",
            assertIs<ClaudeCodeStepResult.TransportError>(failure.result).detail
        )
        assertEquals(1, transport.sentRequests.size)
        assertTrue(
            sessionLog.events.any {
                it.text == "send failed" && it.data?.get("error") != null
            }
        )
    }

    @Test
    fun `successful send persists observed session id`() = runBlocking {
        repository.put(
            ChatSession(
                id = sessionId,
                claudeCodeNeedsFullContext = true
            )
        )
        transport.enqueueResponse(
            response = InteractionResponse(message = "Done"),
            observedSessionId = "claude-new"
        )

        val result = executor.execute(
            ClaudeCodeTurnCommand(sessionId = sessionId),
            state()
        )

        assertIs<CodingAgentTurnExecutionResult.Success>(result)
        val persisted = repository.getSessionById(sessionId)!!
        assertEquals("claude-new", persisted.claudeCodeSessionId)
        assertFalse(persisted.claudeCodeNeedsFullContext)
    }

    @Test
    fun `successful full replay without observed id retains previous id and clears flag`() = runBlocking {
        repository.put(
            ChatSession(
                id = sessionId,
                claudeCodeSessionId = "claude-existing",
                claudeCodeNeedsFullContext = true
            )
        )
        transport.enqueueSend(
            Result.Success(
                ClaudeCodeSendResult(
                    response = InteractionResponse(message = "Done"),
                    observedSessionId = null
                )
            )
        )

        val result = executor.execute(
            ClaudeCodeTurnCommand(sessionId = sessionId),
            state()
        )

        assertIs<CodingAgentTurnExecutionResult.Success>(result)
        val persisted = repository.getSessionById(sessionId)!!
        assertEquals("claude-existing", persisted.claudeCodeSessionId)
        assertFalse(persisted.claudeCodeNeedsFullContext)
    }

    @Test
    fun `shutdown delegates once and suppresses transport exception`() {
        transport.shutdownFailure = IllegalStateException("already dead")

        executor.shutdown()

        assertEquals(1, transport.shutdownCalls)
    }

    private fun state(
        message: String = "Task"
    ) = ClipboardSessionState(
        currentMessage = message,
        projectContext = FakeProjectContextPort.defaultContext(),
        dialogHistory = mutableListOf(
            ChatMessageDTO(
                role = ChatRole.USER,
                content = message
            )
        ),
        prompts = PromptTemplates(
            chatSystem = "SYSTEM",
            planningSystem = "SYSTEM"
        ),
        allGatheredFiles = mutableMapOf(),
        planOnly = false
    )
}
