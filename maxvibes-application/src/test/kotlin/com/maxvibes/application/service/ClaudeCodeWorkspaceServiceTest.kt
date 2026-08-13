package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.application.port.output.ContextError
import com.maxvibes.application.testsupport.FakeProjectContextPort
import com.maxvibes.application.testsupport.FakePromptPort
import com.maxvibes.application.testsupport.InMemoryChatSessionRepository
import com.maxvibes.application.testsupport.RecordingNotificationPort
import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClaudeCodeWorkspaceServiceTest {
    private val sessionId = "session-1"

    private lateinit var contextProvider: FakeProjectContextPort
    private lateinit var repository: InMemoryChatSessionRepository
    private lateinit var notifications: RecordingNotificationPort
    private lateinit var service: ClaudeCodeWorkspaceService

    @BeforeEach
    fun setUp() {
        contextProvider = FakeProjectContextPort()
        repository = InMemoryChatSessionRepository()
        notifications = RecordingNotificationPort()
        service = ClaudeCodeWorkspaceService(
            contextProvider = contextProvider,
            promptPort = FakePromptPort(),
            chatSessionRepository = repository,
            notificationPort = notifications
        )
    }

    @Test
    fun `start failure leaves workspace empty`() = runBlocking {
        contextProvider.projectContextError = ContextError.ProjectNotFound()

        val result = service.start(
            UserInputCommand(
                sessionId = sessionId,
                userInput = "Task"
            )
        )

        val failure = assertIs<CodingAgentWorkspaceResult.Failure>(result)
        assertTrue(failure.message.contains("Project not found"))
        assertNull(service.state)
        assertNull(service.owner)
        assertEquals(1, contextProvider.projectContextCalls)
        assertEquals(1, notifications.progress.size)
        assertEquals("Gathering project context...", notifications.progress.single().message)
        assertEquals(0.1, notifications.progress.single().fraction)
    }

    @Test
    fun `successful start installs complete workspace and appends user message`() = runBlocking {
        val existingHistory = listOf(
            ChatMessageDTO(
                role = ChatRole.ASSISTANT,
                content = "Earlier reply"
            )
        )

        val result = service.start(
            UserInputCommand(
                sessionId = sessionId,
                userInput = "New task",
                history = existingHistory,
                planOnly = true
            )
        )

        val ready = assertIs<CodingAgentWorkspaceResult.Ready>(result)
        assertSame(ready.state, service.state)
        assertEquals(sessionId, service.owner)
        assertTrue(service.isOwnedBy(sessionId))
        assertEquals("New task", ready.state.currentMessage)
        assertEquals(contextProvider.projectContext, ready.state.projectContext)
        assertEquals("CLAUDE CODE SYSTEM PROMPT", ready.state.prompts.chatSystem)
        assertEquals("CLAUDE CODE SYSTEM PROMPT", ready.state.prompts.planningSystem)
        assertEquals(
            listOf(
                ChatMessageDTO(ChatRole.ASSISTANT, "Earlier reply"),
                ChatMessageDTO(ChatRole.USER, "New task")
            ),
            ready.state.dialogHistory
        )
        assertTrue(ready.state.allGatheredFiles.isEmpty())
        assertTrue(ready.state.planOnly)
    }

    @Test
    fun `continue owned workspace updates message and preserves gathered files`() = runBlocking {
        service.start(
            UserInputCommand(
                sessionId = sessionId,
                userInput = "Initial",
                planOnly = true
            )
        )
        service.state!!.allGatheredFiles["src/Foo.kt"] = "class Foo"
        val contextCallsBeforeContinue = contextProvider.projectContextCalls

        val result = service.continueSession(
            UserInputCommand(
                sessionId = sessionId,
                userInput = "Follow-up",
                planOnly = false
            )
        )

        val state = assertIs<CodingAgentWorkspaceResult.Ready>(result).state
        assertEquals("Follow-up", state.currentMessage)
        assertFalse(state.planOnly)
        assertEquals("class Foo", state.allGatheredFiles["src/Foo.kt"])
        assertEquals(
            listOf("Initial", "Follow-up"),
            state.dialogHistory.map { it.content }
        )
        assertEquals(
            listOf(ChatRole.USER, ChatRole.USER),
            state.dialogHistory.map { it.role }
        )
        assertEquals(contextCallsBeforeContinue, contextProvider.projectContextCalls)
    }

    @Test
    fun `continue different session restores persisted history before appending new input`() = runBlocking {
        service.start(
            UserInputCommand(
                sessionId = "session-owner",
                userInput = "Owner task"
            )
        )
        repository.put(
            ChatSession(
                id = sessionId,
                messages = listOf(
                    ChatMessage(
                        role = MessageRole.USER,
                        content = "Persisted task"
                    ),
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = "Persisted reply"
                    )
                )
            )
        )

        val result = service.continueSession(
            UserInputCommand(
                sessionId = sessionId,
                userInput = "Continue now",
                planOnly = true
            )
        )

        val state = assertIs<CodingAgentWorkspaceResult.Ready>(result).state
        assertEquals(sessionId, service.owner)
        assertEquals("Continue now", state.currentMessage)
        assertTrue(state.planOnly)
        assertEquals(
            listOf("Persisted task", "Persisted reply", "Continue now"),
            state.dialogHistory.map { it.content }
        )
        assertEquals(
            listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER),
            state.dialogHistory.map { it.role }
        )
        assertTrue(state.allGatheredFiles.isEmpty())
    }

    @Test
    fun `failed continuation restore preserves previous workspace owner`() = runBlocking {
        service.start(
            UserInputCommand(
                sessionId = "session-owner",
                userInput = "Owner task"
            )
        )

        val result = service.continueSession(
            UserInputCommand(
                sessionId = "missing-session",
                userInput = "Continue"
            )
        )

        val failure = assertIs<CodingAgentWorkspaceResult.Failure>(result)
        assertTrue(failure.message.contains("Cannot restore session state"))
        assertEquals("session-owner", service.owner)
        assertEquals("Owner task", service.state?.currentMessage)
    }

    @Test
    fun `ensure restores only user and assistant messages and uses last user as current`() = runBlocking {
        repository.put(
            ChatSession(
                id = sessionId,
                messages = listOf(
                    ChatMessage(
                        role = MessageRole.SYSTEM,
                        content = "Ignored system"
                    ),
                    ChatMessage(
                        role = MessageRole.USER,
                        content = "First user"
                    ),
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = "Assistant reply"
                    ),
                    ChatMessage(
                        role = MessageRole.USER,
                        content = "Last user"
                    )
                )
            )
        )

        val restored = service.ensure(sessionId)

        assertTrue(restored)
        assertEquals(sessionId, service.owner)
        val state = service.state!!
        assertEquals("Last user", state.currentMessage)
        assertFalse(state.planOnly)
        assertEquals(
            listOf("First user", "Assistant reply", "Last user"),
            state.dialogHistory.map { it.content }
        )
        assertEquals(
            listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER),
            state.dialogHistory.map { it.role }
        )
    }

    @Test
    fun `ensure owned workspace avoids project context reload`() = runBlocking {
        service.start(
            UserInputCommand(
                sessionId = sessionId,
                userInput = "Task"
            )
        )
        val callsAfterStart = contextProvider.projectContextCalls

        val ensured = service.ensure(sessionId)

        assertTrue(ensured)
        assertEquals(callsAfterStart, contextProvider.projectContextCalls)
        assertEquals(sessionId, service.owner)
    }

    @Test
    fun `ensure missing session returns false without installing workspace`() = runBlocking {
        val ensured = service.ensure("missing")

        assertFalse(ensured)
        assertNull(service.state)
        assertNull(service.owner)
        assertEquals(0, contextProvider.projectContextCalls)
    }

    @Test
    fun `ensure session without user message returns false before reading project context`() = runBlocking {
        repository.put(
            ChatSession(
                id = sessionId,
                messages = listOf(
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = "Assistant only"
                    )
                )
            )
        )

        val ensured = service.ensure(sessionId)

        assertFalse(ensured)
        assertNull(service.state)
        assertEquals(0, contextProvider.projectContextCalls)
    }

    @Test
    fun `restore project context failure returns false and leaves workspace empty`() = runBlocking {
        repository.put(
            ChatSession(
                id = sessionId,
                messages = listOf(
                    ChatMessage(
                        role = MessageRole.USER,
                        content = "Persisted task"
                    )
                )
            )
        )
        contextProvider.projectContextError = ContextError.FileReadError(
            path = "project",
            details = "unavailable"
        )

        val ensured = service.ensure(sessionId)

        assertFalse(ensured)
        assertNull(service.state)
        assertNull(service.owner)
        assertEquals(1, contextProvider.projectContextCalls)
    }

    @Test
    fun `append assistant history adds exactly one assistant message`() = runBlocking {
        service.start(
            UserInputCommand(
                sessionId = sessionId,
                userInput = "Task"
            )
        )

        service.appendAssistantHistory("Reply")

        assertEquals(
            listOf(
                ChatMessageDTO(ChatRole.USER, "Task"),
                ChatMessageDTO(ChatRole.ASSISTANT, "Reply")
            ),
            service.state!!.dialogHistory
        )
    }

    @Test
    fun `clear removes both workspace state and owner`() = runBlocking {
        service.start(
            UserInputCommand(
                sessionId = sessionId,
                userInput = "Task"
            )
        )

        service.clear()

        assertNull(service.state)
        assertNull(service.owner)
        assertFalse(service.isOwnedBy(sessionId))
    }
}
