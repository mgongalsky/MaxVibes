package com.maxvibes.application.service

import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.application.testsupport.FakeProjectContextPort
import com.maxvibes.application.testsupport.FakePromptPort
import com.maxvibes.application.testsupport.InMemoryChatSessionRepository
import com.maxvibes.application.testsupport.RecordingClaudeCodePort
import com.maxvibes.application.testsupport.RecordingNotificationPort
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClaudeCodeInteractionServiceFacadeTest {
    private val sessionId = "session-1"

    private lateinit var repository: InMemoryChatSessionRepository
    private lateinit var transport: RecordingClaudeCodePort
    private lateinit var service: ClaudeCodeInteractionService

    @BeforeEach
    fun setUp() {
        repository = InMemoryChatSessionRepository()
        transport = RecordingClaudeCodePort()
        service = ClaudeCodeInteractionService(
            contextProvider = FakeProjectContextPort(),
            claudeCodePort = transport,
            codeRepository = mockk<CodeRepository>(relaxed = true),
            notificationPort = RecordingNotificationPort(),
            promptPort = FakePromptPort(),
            sessionManager = ClipboardSessionManager(repository),
            chatSessionRepository = repository
        )
    }

    @Test
    fun `status exposes persisted clipboard status`() {
        putSession(ClipboardSessionStatus.SESSION_ACTIVE)

        assertEquals(
            ClipboardSessionStatus.SESSION_ACTIVE,
            service.status(sessionId)
        )
    }

    @Test
    fun `user input in awaiting paste is rejected without transport call`() = runBlocking {
        putSession(ClipboardSessionStatus.AWAITING_PASTE)

        val result = service.handleUserInput(
            sessionId = sessionId,
            userInput = "Continue"
        )

        val error = assertIs<ClaudeCodeStepResult.Error>(result)
        assertTrue(error.message.contains("AWAITING_PASTE"))
        assertTrue(transport.ensureCalls.isEmpty())
        assertTrue(transport.sentRequests.isEmpty())
    }

    @Test
    fun `user input in awaiting approve without pending set is rejected`() = runBlocking {
        putSession(ClipboardSessionStatus.AWAITING_APPROVE)

        val result = service.handleUserInput(
            sessionId = sessionId,
            userInput = "Continue"
        )

        val error = assertIs<ClaudeCodeStepResult.Error>(result)
        assertTrue(error.message.contains("awaiting approve"))
        assertEquals(
            ClipboardSessionStatus.AWAITING_APPROVE,
            service.status(sessionId)
        )
        assertTrue(transport.ensureCalls.isEmpty())
        assertTrue(transport.sentRequests.isEmpty())
    }

    @Test
    fun `approve outside awaiting state returns immediate error without transport`() = runBlocking {
        putSession(ClipboardSessionStatus.SESSION_ACTIVE)

        val result = service.approve(sessionId)

        assertEquals(
            "Approve is only valid in AWAITING_APPROVE state",
            assertIs<ClaudeCodeStepResult.Error>(result).message
        )
        assertTrue(transport.ensureCalls.isEmpty())
        assertTrue(transport.sentRequests.isEmpty())
    }

    @Test
    fun `command results outside active state are rejected without transport`() = runBlocking {
        putSession(ClipboardSessionStatus.IDLE)

        val result = service.submitCommandResults(
            sessionId = sessionId,
            resultsForLlm = "tests passed"
        )

        assertEquals(
            "Command results can only be submitted in SESSION_ACTIVE state",
            assertIs<ClaudeCodeStepResult.Error>(result).message
        )
        assertTrue(transport.ensureCalls.isEmpty())
        assertTrue(transport.sentRequests.isEmpty())
    }

    @Test
    fun `reset returns session to idle and shuts down transport`() {
        putSession(ClipboardSessionStatus.AWAITING_APPROVE)

        service.reset(sessionId)

        assertEquals(ClipboardSessionStatus.IDLE, service.status(sessionId))
        assertEquals(1, transport.shutdownCalls)
    }

    private fun putSession(status: ClipboardSessionStatus) {
        repository.put(
            ChatSession(
                id = sessionId,
                clipboardStatus = status
            )
        )
    }
}
