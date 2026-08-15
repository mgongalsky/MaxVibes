package com.maxvibes.application.service.turn

import com.maxvibes.application.service.ClaudeCodeStepResult
import com.maxvibes.domain.model.approval.AgentActionKind
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.modification.ModificationError
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.domain.model.turn.TurnSignal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TurnSignalMapperTest {

    private val path = ElementPath("file:src/main/kotlin/A.kt")
    private val modification = Modification.DeleteElement(path)

    private fun applied() = ModificationResult.Success(modification, path, null)

    private fun rejected() =
        ModificationResult.Failure(modification, ModificationError.ElementNotFound(path))

    private fun completed(
        modifications: List<ModificationResult> = emptyList(),
        commands: List<CommandRequest> = emptyList()
    ) = ClaudeCodeStepResult.Completed(
        message = "done",
        modifications = modifications,
        success = true,
        commands = commands
    )

    @Test
    fun `a request for code is a pending view request`() {
        val result = ClaudeCodeStepResult.WaitingForApprove("look here", emptyList())

        assertEquals(TurnSignal.Pending(AgentActionKind.VIEW_REQUEST), TurnSignalMapper.from(result))
    }

    @Test
    fun `proposed modifications are a pending modification`() {
        val result = ClaudeCodeStepResult.AwaitingModApprove("here is the fix", emptyList())

        assertEquals(TurnSignal.Pending(AgentActionKind.MODIFICATION), TurnSignalMapper.from(result))
    }

    @Test
    fun `questions always wait for the human, no policy can answer them`() {
        val result = ClaudeCodeStepResult.AwaitingQuestions("which one?", emptyList())

        assertEquals(TurnSignal.Questions, TurnSignalMapper.from(result))
    }

    @Test
    fun `commands left after a clean apply keep the turn alive`() {
        val result = completed(listOf(applied()), listOf(CommandRequest("gradlew test")))

        assertEquals(TurnSignal.Pending(AgentActionKind.COMMAND), TurnSignalMapper.from(result))
    }

    @Test
    fun `commands without any modification at all still keep the turn alive`() {
        val result = completed(commands = listOf(CommandRequest("git status")))

        assertEquals(TurnSignal.Pending(AgentActionKind.COMMAND), TurnSignalMapper.from(result))
    }

    @Test
    fun `a failed modification ends the turn even though commands are pending`() {
        val result = completed(listOf(applied(), rejected()), listOf(CommandRequest("gradlew test")))

        assertEquals(TurnSignal.Completed, TurnSignalMapper.from(result))
    }

    @Test
    fun `an answer with nothing left to run finishes the turn`() {
        assertEquals(TurnSignal.Completed, TurnSignalMapper.from(completed(listOf(applied()))))
    }

    @Test
    fun `an agent error carries its message into the failure`() {
        assertEquals(TurnSignal.Failed("bad json"), TurnSignalMapper.from(ClaudeCodeStepResult.Error("bad json")))
    }

    @Test
    fun `a transport error carries its detail into the failure`() {
        val result = ClaudeCodeStepResult.TransportError("cli exited with 1")

        assertEquals(TurnSignal.Failed("cli exited with 1"), TurnSignalMapper.from(result))
    }
}
