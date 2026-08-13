package com.maxvibes.plugin.ui

import com.maxvibes.application.port.input.ContextAwareRequest
import com.maxvibes.application.port.input.ContextAwareResult
import com.maxvibes.application.service.ClipboardStepResult
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.command.CommandExecution
import com.maxvibes.plugin.testsupport.FakeChatPanelCallbacks
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.jupiter.api.assertThrows
import com.maxvibes.application.service.ClaudeCodeStepResult

class InteractionExecutionCoordinatorTest {
    @Test
    fun `Clipboard crash is converted to Error result`() {
        val runner = ImmediateRunner()
        val fixture = fixture(runner)
        val results = mutableListOf<ClipboardStepResult>()

        fixture.coordinator.runClipboard("Sending", ChatSession(), action = {
            error("boom")
        }, onResult = results::add)

        assertTrue(results.single() is ClipboardStepResult.Error)
        assertEquals("MaxVibes: Sending", runner.title)
        assertTrue(runner.cancellable)
    }

    @Test
    fun `Clipboard cancellation resets session and unlocks input`() {
        val runner = ImmediateRunner(cancel = true)
        val fixture = fixture(runner)
        val session = ChatSession()

        fixture.coordinator.runClipboard("Sending", session, action = {
            mockk<ClipboardStepResult>()
        }, onResult = {})

        assertEquals(listOf(session.id), fixture.clipboardResets)
        assertEquals(listOf("⚠️ Cancelled"), fixture.chatMessages)
        assertEquals(true, fixture.callbacks.inputEnabled)
    }

    @Test
    fun `Claude Code sets running status and converts crash`() {
        val runner = ImmediateRunner()
        val fixture = fixture(runner)
        val results = mutableListOf<ClaudeCodeStepResult>()

        fixture.coordinator.runClaudeCode("Turn", ChatSession(), action = {
            throw IllegalStateException("broken")
        }, onResult = results::add)

        assertEquals("Claude Code: running", fixture.callbacks.statusUpdates.first())
        assertTrue(results.single() is ClaudeCodeStepResult.Error)
    }

    @Test
    fun `API execution forwards cheap selection and result`() {
        val runner = ImmediateRunner()
        val expected = mockk<ContextAwareResult>()
        val request = mockk<ContextAwareRequest>()
        val fixture = fixture(runner, apiResult = expected)
        val results = mutableListOf<ContextAwareResult>()

        fixture.coordinator.runApi(
            progressTitle = "Planning",
            session = ChatSession(),
            useCheap = true,
            request = request,
            onResult = results::add
        )

        assertEquals(listOf(true), fixture.apiSelections)
        assertEquals(listOf(expected), results)
        assertEquals("MaxVibes: Planning...", runner.title)
    }

    @Test
    fun `API cancellation records system message and cancelled status`() {
        val runner = ImmediateRunner(cancel = true)
        val fixture = fixture(runner)
        val session = ChatSession()

        fixture.coordinator.runApi(
            progressTitle = "Planning",
            session = session,
            useCheap = false,
            request = mockk(),
            onResult = {}
        )

        assertEquals(listOf(session.id to "Cancelled"), fixture.systemMessages)
        assertEquals("Cancelled", fixture.callbacks.statusUpdates.last())
        assertEquals(true, fixture.callbacks.inputEnabled)
    }

    @Test
    fun `command execution is non-cancellable and does not publish indicator`() {
        val runner = ImmediateRunner()
        val fixture = fixture(runner)
        val execution = mockk<CommandExecution>()
        val results = mutableListOf<CommandExecution>()

        fixture.coordinator.runCommand(
            action = { execution },
            onResult = results::add
        )

        assertEquals(listOf(execution), results)
        assertFalse(runner.cancellable)
        assertFalse(runner.publishIndicator)
    }

    @Test
    fun `command execution uses the fixed background task title`() {
        val runner = ImmediateRunner()
        val fixture = fixture(runner)

        fixture.coordinator.runCommand(
            action = { mockk() },
            onResult = {}
        )

        assertEquals("MaxVibes: Running command...", runner.title)
    }

    @Test
    fun `API cancellation does not reset Clipboard or Claude sessions`() {
        val runner = ImmediateRunner(cancel = true)
        val fixture = fixture(runner)

        fixture.coordinator.runApi(
            progressTitle = "Processing",
            session = ChatSession(),
            useCheap = false,
            request = mockk(),
            onResult = {}
        )

        assertTrue(fixture.clipboardResets.isEmpty())
        assertTrue(fixture.claudeResets.isEmpty())
        assertEquals(listOf("⚠️ Cancelled"), fixture.chatMessages)
    }

    @Test
    fun `API execution failure propagates without protocol-specific conversion`() {
        val runner = ImmediateRunner()
        val callbacks = FakeChatPanelCallbacks()
        val coordinator = InteractionExecutionCoordinator(
            backgroundTaskRunner = runner,
            inputStatusView = callbacks,
            appendToChat = {},
            resetClipboardSession = {},
            resetClaudeCodeSession = {},
            addSystemMessage = { _, _ -> },
            executeApiRequest = { _, _ -> throw IllegalStateException("api boom") }
        )

        val thrown = assertThrows<IllegalStateException> {
            coordinator.runApi(
                progressTitle = "Processing",
                session = ChatSession(),
                useCheap = false,
                request = mockk(),
                onResult = {}
            )
        }

        assertEquals("api boom", thrown.message)
    }

    @Test
    fun `API regular execution selects regular use case and visible cancellable task`() {
        val runner = ImmediateRunner()
        val expected = mockk<ContextAwareResult>()
        val fixture = fixture(runner, apiResult = expected)
        val results = mutableListOf<ContextAwareResult>()

        fixture.coordinator.runApi(
            progressTitle = "Processing",
            session = ChatSession(),
            useCheap = false,
            request = mockk(),
            onResult = results::add
        )

        assertEquals(listOf(false), fixture.apiSelections)
        assertEquals(listOf(expected), results)
        assertEquals("MaxVibes: Processing...", runner.title)
        assertTrue(runner.cancellable)
        assertTrue(runner.publishIndicator)
    }

    @Test
    fun `Claude Code ProcessCanceledException is rethrown rather than converted to Error`() {
        val runner = ImmediateRunner()
        val fixture = fixture(runner)
        val results = mutableListOf<ClaudeCodeStepResult>()

        assertThrows<ProcessCanceledException> {
            fixture.coordinator.runClaudeCode(
                title = "Turn",
                session = ChatSession(),
                action = { throw ProcessCanceledException() },
                onResult = results::add
            )
        }

        assertTrue(results.isEmpty())
        assertTrue(fixture.claudeResets.isEmpty())
    }

    @Test
    fun `Claude Code cancellation resets only Claude session and unlocks input`() {
        val runner = ImmediateRunner(cancel = true)
        val fixture = fixture(runner)
        val session = ChatSession()

        fixture.coordinator.runClaudeCode(
            title = "Turn",
            session = session,
            action = { mockk() },
            onResult = {}
        )

        assertEquals(listOf(session.id), fixture.claudeResets)
        assertTrue(fixture.clipboardResets.isEmpty())
        assertEquals(listOf("⚠️ Cancelled"), fixture.chatMessages)
        assertEquals(true, fixture.callbacks.inputEnabled)
        assertEquals("Claude Code: running", fixture.callbacks.statusUpdates.single())
    }

    @Test
    fun `Claude Code success forwards exact result with cancellable visible task`() {
        val runner = ImmediateRunner()
        val fixture = fixture(runner)
        val expected = mockk<ClaudeCodeStepResult>()
        val results = mutableListOf<ClaudeCodeStepResult>()

        fixture.coordinator.runClaudeCode(
            title = "Approving",
            session = ChatSession(),
            action = { expected },
            onResult = results::add
        )

        assertEquals(listOf(expected), results)
        assertEquals("MaxVibes: Approving", runner.title)
        assertTrue(runner.cancellable)
        assertTrue(runner.publishIndicator)
        assertTrue(fixture.claudeResets.isEmpty())
    }

    @Test
    fun `Clipboard cancellation does not execute the supplied action`() {
        val runner = ImmediateRunner(cancel = true)
        val fixture = fixture(runner)
        var actionInvoked = false

        fixture.coordinator.runClipboard(
            title = "Sending",
            session = ChatSession(),
            action = {
                actionInvoked = true
                mockk()
            },
            onResult = {}
        )

        assertFalse(actionInvoked)
    }

    @Test
    fun `Clipboard non-Exception crash uses throwable type and fallback message`() {
        val runner = ImmediateRunner()
        val fixture = fixture(runner)
        val results = mutableListOf<ClipboardStepResult>()

        fixture.coordinator.runClipboard(
            title = "Sending",
            session = ChatSession(),
            action = { throw AssertionError() },
            onResult = results::add
        )

        val error = results.single() as ClipboardStepResult.Error
        assertEquals("Internal error: AssertionError: no message", error.message)
    }

    @Test
    fun `Clipboard ProcessCanceledException is rethrown rather than converted to Error`() {
        val runner = ImmediateRunner()
        val fixture = fixture(runner)
        val results = mutableListOf<ClipboardStepResult>()

        assertThrows<ProcessCanceledException> {
            fixture.coordinator.runClipboard(
                title = "Sending",
                session = ChatSession(),
                action = { throw ProcessCanceledException() },
                onResult = results::add
            )
        }

        assertTrue(results.isEmpty())
        assertTrue(fixture.clipboardResets.isEmpty())
    }

    @Test
    fun `Clipboard success forwards the exact result without resetting either protocol`() {
        val runner = ImmediateRunner()
        val fixture = fixture(runner)
        val session = ChatSession()
        val expected = mockk<ClipboardStepResult>()
        val results = mutableListOf<ClipboardStepResult>()

        fixture.coordinator.runClipboard(
            title = "Sending",
            session = session,
            action = { expected },
            onResult = results::add
        )

        assertEquals(listOf(expected), results)
        assertTrue(fixture.clipboardResets.isEmpty())
        assertTrue(fixture.claudeResets.isEmpty())
        assertTrue(fixture.chatMessages.isEmpty())
        assertEquals("MaxVibes: Sending", runner.title)
        assertTrue(runner.publishIndicator)
    }

    private fun fixture(
        runner: ImmediateRunner,
        apiResult: ContextAwareResult = mockk()
    ): Fixture {
        val callbacks = FakeChatPanelCallbacks()
        val chatMessages = mutableListOf<String>()
        val clipboardResets = mutableListOf<String>()
        val claudeResets = mutableListOf<String>()
        val systemMessages = mutableListOf<Pair<String, String>>()
        val apiSelections = mutableListOf<Boolean>()

        val coordinator = InteractionExecutionCoordinator(
            backgroundTaskRunner = runner,
            inputStatusView = callbacks,
            appendToChat = chatMessages::add,
            resetClipboardSession = clipboardResets::add,
            resetClaudeCodeSession = claudeResets::add,
            addSystemMessage = { sessionId, text -> systemMessages.add(sessionId to text) },
            executeApiRequest = { useCheap, _ ->
                apiSelections.add(useCheap)
                apiResult
            }
        )

        return Fixture(
            coordinator = coordinator,
            callbacks = callbacks,
            chatMessages = chatMessages,
            clipboardResets = clipboardResets,
            claudeResets = claudeResets,
            systemMessages = systemMessages,
            apiSelections = apiSelections
        )
    }

    private data class Fixture(
        val coordinator: InteractionExecutionCoordinator,
        val callbacks: FakeChatPanelCallbacks,
        val chatMessages: MutableList<String>,
        val clipboardResets: MutableList<String>,
        val claudeResets: MutableList<String>,
        val systemMessages: MutableList<Pair<String, String>>,
        val apiSelections: MutableList<Boolean>
    )

    private class ImmediateRunner(
        private val cancel: Boolean = false
    ) : BackgroundTaskRunner {
        var title: String = ""
        var cancellable: Boolean = false
        var publishIndicator: Boolean = true

        override fun <T> run(
            title: String,
            cancellable: Boolean,
            publishIndicator: Boolean,
            action: suspend () -> T,
            onSuccess: (T) -> Unit,
            onCancel: () -> Unit
        ) {
            this.title = title
            this.cancellable = cancellable
            this.publishIndicator = publishIndicator
            if (cancel) {
                onCancel()
            } else {
                onSuccess(runBlocking { action() })
            }
        }
    }
}
