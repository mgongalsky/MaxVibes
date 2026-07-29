package com.maxvibes.plugin.ui

import com.maxvibes.application.port.input.ContextAwareRequest
import com.maxvibes.application.port.input.ContextAwareResult
import com.maxvibes.application.service.ClaudeCodeStepResult
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
