package com.maxvibes.plugin.ui

import com.maxvibes.application.port.input.ExecuteCommandUseCase
import com.maxvibes.domain.model.command.CommandExecution
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.command.CommandStatus
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.plugin.testsupport.FakeChatPanelCallbacks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Full lifecycle of the command-turn state machine: run all (sequential chain,
 * stop at first failure), decline all, partial decline, single command.
 *
 * Command execution is captured into [pendingExecutions] instead of running —
 * each test decides when and how a command completes.
 */
class CommandTurnCoordinatorTest {

    private class FakeExecuteCommandUseCase : ExecuteCommandUseCase {
        override suspend fun execute(request: CommandRequest): CommandExecution =
            throw UnsupportedOperationException("coordinator must use the injected executeAsync")

        override fun warningsFor(request: CommandRequest): List<String> = emptyList()

        override fun formatForLlm(execution: CommandExecution, tailLines: Int): String =
            "${execution.request.command}=${execution.status}" +
                    (execution.declineComment?.let { "[$it]" } ?: "")
    }

    private lateinit var callbacks: FakeChatPanelCallbacks
    private val systemMessages = mutableListOf<Pair<String, String>>()
    private val pendingExecutions = mutableListOf<Pair<CommandRequest, (CommandExecution) -> Unit>>()
    private var completedBatch: Triple<String, InteractionMode, String>? = null
    private lateinit var coordinator: CommandTurnCoordinator

    @BeforeEach
    fun setUp() {
        callbacks = FakeChatPanelCallbacks()
        systemMessages.clear()
        pendingExecutions.clear()
        completedBatch = null
        coordinator = CommandTurnCoordinator(
            executeCommandUseCase = FakeExecuteCommandUseCase(),
            callbacks = callbacks,
            addSystemMessage = { sessionId, text -> systemMessages.add(sessionId to text) },
            activeSessionId = { "active-session" },
            executeAsync = { request, onDone -> pendingExecutions.add(request to onDone) },
            onBatchComplete = { sessionId, mode, results -> completedBatch = Triple(sessionId, mode, results) }
        )
    }

    private fun present(vararg commands: String, mode: InteractionMode = InteractionMode.CLAUDE_CODE) {
        coordinator.presentCommands(commands.map { CommandRequest(it) }, "s1", mode)
    }

    private fun completeNext(status: CommandStatus, exitCode: Int? = 0) {
        val (request, onDone) = pendingExecutions.removeAt(0)
        onDone(CommandExecution(request = request, status = status, exitCode = exitCode, durationMs = 1500))
    }

    @Test
    fun `presenting a batch locks input and renders bubble per command plus batch bar`() {
        present("a", "b")
        assertEquals(false, callbacks.inputEnabled)
        assertEquals(2, callbacks.commandBubbles.size)
        assertEquals(1, callbacks.batchBars.size)
        assertTrue(callbacks.statusUpdates.last().contains("2 command(s) awaiting approval"))
    }

    @Test
    fun `single command has no batch bar and completes after run`() {
        present("gradlew test")
        assertTrue(callbacks.batchBars.isEmpty())
        callbacks.commandBubbles[0].onRun()
        assertEquals(listOf("running"), callbacks.commandBubbles[0].stateChanges)
        completeNext(CommandStatus.SUCCESS)
        val batch = completedBatch!!
        assertEquals("s1", batch.first)
        assertEquals(InteractionMode.CLAUDE_CODE, batch.second)
        assertTrue(batch.third.contains("gradlew test=SUCCESS"))
        assertTrue(callbacks.commandBubbles[0].resultOk == true)
        assertTrue(systemMessages.any { it.first == "s1" && it.second.contains("gradlew test") })
    }

    @Test
    fun `run all executes commands sequentially`() {
        present("one", "two")
        callbacks.batchBars[0].onRunAll()
        assertEquals(1, pendingExecutions.size)
        assertTrue(callbacks.commandBubbles[1].stateChanges.contains("queued"))
        completeNext(CommandStatus.SUCCESS)
        assertEquals(1, pendingExecutions.size)
        completeNext(CommandStatus.SUCCESS)
        val batch = completedBatch!!
        assertTrue(batch.third.contains("one=SUCCESS"))
        assertTrue(batch.third.contains("two=SUCCESS"))
        assertTrue(callbacks.batchBars[0].dismissed)
    }

    @Test
    fun `run all stops at first failure and declines the rest`() {
        present("one", "two", "three")
        callbacks.batchBars[0].onRunAll()
        completeNext(CommandStatus.FAILED, exitCode = 1)
        assertTrue(pendingExecutions.isEmpty())
        assertEquals("declined", callbacks.commandBubbles[1].stateChanges.last())
        assertEquals("skipped: previous command failed", callbacks.commandBubbles[1].declineComment)
        assertEquals("skipped: previous command failed", callbacks.commandBubbles[2].declineComment)
        val batch = completedBatch!!
        assertTrue(batch.third.contains("one=FAILED"))
        assertTrue(batch.third.contains("two=DECLINED[skipped: previous command failed]"))
        assertTrue(batch.third.contains("three=DECLINED[skipped: previous command failed]"))
    }

    @Test
    fun `decline all declines every pending command and completes the batch`() {
        present("one", "two")
        callbacks.batchBars[0].onDeclineAll()
        assertTrue(pendingExecutions.isEmpty())
        val batch = completedBatch!!
        assertTrue(batch.third.contains("one=DECLINED"))
        assertTrue(batch.third.contains("two=DECLINED"))
        assertEquals(2, systemMessages.count { it.second.contains("Declined:") })
    }

    @Test
    fun `partial decline then manual run completes the batch with both outcomes`() {
        present("one", "two")
        callbacks.commandBubbles[0].onDecline("not needed")
        assertNull(completedBatch)
        assertTrue(callbacks.statusUpdates.any { it.contains("1 command(s) awaiting approval") })
        callbacks.commandBubbles[1].onRun()
        completeNext(CommandStatus.SUCCESS)
        val batch = completedBatch!!
        assertTrue(batch.third.contains("one=DECLINED[not needed]"))
        assertTrue(batch.third.contains("two=SUCCESS"))
    }

    @Test
    fun `empty command list is a no-op`() {
        coordinator.presentCommands(emptyList(), "s1", InteractionMode.API)
        assertNull(callbacks.inputEnabled)
        assertNull(completedBatch)
        assertTrue(callbacks.commandBubbles.isEmpty())
    }
}
