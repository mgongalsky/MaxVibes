package com.maxvibes.plugin.ui

import com.maxvibes.application.service.ChatTreeService
import com.maxvibes.application.service.ClaudeCodeStepResult
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.modification.ModificationError
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.plugin.testsupport.FakeChatPanelCallbacks
import com.maxvibes.plugin.testsupport.InMemoryChatSessionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Correction turns for a step whose modifications never reached the code: the
 * plugin could not parse them, or they parsed and failed to apply.
 *
 * The service provider throws on purpose: a correction turn must reach the agent
 * through [ClaudeCodeDispatcher.executeAsync], never from the UI thread.
 */
class ClaudeCodeDispatcherFormatRetryTest {

    private lateinit var callbacks: FakeChatPanelCallbacks
    private lateinit var chatTreeService: ChatTreeService
    private val asyncCalls = mutableListOf<Pair<String, ChatSession>>()
    private val presentedCommands = mutableListOf<List<CommandRequest>>()
    private val correctionTitle = "Claude Code: fixing modifications..."
    private val path = ElementPath("file:src/main/kotlin/A.kt")

    @BeforeEach
    fun setUp() {
        callbacks = FakeChatPanelCallbacks()
        chatTreeService = ChatTreeService(InMemoryChatSessionRepository())
        asyncCalls.clear()
        presentedCommands.clear()
    }

    private fun dispatcher(retryLimit: Int) = ClaudeCodeDispatcher(
        claudeCodeService = {
            throw UnsupportedOperationException("service must only be used inside executeAsync actions")
        },
        resolveSpecificPrompt = { null },
        chatTreeService = chatTreeService,
        callbacks = callbacks,
        presentQuestions = { },
        presentCommands = { commands, _, _ -> presentedCommands.add(commands) },
        executeAsync = { title, session, _ -> asyncCalls.add(title to session) },
        maxFormatRetries = { retryLimit }
    )

    private fun malformedStep(commands: List<CommandRequest> = emptyList()) = ClaudeCodeStepResult.Completed(
        message = "here is the fix",
        modifications = emptyList(),
        success = true,
        commands = commands,
        malformedModifications = listOf("#1: нет обязательных полей: type")
    )

    private fun failedStep() = ClaudeCodeStepResult.Completed(
        message = "here is the fix",
        modifications = listOf(
            ModificationResult.Failure(
                Modification.DeleteElement(path),
                ModificationError.ElementNotFound(path)
            )
        ),
        success = false
    )

    private fun cleanStep() = ClaudeCodeStepResult.Completed(
        message = "applied",
        modifications = emptyList(),
        success = true
    )

    private fun sessionId() = chatTreeService.getActiveSession().id

    private fun asyncTitles() = asyncCalls.map { it.first }

    @Test
    fun `unparsable modifications turn the next step into a correction turn`() {
        val dispatcher = dispatcher(retryLimit = 2)

        dispatcher.handleResult(malformedStep(), chatTreeService.getActiveSession())
        dispatcher.continueWithoutHuman(sessionId())

        assertEquals(listOf(correctionTitle), asyncTitles())
        assertEquals(false, callbacks.inputEnabled)
    }

    @Test
    fun `modifications that failed to apply also turn the next step into a correction turn`() {
        val dispatcher = dispatcher(retryLimit = 2)

        dispatcher.handleResult(failedStep(), chatTreeService.getActiveSession())
        dispatcher.continueWithoutHuman(sessionId())

        assertEquals(listOf(correctionTitle), asyncTitles())
        assertEquals(false, callbacks.inputEnabled)
    }

    @Test
    fun `a format error and a failed apply share the same retry streak`() {
        val dispatcher = dispatcher(retryLimit = 1)

        dispatcher.handleResult(malformedStep(), chatTreeService.getActiveSession())
        dispatcher.continueWithoutHuman(sessionId())
        dispatcher.handleResult(failedStep(), chatTreeService.getActiveSession())
        dispatcher.continueWithoutHuman(sessionId())

        assertEquals(listOf(correctionTitle), asyncTitles())
        assertEquals(true, callbacks.inputEnabled)
        assertTrue(callbacks.statusUpdates.last().contains("так и не"))
    }

    @Test
    fun `the correction stops once the configured number of retries is spent`() {
        val dispatcher = dispatcher(retryLimit = 1)

        repeat(2) {
            dispatcher.handleResult(malformedStep(), chatTreeService.getActiveSession())
            dispatcher.continueWithoutHuman(sessionId())
        }

        assertEquals(listOf(correctionTitle), asyncTitles())
        assertEquals(true, callbacks.inputEnabled)
        assertTrue(callbacks.statusUpdates.last().contains("так и не"))
    }

    @Test
    fun `a step without format errors starts the retry streak from scratch`() {
        val dispatcher = dispatcher(retryLimit = 1)

        dispatcher.handleResult(malformedStep(), chatTreeService.getActiveSession())
        dispatcher.continueWithoutHuman(sessionId())
        dispatcher.handleResult(cleanStep(), chatTreeService.getActiveSession())
        dispatcher.handleResult(malformedStep(), chatTreeService.getActiveSession())
        dispatcher.continueWithoutHuman(sessionId())

        assertEquals(2, asyncTitles().size)
        assertTrue(asyncTitles().all { it == correctionTitle })
    }

    @Test
    fun `a limit of zero hands the broken step straight back to the user`() {
        val dispatcher = dispatcher(retryLimit = 0)

        dispatcher.handleResult(malformedStep(), chatTreeService.getActiveSession())
        dispatcher.continueWithoutHuman(sessionId())

        assertTrue(asyncCalls.isEmpty())
        assertEquals(true, callbacks.inputEnabled)
        assertTrue(callbacks.statusUpdates.last().contains("так и не"))
    }

    @Test
    fun `commands of a step that lost its modifications are never offered`() {
        val dispatcher = dispatcher(retryLimit = 2)

        dispatcher.handleResult(
            malformedStep(commands = listOf(CommandRequest("gradlew test"))),
            chatTreeService.getActiveSession()
        )

        assertTrue(presentedCommands.isEmpty())
        assertEquals(true, callbacks.inputEnabled)
    }

    @Test
    fun `a step held for approval does not reset the retry streak`() {
        val dispatcher = dispatcher(retryLimit = 1)
        dispatcher.handleResult(malformedStep(), chatTreeService.getActiveSession())
        dispatcher.continueWithoutHuman(sessionId())
        dispatcher.handleResult(
            ClaudeCodeStepResult.AwaitingModApprove(
                assistantMessage = "proposed",
                proposedModifications = emptyList()
            ),
            chatTreeService.getActiveSession()
        )
        dispatcher.handleResult(failedStep(), chatTreeService.getActiveSession())
        dispatcher.continueWithoutHuman(sessionId())
        assertEquals(listOf(correctionTitle), asyncTitles())
        assertEquals(true, callbacks.inputEnabled)
        assertTrue(callbacks.statusUpdates.last().contains("так и не"))
    }
}
