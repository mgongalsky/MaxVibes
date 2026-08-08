package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.application.port.output.PromptTemplates
import com.maxvibes.application.testsupport.FakeProjectContextPort
import com.maxvibes.application.testsupport.InMemoryChatSessionRepository
import com.maxvibes.application.testsupport.RecordingClaudeCodeSessionLogPort
import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.code.CodeGranularity
import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionCommand
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.interaction.InteractionQuestion
import com.maxvibes.domain.model.interaction.InteractionResponse
import com.maxvibes.domain.model.planning.PlanStep
import com.maxvibes.domain.model.planning.TaskPlan
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeCodeResponseHandlerTest {
    private val sessionId = "session-1"

    private lateinit var repository: InMemoryChatSessionRepository
    private lateinit var sessionManager: ClipboardSessionManager
    private lateinit var pendingStore: PendingModificationsStore
    private lateinit var sessionLog: RecordingClaudeCodeSessionLogPort
    private lateinit var handler: CodingAgentResponseHandler

    @BeforeEach
    fun setUp() {
        repository = InMemoryChatSessionRepository(
            listOf(
                ChatSession(
                    id = sessionId,
                    clipboardStatus = ClipboardSessionStatus.SESSION_ACTIVE
                )
            )
        )
        sessionManager = ClipboardSessionManager(repository)
        pendingStore = PendingModificationsStore()
        sessionLog = RecordingClaudeCodeSessionLogPort()
        handler = CodingAgentResponseHandler(
            chatSessionRepository = repository,
            sessionManager = sessionManager,
            pendingStore = pendingStore,
            sessionLog = sessionLog
        )
    }

    @Test
    fun `text response appends assistant history and remains active`() {
        val state = state()

        val result = handler.handle(
            sessionId = sessionId,
            turn = turn(InteractionResponse(message = "Completed")),
            state = state
        )

        assertIs<ClaudeCodeStepResult.Completed>(result)
        assertEquals(
            listOf(ChatMessageDTO(ChatRole.ASSISTANT, "Completed")),
            state.dialogHistory
        )
        assertEquals(
            ClipboardSessionStatus.SESSION_ACTIVE,
            sessionManager.statusFor(sessionId)
        )
    }

    @Test
    fun `blank response does not append assistant history`() {
        val state = state()

        val result = handler.handle(
            sessionId = sessionId,
            turn = turn(InteractionResponse(message = "")),
            state = state
        )

        assertEquals("Done.", assertIs<ClaudeCodeStepResult.Completed>(result).message)
        assertTrue(state.dialogHistory.isEmpty())
    }

    @Test
    fun `requested views are persisted into last assistant message and await approval`() {
        repository.put(
            ChatSession(
                id = sessionId,
                clipboardStatus = ClipboardSessionStatus.SESSION_ACTIVE,
                messages = listOf(
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = "Older response"
                    ),
                    ChatMessage(
                        role = MessageRole.USER,
                        content = "Inspect Foo"
                    ),
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = "Need Foo"
                    )
                )
            )
        )
        val request = CodeViewRequest(
            filePath = "src/Foo.kt",
            granularity = CodeGranularity.ELEMENT,
            elementPath = "class[Foo]/function[bar]"
        )

        val result = handler.handle(
            sessionId = sessionId,
            turn = turn(
                InteractionResponse(
                    message = "Need Foo",
                    codeViewRequests = listOf(request)
                )
            ),
            state = state()
        )

        assertIs<ClaudeCodeStepResult.WaitingForApprove>(result)
        assertEquals(
            ClipboardSessionStatus.AWAITING_APPROVE,
            sessionManager.statusFor(sessionId)
        )
        val persisted = repository.getSessionById(sessionId)!!
        assertTrue(persisted.messages.first().requestedViews.isEmpty())
        val savedView = persisted.messages.last().requestedViews.single()
        assertEquals("src/Foo.kt", savedView.path)
        assertEquals(CodeGranularity.ELEMENT, savedView.granularity)
        assertEquals("class[Foo]/function[bar]", savedView.elementPath)
    }

    @Test
    fun `requested views without assistant domain message do not create one`() {
        repository.put(
            ChatSession(
                id = sessionId,
                clipboardStatus = ClipboardSessionStatus.SESSION_ACTIVE,
                messages = listOf(
                    ChatMessage(
                        role = MessageRole.USER,
                        content = "Inspect"
                    )
                )
            )
        )

        handler.handle(
            sessionId = sessionId,
            turn = turn(
                InteractionResponse(
                    message = "Need file",
                    codeViewRequests = listOf(
                        CodeViewRequest(
                            "src/Foo.kt",
                            CodeGranularity.FULL
                        )
                    )
                )
            ),
            state = state()
        )

        val persisted = repository.getSessionById(sessionId)!!
        assertEquals(1, persisted.messages.size)
        assertTrue(persisted.messages.single().requestedViews.isEmpty())
    }

    @Test
    fun `plan snapshot replaces persisted plan`() {
        val plan = TaskPlan(
            title = "Refactor",
            steps = listOf(
                PlanStep(id = "1", title = "Extract service")
            )
        )

        handler.handle(
            sessionId = sessionId,
            turn = turn(
                InteractionResponse(
                    message = "Working",
                    plan = plan
                )
            ),
            state = state()
        )

        val persistedPlan = repository.getSessionById(sessionId)!!.plan
        assertNotNull(persistedPlan)
        assertEquals("Refactor", persistedPlan.title)
        assertEquals("Extract service", persistedPlan.steps.single().title)
        assertTrue(
            sessionLog.events.any {
                it.text == "plan updated" && it.data?.get("steps") == 1
            }
        )
    }

    @Test
    fun `empty plan clears persisted plan`() {
        repository.put(
            ChatSession(
                id = sessionId,
                clipboardStatus = ClipboardSessionStatus.SESSION_ACTIVE,
                plan = TaskPlan(
                    title = "Old",
                    steps = listOf(PlanStep("1", "Old step"))
                )
            )
        )

        handler.handle(
            sessionId = sessionId,
            turn = turn(
                InteractionResponse(
                    message = "Plan cleared",
                    plan = TaskPlan(title = "Empty")
                )
            ),
            state = state()
        )

        assertNull(repository.getSessionById(sessionId)!!.plan)
    }

    @Test
    fun `modifications commands and commit message are held together`() {
        val modification = InteractionModification(
            type = "CREATE_FILE",
            path = "file:src/New.kt",
            content = "class New"
        )
        val command = InteractionCommand(
            command = "gradlew.bat test",
            reason = "verify"
        )

        val result = handler.handle(
            sessionId = sessionId,
            turn = turn(
                InteractionResponse(
                    message = "Proposal",
                    modifications = listOf(modification),
                    commands = listOf(command),
                    commitMessage = "feat: add New"
                )
            ),
            state = state()
        )

        val awaiting = assertIs<ClaudeCodeStepResult.AwaitingModApprove>(result)
        assertEquals(1, awaiting.heldCommands)
        assertTrue(pendingStore.hasPendingFor(sessionId))
        val pending = assertNotNull(pendingStore.take(sessionId))
        assertEquals(listOf(modification), pending.modifications)
        assertEquals("gradlew.bat test", pending.commands.single().command)
        assertEquals("feat: add New", pending.commitMessage)
        assertEquals(
            ClipboardSessionStatus.AWAITING_APPROVE,
            sessionManager.statusFor(sessionId)
        )
    }

    @Test
    fun `plan only response never creates pending modifications`() {
        val state = state(planOnly = true)

        val result = handler.handle(
            sessionId = sessionId,
            turn = turn(
                InteractionResponse(
                    message = "Planning only",
                    modifications = listOf(
                        InteractionModification(
                            type = "CREATE_FILE",
                            path = "file:src/New.kt",
                            content = "class New"
                        )
                    )
                )
            ),
            state = state
        )

        assertIs<ClaudeCodeStepResult.Completed>(result)
        assertFalse(pendingStore.hasPendingFor(sessionId))
        assertEquals(
            ClipboardSessionStatus.SESSION_ACTIVE,
            sessionManager.statusFor(sessionId)
        )
    }

    @Test
    fun `views mixed with commands emit warning event and do not hold commands`() {
        val result = handler.handle(
            sessionId = sessionId,
            turn = turn(
                InteractionResponse(
                    message = "Need view first",
                    codeViewRequests = listOf(
                        CodeViewRequest(
                            "src/Foo.kt",
                            CodeGranularity.FULL
                        )
                    ),
                    commands = listOf(
                        InteractionCommand(
                            command = "gradlew.bat test",
                            reason = "verify"
                        )
                    )
                )
            ),
            state = state()
        )

        assertEquals(1, assertIs<ClaudeCodeStepResult.WaitingForApprove>(result).skippedCommands)
        assertFalse(pendingStore.hasPendingFor(sessionId))
        assertTrue(
            sessionLog.events.any {
                it.text == "commands skipped (mixed with requestedViews)" &&
                        it.data?.get("count") == 1
            }
        )
    }

    @Test
    fun `questions result emits count event and keeps response metrics`() {
        val result = handler.handle(
            sessionId = sessionId,
            turn = ReceivedClaudeTurn(
                response = InteractionResponse(
                    message = "Choose",
                    questions = listOf(
                        InteractionQuestion(
                            id = "q1",
                            question = "A or B?"
                        ),
                        InteractionQuestion(
                            id = "q2",
                            question = "Now or later?"
                        )
                    )
                ),
                inputTokens = 123,
                outputTokens = 45,
                thinkingText = "thinking",
                durationMs = 900,
                costUsd = 0.02,
                numTurns = 2
            ),
            state = state()
        )

        assertEquals(2, assertIs<ClaudeCodeStepResult.AwaitingQuestions>(result).questions.size)
        assertTrue(
            sessionLog.events.any {
                it.text == "questions received" && it.data?.get("count") == 2
            }
        )
        val responseEvent = sessionLog.events.first {
            it.text == "response"
        }
        assertEquals(2, responseEvent.data?.get("questions"))
        assertEquals(8, responseEvent.data?.get("thinkingLen"))
    }

    private fun turn(
        response: InteractionResponse
    ) = ReceivedClaudeTurn(
        response = response,
        inputTokens = 100,
        outputTokens = 20
    )

    private fun state(
        planOnly: Boolean = false
    ) = ClipboardSessionState(
        currentMessage = "task",
        projectContext = FakeProjectContextPort.defaultContext(),
        dialogHistory = mutableListOf(),
        prompts = PromptTemplates(
            chatSystem = "system",
            planningSystem = "system"
        ),
        allGatheredFiles = mutableMapOf(),
        planOnly = planOnly
    )
}
