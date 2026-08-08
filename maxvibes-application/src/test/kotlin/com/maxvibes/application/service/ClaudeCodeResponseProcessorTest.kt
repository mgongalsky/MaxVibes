package com.maxvibes.application.service

import com.maxvibes.application.service.CodingAgentResponseProcessor.Context
import com.maxvibes.application.service.CodingAgentResponseProcessor.Intent
import com.maxvibes.domain.model.code.CodeGranularity
import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.domain.model.interaction.InteractionCommand
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.interaction.InteractionQuestion
import com.maxvibes.domain.model.interaction.InteractionResponse
import com.maxvibes.domain.model.planning.PlanStep
import com.maxvibes.domain.model.planning.TaskPlan
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeCodeResponseProcessorTest {

    private val mod = InteractionModification(
        type = "CREATE_FILE",
        path = "file:src/New.kt",
        content = "class New"
    )
    private val view = CodeViewRequest("src/Foo.kt", CodeGranularity.FULL)
    private val command = InteractionCommand(
        command = "gradlew test",
        reason = "verify"
    )

    @Test
    fun `text-only response completes with history and inactive transition`() {
        val outcome = CodingAgentResponseProcessor.process(
            InteractionResponse(message = "hi"),
            Context()
        )

        assertIs<ClaudeCodeStepResult.Completed>(outcome.result)
        assertEquals(
            listOf(
                Intent.AppendAssistantHistory("hi"),
                Intent.Transition(hasRequestedViews = false)
            ),
            outcome.intents
        )
    }

    @Test
    fun `views response waits for approve and persists views`() {
        val outcome = CodingAgentResponseProcessor.process(
            InteractionResponse(
                message = "need",
                codeViewRequests = listOf(view),
                commands = listOf(command)
            ),
            Context()
        )

        val waiting = assertIs<ClaudeCodeStepResult.WaitingForApprove>(outcome.result)
        assertEquals(1, waiting.skippedCommands)
        assertEquals(listOf("src/Foo.kt"), waiting.requestedViews.map { it.path })
        assertEquals(
            listOf(
                Intent.AppendAssistantHistory("need"),
                Intent.PersistRequestedViews(listOf(view)),
                Intent.Transition(hasRequestedViews = true)
            ),
            outcome.intents
        )
    }

    @Test
    fun `modifications are held with commands and commit message`() {
        val outcome = CodingAgentResponseProcessor.process(
            InteractionResponse(
                message = "prop",
                modifications = listOf(mod),
                commands = listOf(command),
                commitMessage = "feat: x"
            ),
            Context()
        )

        val awaiting = assertIs<ClaudeCodeStepResult.AwaitingModApprove>(outcome.result)
        assertEquals(1, awaiting.heldCommands)
        val hold = assertIs<Intent.HoldPending>(outcome.intents.last())
        assertEquals(listOf(mod), hold.modifications)
        assertEquals("feat: x", hold.commitMessage)
        assertEquals("gradlew test", hold.commands.single().command)
        assertEquals(
            Intent.Transition(hasRequestedViews = true),
            outcome.intents[outcome.intents.size - 2]
        )
    }

    @Test
    fun `views mixed with modifications are skipped and not persisted`() {
        val outcome = CodingAgentResponseProcessor.process(
            InteractionResponse(
                message = "m",
                modifications = listOf(mod),
                codeViewRequests = listOf(view)
            ),
            Context()
        )

        val awaiting = assertIs<ClaudeCodeStepResult.AwaitingModApprove>(outcome.result)
        assertEquals(1, awaiting.skippedViews)
        assertTrue(outcome.intents.none { it is Intent.PersistRequestedViews })
    }

    @Test
    fun `questions drop mixed commands`() {
        val outcome = CodingAgentResponseProcessor.process(
            InteractionResponse(
                message = "q",
                questions = listOf(
                    InteractionQuestion(
                        id = "q1",
                        question = "A or B?"
                    )
                ),
                commands = listOf(command)
            ),
            Context()
        )

        val awaiting = assertIs<ClaudeCodeStepResult.AwaitingQuestions>(outcome.result)
        assertEquals(listOf("q1"), awaiting.questions.map { it.id })
        assertEquals(
            Intent.Transition(hasRequestedViews = false),
            outcome.intents.last()
        )
    }

    @Test
    fun `plan-only mode neither holds nor applies modifications`() {
        val outcome = CodingAgentResponseProcessor.process(
            InteractionResponse(
                message = "talk",
                modifications = listOf(mod)
            ),
            Context(planOnly = true)
        )

        val completed = assertIs<ClaudeCodeStepResult.Completed>(outcome.result)
        assertTrue(completed.modifications.isEmpty())
        assertTrue(outcome.intents.none { it is Intent.HoldPending })
        assertEquals(
            Intent.Transition(hasRequestedViews = false),
            outcome.intents.last()
        )
    }

    @Test
    fun `plan snapshot maps to SavePlan and empty steps clear it`() {
        val withSteps = CodingAgentResponseProcessor.process(
            InteractionResponse(
                message = "m",
                plan = TaskPlan(
                    "X",
                    steps = listOf(PlanStep("1", "s"))
                )
            ),
            Context()
        )
        val savePlan = assertIs<Intent.SavePlan>(withSteps.intents.first())
        assertEquals("X", savePlan.plan?.title)

        val cleared = CodingAgentResponseProcessor.process(
            InteractionResponse(
                message = "m",
                plan = TaskPlan("empty")
            ),
            Context()
        )
        assertNull(
            assertIs<Intent.SavePlan>(cleared.intents.first()).plan
        )

        val absent = CodingAgentResponseProcessor.process(
            InteractionResponse(message = "m"),
            Context()
        )
        assertTrue(absent.intents.none { it is Intent.SavePlan })
    }

    @Test
    fun `blank message falls back to Done and skips history`() {
        val outcome = CodingAgentResponseProcessor.process(
            InteractionResponse(message = ""),
            Context()
        )

        assertEquals(
            "Done.",
            assertIs<ClaudeCodeStepResult.Completed>(outcome.result).message
        )
        assertTrue(
            outcome.intents.none {
                it is Intent.AppendAssistantHistory
            }
        )
    }

    @Test
    fun `thinking and reasoning are combined with blank line`() {
        val outcome = CodingAgentResponseProcessor.process(
            InteractionResponse(
                message = "m",
                reasoning = "R"
            ),
            Context(thinkingText = "T")
        )

        val expected = listOf("T", "R")
            .joinToString(10.toChar().toString().repeat(2))

        assertEquals(
            expected,
            assertIs<ClaudeCodeStepResult.Completed>(outcome.result).llmReasoning
        )
    }

    @Test
    fun `blank commands are dropped by conversion`() {
        val outcome = CodingAgentResponseProcessor.process(
            InteractionResponse(
                message = "m",
                commands = listOf(
                    InteractionCommand(
                        command = "",
                        reason = "r"
                    )
                )
            ),
            Context()
        )

        assertTrue(
            assertIs<ClaudeCodeStepResult.Completed>(outcome.result)
                .commands
                .isEmpty()
        )
    }
}
