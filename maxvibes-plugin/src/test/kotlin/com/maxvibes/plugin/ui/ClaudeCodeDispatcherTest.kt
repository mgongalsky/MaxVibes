package com.maxvibes.plugin.ui

import com.maxvibes.application.service.ChatTreeService
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.interaction.InteractionQuestion
import com.maxvibes.plugin.testsupport.FakeChatPanelCallbacks
import com.maxvibes.plugin.testsupport.InMemoryChatSessionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.maxvibes.application.service.ClaudeCodeStepResult

/**
 * Claude Code dispatch/approve/result flow against a real [ChatTreeService]
 * (in-memory repository) and the recording [FakeChatPanelCallbacks].
 *
 * The service provider throws: the dispatcher must only dereference it inside
 * [ClaudeCodeDispatcher.executeAsync] actions, which these tests record without
 * running.
 */
class ClaudeCodeDispatcherTest {

    private lateinit var callbacks: FakeChatPanelCallbacks
    private lateinit var chatTreeService: ChatTreeService
    private val presentedQuestions = mutableListOf<List<InteractionQuestion>>()
    private val presentedCommands = mutableListOf<Triple<List<CommandRequest>, String, InteractionMode>>()
    private val asyncCalls = mutableListOf<Pair<String, ChatSession>>()
    private lateinit var dispatcher: ClaudeCodeDispatcher

    @BeforeEach
    fun setUp() {
        callbacks = FakeChatPanelCallbacks()
        chatTreeService = ChatTreeService(InMemoryChatSessionRepository())
        presentedQuestions.clear()
        presentedCommands.clear()
        asyncCalls.clear()
        dispatcher = ClaudeCodeDispatcher(
            claudeCodeService = {
                throw UnsupportedOperationException("service must only be used inside executeAsync actions")
            },
            resolveSpecificPrompt = { null },
            chatTreeService = chatTreeService,
            callbacks = callbacks,
            presentQuestions = { presentedQuestions.add(it) },
            presentCommands = { commands, sessionId, mode ->
                presentedCommands.add(Triple(commands, sessionId, mode))
            },
            executeAsync = { title, session, _ -> asyncCalls.add(title to session) }
        )
    }

    private fun activeSession() = chatTreeService.getActiveSession()

    private fun lastMessage() = activeSession().messages.last()

    @Test
    fun `dispatchMessage persists user turn, locks input and defers execution`() {
        dispatcher.dispatchMessage("hello", trace = null, errs = null, isPlanOnly = false)
        assertEquals(listOf("hello"), callbacks.userBubbles)
        assertEquals(false, callbacks.inputEnabled)
        assertEquals("Claude Code: sending...", callbacks.statusUpdates.last())
        assertEquals(1, asyncCalls.size)
        assertEquals("Claude Code: sending...", asyncCalls[0].first)
        assertEquals(MessageRole.USER, lastMessage().role)
        assertEquals("hello", lastMessage().content)
    }

    @Test
    fun `dispatchMessage annotates attachments in the persisted message but not in the bubble`() {
        dispatcher.dispatchMessage("hi", trace = "a\nb\nc", errs = "boom", isPlanOnly = true)
        assertEquals(listOf("hi"), callbacks.userBubbles)
        assertEquals("hi\n[trace: 3 lines]\n[attached ide errors]\n[plan-only]", lastMessage().content)
    }

    @Test
    fun `approve locks input and defers the approve action`() {
        dispatcher.approve(trace = null, errs = null)
        assertEquals(false, callbacks.inputEnabled)
        assertEquals("Claude Code: approving...", callbacks.statusUpdates.last())
        assertEquals(1, asyncCalls.size)
        assertTrue(callbacks.userBubbles.isEmpty())
    }

    @Test
    fun `WaitingForApprove renders assistant turn and reenables input`() {
        val session = activeSession()
        dispatcher.handleResult(
            ClaudeCodeStepResult.WaitingForApprove(assistantMessage = "need files", requestedViews = emptyList()),
            session
        )
        assertEquals(true, callbacks.inputEnabled)
        assertTrue(callbacks.statusUpdates.last().contains("Awaiting approval"))
        assertEquals(1, callbacks.assistantBubbles.size)
        assertTrue(callbacks.assistantBubbles[0].contains("need files"))
        assertEquals(MessageRole.ASSISTANT, lastMessage().role)
        assertEquals("need files", lastMessage().content)
    }

    @Test
    fun `AwaitingModApprove reports proposal and keeps session interactive`() {
        val session = activeSession()
        val mods = listOf(InteractionModification(type = "REPLACE_ELEMENT", path = "file:src/A.kt/class[A]"))
        dispatcher.handleResult(
            ClaudeCodeStepResult.AwaitingModApprove(assistantMessage = "plan", proposedModifications = mods),
            session
        )
        assertEquals(true, callbacks.inputEnabled)
        assertTrue(callbacks.statusUpdates.last().contains("1 modification(s) awaiting approval"))
        assertEquals("plan", lastMessage().content)
    }

    @Test
    fun `AwaitingQuestions forwards questions to the coordinator hook`() {
        val session = activeSession()
        val questions = listOf(InteractionQuestion(id = "q1", question = "Which?", options = listOf("A", "B")))
        dispatcher.handleResult(
            ClaudeCodeStepResult.AwaitingQuestions(assistantMessage = "", questions = questions),
            session
        )
        assertEquals(listOf(questions), presentedQuestions)
        assertEquals(true, callbacks.inputEnabled)
        assertTrue(callbacks.statusUpdates.last().contains("1 question(s)"))
        assertTrue(callbacks.assistantBubbles.isEmpty())
        assertTrue(lastMessage().content.contains("Which?"))
        assertTrue(lastMessage().content.contains("1. A"))
    }

    @Test
    fun `Completed with commands hands off to the command coordinator without reenabling input`() {
        val session = activeSession()
        val commands = listOf(CommandRequest("gradlew test"))
        dispatcher.handleResult(
            ClaudeCodeStepResult.Completed(
                message = "done",
                modifications = emptyList(),
                success = true,
                commands = commands
            ),
            session
        )
        assertEquals(1, presentedCommands.size)
        assertEquals(commands, presentedCommands[0].first)
        assertEquals(session.id, presentedCommands[0].second)
        assertEquals(InteractionMode.CLAUDE_CODE, presentedCommands[0].third)
        assertNull(callbacks.inputEnabled)
    }

    @Test
    fun `Completed without commands sets Ready and falls back to Done for blank message`() {
        val session = activeSession()
        dispatcher.handleResult(
            ClaudeCodeStepResult.Completed(message = "  ", modifications = emptyList(), success = true),
            session
        )
        assertEquals(true, callbacks.inputEnabled)
        assertEquals("Ready", callbacks.statusUpdates.last())
        assertEquals("Done.", lastMessage().content)
        assertTrue(presentedCommands.isEmpty())
    }

    @Test
    fun `Completed with commit message forwards it to the IDE`() {
        val session = activeSession()
        dispatcher.handleResult(
            ClaudeCodeStepResult.Completed(
                message = "ok",
                modifications = emptyList(),
                success = true,
                commitMessage = "feat: x"
            ),
            session
        )
        assertEquals(listOf("feat: x"), callbacks.commitMessages)
    }

    @Test
    fun `Error persists a system message and reenables input`() {
        val session = activeSession()
        dispatcher.handleResult(ClaudeCodeStepResult.Error("boom"), session)
        assertEquals(true, callbacks.inputEnabled)
        assertEquals("Claude Code error", callbacks.statusUpdates.last())
        assertEquals(MessageRole.SYSTEM, lastMessage().role)
        assertEquals("Claude Code error: boom", lastMessage().content)
    }

    @Test
    fun `TransportError persists a system message and reenables input`() {
        val session = activeSession()
        dispatcher.handleResult(ClaudeCodeStepResult.TransportError("no binary"), session)
        assertEquals(true, callbacks.inputEnabled)
        assertTrue(callbacks.statusUpdates.last().contains("transport error"))
        assertEquals("Claude Code transport error: no binary", lastMessage().content)
    }
}
