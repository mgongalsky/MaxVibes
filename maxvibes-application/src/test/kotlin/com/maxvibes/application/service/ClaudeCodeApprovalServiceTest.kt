package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.application.port.output.PromptTemplates
import com.maxvibes.application.testsupport.FakeProjectContextPort
import com.maxvibes.application.testsupport.InMemoryChatSessionRepository
import com.maxvibes.application.testsupport.RecordingClaudeCodeSessionLogPort
import com.maxvibes.application.testsupport.RecordingNotificationPort
import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.code.CodeGranularity
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.code.RequestedViewInfo
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.modification.ModificationError
import com.maxvibes.domain.model.modification.ModificationResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeCodeApprovalServiceTest {
    private val sessionId = "session-1"

    private lateinit var repository: InMemoryChatSessionRepository
    private lateinit var sessionManager: ClipboardSessionManager
    private lateinit var pendingStore: PendingModificationsStore
    private lateinit var workspaceService: ClaudeCodeWorkspaceService
    private lateinit var viewResolver: CodingAgentViewResolver
    private lateinit var codeRepository: CodeRepository
    private lateinit var notifications: RecordingNotificationPort
    private lateinit var sessionLog: RecordingClaudeCodeSessionLogPort
    private lateinit var service: CodingAgentApprovalService

    @BeforeEach
    fun setUp() {
        repository = InMemoryChatSessionRepository()
        sessionManager = ClipboardSessionManager(repository)
        pendingStore = PendingModificationsStore()
        workspaceService = mockk(relaxed = true)
        viewResolver = mockk()
        codeRepository = mockk(relaxed = true)
        notifications = RecordingNotificationPort()
        sessionLog = RecordingClaudeCodeSessionLogPort()
        service = CodingAgentApprovalService(
            chatSessionRepository = repository,
            sessionManager = sessionManager,
            pendingStore = pendingStore,
            workspaceService = workspaceService,
            viewResolver = viewResolver,
            codeRepository = codeRepository,
            notificationPort = notifications,
            sessionLog = sessionLog
        )
    }

    @Test
    fun `reject without pending returns null and preserves status`() {
        putSession(status = ClipboardSessionStatus.AWAITING_APPROVE)

        val result = service.rejectPending(
            UserInputCommand(
                sessionId = sessionId,
                userInput = "New instruction"
            )
        )

        assertNull(result)
        assertEquals(
            ClipboardSessionStatus.AWAITING_APPROVE,
            sessionManager.statusFor(sessionId)
        )
        assertTrue(sessionLog.events.isEmpty())
        coVerify(exactly = 0) { codeRepository.applyModifications(any()) }
    }

    @Test
    fun `reject consumes pending set and prefixes new instruction`() {
        putSession(status = ClipboardSessionStatus.AWAITING_APPROVE)
        pendingStore.hold(
            sessionId = sessionId,
            modifications = listOf(protocolModification()),
            commands = listOf(commandRequest())
        )

        val result = service.rejectPending(
            UserInputCommand(
                sessionId = sessionId,
                userInput = "Use another approach"
            )
        )!!

        assertTrue(
            result.userInput.contains(
                "USER REJECTED your 1 proposed modification(s)"
            )
        )
        assertTrue(
            result.userInput.contains(
                "the 1 held command(s) were not run"
            )
        )
        assertTrue(result.userInput.endsWith("Use another approach"))
        assertFalse(pendingStore.hasPendingFor(sessionId))
        assertEquals(
            ClipboardSessionStatus.SESSION_ACTIVE,
            sessionManager.statusFor(sessionId)
        )
        assertTrue(
            sessionLog.events.any {
                it.text == "pending modifications rejected" &&
                        it.data?.get("mods") == 1 &&
                        it.data?.get("heldCommands") == 1
            }
        )
        coVerify(exactly = 0) { codeRepository.applyModifications(any()) }
    }

    @Test
    fun `approve outside awaiting state returns error without side effects`() = runBlocking {
        putSession(status = ClipboardSessionStatus.SESSION_ACTIVE)

        val outcome = service.approve(sessionId)

        val immediate = assertIs<CodingAgentApprovalOutcome.Immediate>(outcome)
        assertEquals(
            "Approve is only valid in AWAITING_APPROVE state",
            assertIs<ClaudeCodeStepResult.Error>(immediate.result).message
        )
        coVerify(exactly = 0) { viewResolver.resolve(any(), any()) }
        coVerify(exactly = 0) { codeRepository.applyModifications(any()) }
    }

    @Test
    fun `approve pending modifications applies converted changes and releases metadata`() = runBlocking {
        putSession(status = ClipboardSessionStatus.AWAITING_APPROVE)
        val protocolModification = protocolModification()
        val domainModification = Modification.CreateFile(
            targetPath = ElementPath("file:src/New.kt"),
            content = "class New"
        )
        val modificationResult = ModificationResult.Success(
            modification = domainModification,
            affectedPath = ElementPath("file:src/New.kt"),
            resultContent = "class New"
        )
        pendingStore.hold(
            sessionId = sessionId,
            modifications = listOf(protocolModification),
            commands = listOf(commandRequest()),
            commitMessage = "feat: add New"
        )
        coEvery {
            codeRepository.applyModifications(any())
        } returns listOf(modificationResult)

        val outcome = service.approve(sessionId)

        val immediate = assertIs<CodingAgentApprovalOutcome.Immediate>(outcome)
        val completed = assertIs<ClaudeCodeStepResult.Completed>(immediate.result)
        assertTrue(completed.success)
        assertEquals("feat: add New", completed.commitMessage)
        assertEquals("gradlew.bat test", completed.commands.single().command)
        assertEquals(listOf(modificationResult), completed.modifications)
        assertFalse(pendingStore.hasPendingFor(sessionId))
        assertEquals(
            ClipboardSessionStatus.SESSION_ACTIVE,
            sessionManager.statusFor(sessionId)
        )
        assertEquals(listOf("Applied 1 changes"), notifications.successes)
        assertTrue(notifications.warnings.isEmpty())
        coVerify(exactly = 1) {
            codeRepository.applyModifications(
                match {
                    it.size == 1 &&
                            it.single() == domainModification
                }
            )
        }
    }

    @Test
    fun `partial modification failure returns unsuccessful completion and warning`() = runBlocking {
        putSession(status = ClipboardSessionStatus.AWAITING_APPROVE)
        val create = protocolModification()
        val delete = InteractionModification(
            type = "DELETE_FILE",
            path = "file:src/Old.kt"
        )
        val createDomain = Modification.CreateFile(
            ElementPath("file:src/New.kt"),
            "class New"
        )
        val deleteDomain = Modification.DeleteFile(
            ElementPath("file:src/Old.kt")
        )
        pendingStore.hold(
            sessionId = sessionId,
            modifications = listOf(create, delete)
        )
        coEvery {
            codeRepository.applyModifications(any())
        } returns listOf(
            ModificationResult.Success(
                modification = createDomain,
                affectedPath = ElementPath("file:src/New.kt"),
                resultContent = "class New"
            ),
            ModificationResult.Failure(
                modification = deleteDomain,
                error = ModificationError.FileNotFound("src/Old.kt")
            )
        )

        val outcome = service.approve(sessionId)

        val completed = assertIs<ClaudeCodeStepResult.Completed>(
            assertIs<CodingAgentApprovalOutcome.Immediate>(outcome).result
        )
        assertFalse(completed.success)
        assertEquals(2, completed.modifications.size)
        assertEquals(
            listOf("Applied 1 changes, 1 failed"),
            notifications.warnings
        )
        assertTrue(notifications.successes.isEmpty())
    }

    @Test
    fun `invalid protocol modifications are dropped without repository call`() = runBlocking {
        putSession(status = ClipboardSessionStatus.AWAITING_APPROVE)
        pendingStore.hold(
            sessionId = sessionId,
            modifications = listOf(
                InteractionModification(
                    type = "",
                    path = "file:src/New.kt",
                    content = "class New"
                ),
                InteractionModification(
                    type = "UNKNOWN",
                    path = "file:src/Other.kt"
                )
            )
        )

        val outcome = service.approve(sessionId)

        val completed = assertIs<ClaudeCodeStepResult.Completed>(
            assertIs<CodingAgentApprovalOutcome.Immediate>(outcome).result
        )
        assertTrue(completed.success)
        assertTrue(completed.modifications.isEmpty())
        assertTrue(notifications.successes.isEmpty())
        assertTrue(notifications.warnings.isEmpty())
        coVerify(exactly = 0) { codeRepository.applyModifications(any()) }
    }

    @Test
    fun `approve requested views uses owned workspace and returns continuation`() = runBlocking {
        val assistantContent = "Need Foo"
        putSession(
            status = ClipboardSessionStatus.AWAITING_APPROVE,
            messages = listOf(
                ChatMessage(
                    role = MessageRole.USER,
                    content = "Inspect Foo"
                ),
                ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = assistantContent,
                    requestedViews = listOf(
                        RequestedViewInfo(
                            path = "src/Foo.kt",
                            granularity = CodeGranularity.FULL
                        )
                    )
                )
            )
        )
        val state = state(
            history = mutableListOf(
                ChatMessageDTO(ChatRole.USER, "Inspect Foo")
            )
        )
        every { workspaceService.state } returns state
        every { workspaceService.owner } returns sessionId
        coEvery {
            viewResolver.resolve(any(), state)
        } returns mapOf("src/Foo.kt" to "class Foo")

        val outcome = service.approve(
            sessionId = sessionId,
            attachedContext = "selection",
            ideErrors = "error",
            specificPromptContent = "review"
        )

        val continuation = assertIs<CodingAgentApprovalOutcome.Continue>(outcome)
        assertEquals(sessionId, continuation.command.sessionId)
        assertEquals(
            mapOf("src/Foo.kt" to "class Foo"),
            continuation.command.freshFiles
        )
        assertEquals("selection", continuation.command.attachedContext)
        assertEquals("error", continuation.command.ideErrors)
        assertEquals("review", continuation.command.specificPromptContent)
        assertEquals(
            ClipboardSessionStatus.SESSION_ACTIVE,
            sessionManager.statusFor(sessionId)
        )
        coVerify(exactly = 0) { workspaceService.ensure(any()) }
        verify(exactly = 1) {
            workspaceService.appendAssistantHistory(assistantContent)
        }
    }

    @Test
    fun `approve restores missing workspace before resolving requested views`() = runBlocking {
        putSession(
            status = ClipboardSessionStatus.AWAITING_APPROVE,
            messages = assistantWithView()
        )
        val restoredState = state()
        every { workspaceService.state } returnsMany listOf(null, restoredState)
        coEvery { workspaceService.ensure(sessionId) } returns true
        coEvery {
            viewResolver.resolve(any(), restoredState)
        } returns mapOf("src/Foo.kt" to "class Foo")

        val outcome = service.approve(sessionId)

        assertIs<CodingAgentApprovalOutcome.Continue>(outcome)
        coVerify(exactly = 1) { workspaceService.ensure(sessionId) }
        coVerify(exactly = 1) { viewResolver.resolve(any(), restoredState) }
    }

    @Test
    fun `workspace restore failure returns error and preserves awaiting status`() = runBlocking {
        putSession(
            status = ClipboardSessionStatus.AWAITING_APPROVE,
            messages = assistantWithView()
        )
        every { workspaceService.state } returns null
        coEvery { workspaceService.ensure(sessionId) } returns false

        val outcome = service.approve(sessionId)

        val error = assertIs<ClaudeCodeStepResult.Error>(
            assertIs<CodingAgentApprovalOutcome.Immediate>(outcome).result
        )
        assertTrue(error.message.contains("Cannot restore session state"))
        assertEquals(
            ClipboardSessionStatus.AWAITING_APPROVE,
            sessionManager.statusFor(sessionId)
        )
        coVerify(exactly = 0) { viewResolver.resolve(any(), any()) }
    }

    @Test
    fun `missing domain session after workspace validation returns error`() = runBlocking {
        repository.put(
            ChatSession(
                id = "status-owner",
                clipboardStatus = ClipboardSessionStatus.AWAITING_APPROVE
            )
        )
        val managerForMissingSession = ClipboardSessionManager(repository)
        val missingService = CodingAgentApprovalService(
            chatSessionRepository = repository,
            sessionManager = managerForMissingSession,
            pendingStore = pendingStore,
            workspaceService = workspaceService,
            viewResolver = viewResolver,
            codeRepository = codeRepository,
            notificationPort = notifications,
            sessionLog = sessionLog
        )
        every { workspaceService.state } returns state()
        every { workspaceService.owner } returns "missing"

        val outcome = missingService.approve("missing")

        val error = assertIs<ClaudeCodeStepResult.Error>(
            assertIs<CodingAgentApprovalOutcome.Immediate>(outcome).result
        )
        assertTrue(
            error.message == "Approve is only valid in AWAITING_APPROVE state" ||
                    error.message == "Session not found: missing"
        )
    }

    @Test
    fun `session without assistant message returns explicit error`() = runBlocking {
        putSession(
            status = ClipboardSessionStatus.AWAITING_APPROVE,
            messages = listOf(
                ChatMessage(
                    role = MessageRole.USER,
                    content = "Inspect"
                )
            )
        )
        every { workspaceService.state } returns state()
        every { workspaceService.owner } returns sessionId

        val outcome = service.approve(sessionId)

        assertEquals(
            "No assistant message to approve",
            assertIs<ClaudeCodeStepResult.Error>(
                assertIs<CodingAgentApprovalOutcome.Immediate>(outcome).result
            ).message
        )
        coVerify(exactly = 0) { viewResolver.resolve(any(), any()) }
    }

    @Test
    fun `assistant without requested views returns explicit error`() = runBlocking {
        putSession(
            status = ClipboardSessionStatus.AWAITING_APPROVE,
            messages = listOf(
                ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = "Nothing requested"
                )
            )
        )
        every { workspaceService.state } returns state()
        every { workspaceService.owner } returns sessionId

        val outcome = service.approve(sessionId)

        assertEquals(
            "Last assistant message has no requestedViews to approve",
            assertIs<ClaudeCodeStepResult.Error>(
                assertIs<CodingAgentApprovalOutcome.Immediate>(outcome).result
            ).message
        )
        coVerify(exactly = 0) { viewResolver.resolve(any(), any()) }
    }

    @Test
    fun `resolver failure returns error without approving transition`() = runBlocking {
        putSession(
            status = ClipboardSessionStatus.AWAITING_APPROVE,
            messages = assistantWithView()
        )
        val state = state()
        every { workspaceService.state } returns state
        every { workspaceService.owner } returns sessionId
        coEvery { viewResolver.resolve(any(), state) } returns null

        val outcome = service.approve(sessionId)

        assertEquals(
            "Failed to gather requested files",
            assertIs<ClaudeCodeStepResult.Error>(
                assertIs<CodingAgentApprovalOutcome.Immediate>(outcome).result
            ).message
        )
        assertEquals(
            ClipboardSessionStatus.AWAITING_APPROVE,
            sessionManager.statusFor(sessionId)
        )
    }

    @Test
    fun `assistant history is not duplicated when latest content already matches`() = runBlocking {
        putSession(
            status = ClipboardSessionStatus.AWAITING_APPROVE,
            messages = assistantWithView(content = "Need Foo")
        )
        val state = state(
            history = mutableListOf(
                ChatMessageDTO(ChatRole.ASSISTANT, "Need Foo")
            )
        )
        every { workspaceService.state } returns state
        every { workspaceService.owner } returns sessionId
        coEvery {
            viewResolver.resolve(any(), state)
        } returns mapOf("src/Foo.kt" to "class Foo")

        val outcome = service.approve(sessionId)

        assertIs<CodingAgentApprovalOutcome.Continue>(outcome)
        verify(exactly = 0) {
            workspaceService.appendAssistantHistory(any())
        }
    }

    private fun putSession(
        status: ClipboardSessionStatus,
        messages: List<ChatMessage> = emptyList()
    ) {
        repository.put(
            ChatSession(
                id = sessionId,
                clipboardStatus = status,
                messages = messages
            )
        )
    }

    private fun assistantWithView(
        content: String = "Need Foo"
    ) = listOf(
        ChatMessage(
            role = MessageRole.USER,
            content = "Inspect Foo"
        ),
        ChatMessage(
            role = MessageRole.ASSISTANT,
            content = content,
            requestedViews = listOf(
                RequestedViewInfo(
                    path = "src/Foo.kt",
                    granularity = CodeGranularity.FULL
                )
            )
        )
    )

    private fun protocolModification() = InteractionModification(
        type = "CREATE_FILE",
        path = "file:src/New.kt",
        content = "class New"
    )

    private fun commandRequest() = CommandRequest(
        command = "gradlew.bat test",
        reason = "verify",
        timeoutSec = 300
    )

    private fun state(
        history: MutableList<ChatMessageDTO> = mutableListOf()
    ) = ClipboardSessionState(
        currentMessage = "Inspect Foo",
        projectContext = FakeProjectContextPort.defaultContext(),
        dialogHistory = history,
        prompts = PromptTemplates(
            chatSystem = "SYSTEM",
            planningSystem = "SYSTEM"
        ),
        allGatheredFiles = mutableMapOf(),
        planOnly = false
    )

    @Test
    fun `rolled back batch suppresses held commands and commit message`() = runBlocking {
        putSession(status = ClipboardSessionStatus.AWAITING_APPROVE)
        val domainModification = Modification.CreateFile(
            targetPath = ElementPath("file:src/New.kt"),
            content = "class New"
        )
        pendingStore.hold(
            sessionId = sessionId,
            modifications = listOf(protocolModification()),
            commands = listOf(commandRequest()),
            commitMessage = "feat: add New"
        )
        coEvery {
            codeRepository.applyModifications(any())
        } returns listOf(
            ModificationResult.Failure(
                modification = domainModification,
                error = ModificationError.BatchRolledBack(
                    failedOperation = 0,
                    reason = "parse failure"
                )
            )
        )

        val outcome = service.approve(sessionId)

        val completed = assertIs<ClaudeCodeStepResult.Completed>(
            assertIs<CodingAgentApprovalOutcome.Immediate>(outcome).result
        )
        assertFalse(completed.success)
        assertTrue(completed.commands.isEmpty())
        assertNull(completed.commitMessage)
        assertTrue(completed.message.contains("Original project state was restored"))
        assertEquals(
            listOf("Modification batch failed and was rolled back: 0 of 1 applied"),
            notifications.warnings
        )
        assertTrue(notifications.successes.isEmpty())
    }
}
