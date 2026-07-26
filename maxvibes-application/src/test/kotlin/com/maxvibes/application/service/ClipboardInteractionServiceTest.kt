package com.maxvibes.application.service

import com.maxvibes.application.port.output.*
import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.context.GatheredContext
import com.maxvibes.domain.model.context.ProjectContext
import com.maxvibes.domain.model.interaction.*
import com.maxvibes.shared.result.Result
import io.mockk.*
import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.maxvibes.domain.model.context.FileTree
import com.maxvibes.domain.model.context.FileNode
import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.domain.model.code.CodeGranularity

/**
 * Unit tests for [ClipboardInteractionService].
 *
 * Key design under test:
 * - addHistory=false (default, token-saving): [ClipboardRequest.previouslyGatheredPaths] is ALWAYS empty,
 *   globalContextFiles are NOT re-gathered, chatHistory is NOT included in the payload.
 * - addHistory=true: previously gathered file paths AND full chat history ARE included.
 * - redoLastRequest Scenario A: workspace owner matches → reuse existing state directly.
 * - redoLastRequest Scenario B: workspace belongs to another session → rebuild from domain.
 *
 * Session state transitions are mocked via [ClipboardSessionManager] — all [transition] calls
 * return true so the service logic is exercised without a real repository.
 */
class ClipboardInteractionServiceTest {

    /** Shared session ID used across most test scenarios. */
    private val SESSION_ID = "test-session"

    // ==================== Mocks ====================

    private val contextProvider = mockk<ProjectContextPort>()
    private val clipboardPort = mockk<ClipboardPort>()
    private val codeRepository = mockk<CodeRepository>(relaxed = true)
    private val notificationPort = mockk<NotificationPort>(relaxed = true)
    private val promptPort = mockk<PromptPort>()
    private val logger = mockk<LoggerPort>(relaxed = true)
    private val chatSessionRepository = mockk<ChatSessionRepository>(relaxed = true)

    /**
     * Mock session manager: all transitions succeed by default.
     * Individual tests stub [statusFor] to simulate specific lifecycle states.
     */
    private val sessionManager = mockk<ClipboardSessionManager>(relaxed = true)

    /** Captures every [ClipboardRequest] sent to the clipboard. */
    private val capturedRequest = slot<ClipboardRequest>()

    private lateinit var service: ClipboardInteractionService

    @BeforeEach
    fun setUp() {
        service = ClipboardInteractionService(
            contextProvider = contextProvider,
            clipboardPort = clipboardPort,
            codeRepository = codeRepository,
            notificationPort = notificationPort,
            promptPort = promptPort,
            logger = logger,
            sessionManager = sessionManager,
            chatSessionRepository = chatSessionRepository
        )
        every { clipboardPort.copyRequestToClipboard(capture(capturedRequest)) } returns true
        every { promptPort.getPrompts() } returns PromptTemplates(
            planningSystem = "planning-prompt",
            chatSystem = "chat-prompt"
        )
        // All transitions succeed by default
        every { sessionManager.transition(any(), any()) } returns true
        // Default status: IDLE unless overridden in a test
        every { sessionManager.statusFor(any()) } returns ClipboardSessionStatus.IDLE
        // saveSession is relaxed — no-op by default
        every { chatSessionRepository.saveSession(any()) } just Runs
    }

    // ==================== Helpers ====================

    private fun testProjectContext(name: String = "TestProject"): ProjectContext = ProjectContext(
        name = name,
        rootPath = "/tmp/test",
        fileTree = FileTree(
            root = FileNode(name = name, path = "", isDirectory = true),
            totalFiles = 5,
            totalDirectories = 1
        )
    )

    private fun stubProjectContext(ctx: ProjectContext = testProjectContext()) {
        coEvery { contextProvider.getProjectContext() } returns Result.Success(ctx)
    }

    private fun stubGatherFiles(files: Map<String, String>) {
        coEvery { contextProvider.gatherFiles(any(), any()) } returns
                Result.Success(GatheredContext(files = files, totalTokensEstimate = 0))
    }

    private fun simpleResponse(message: String = "Done.") = InteractionResponse(message = message)

    /**
     * Builds a minimal [ChatSession] mock for [redoLastRequest] Scenario B tests.
     * Only sets the fields that redoLastRequest reads from the domain.
     */
    private fun buildDomainSession(
        clipboardStatus: ClipboardSessionStatus,
        lastUserMessage: String,
        lastAssistantRequestedFiles: List<String> = emptyList()
    ): ChatSession {
        val messages = buildList {
            add(ChatMessage(role = MessageRole.USER, content = lastUserMessage))
            if (lastAssistantRequestedFiles.isNotEmpty()) {
                add(
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = "Need files",
                        requestedFiles = lastAssistantRequestedFiles
                    )
                )
            }
        }
        return mockk<ChatSession>(relaxed = true).also {
            every { it.clipboardStatus } returns clipboardStatus
            every { it.messages } returns messages
            every { it.copy(messages = any()) } returns it
        }
    }

    /**
     * Runs a full startTask → handlePastedResponse cycle.
     * After this the service has an active in-memory workspace (sessionState != null,
     * sessionStateOwner == SESSION_ID, status SESSION_ACTIVE).
     */
    private suspend fun startAndComplete(
        sessionId: String = SESSION_ID,
        currentMessage: String = "Fix the bug",
        globalContextFiles: List<String> = emptyList(),
        gatheredFiles: Map<String, String> = emptyMap()
    ) {
        stubProjectContext()
        stubGatherFiles(gatheredFiles)
        service.startTask(
            sessionId = sessionId,
            currentMessage = currentMessage,
            globalContextFiles = globalContextFiles
        )
        every { sessionManager.statusFor(sessionId) } returns ClipboardSessionStatus.AWAITING_PASTE
        every { clipboardPort.parseResponse(any()) } returns simpleResponse()
        service.handlePastedResponse(sessionId = sessionId, rawText = "{\"message\": \"ok\"}")
        every { sessionManager.statusFor(sessionId) } returns ClipboardSessionStatus.SESSION_ACTIVE
    }

    // ==================== Default behaviour (addHistory=false) ====================

    @Test
    fun `default - previouslyGatheredPaths is empty even when files were gathered`() = runBlocking {
        startAndComplete(
            globalContextFiles = listOf("src/Foo.kt", "src/Bar.kt"),
            gatheredFiles = mapOf("src/Foo.kt" to "foo", "src/Bar.kt" to "bar")
        )

        stubGatherFiles(emptyMap())
        service.continueDialog(sessionId = SESSION_ID, message = "next", addHistory = false)

        assertEquals(
            emptyList<String>(), capturedRequest.captured.previouslyGatheredPaths,
            "Token-saving default: previouslyGatheredPaths must be empty when addHistory=false"
        )
    }

    /**
     * Token-saving policy: when addHistory=false, globalContextFiles are NOT re-gathered.
     * LLM already has them from the first turn — freshFiles must be empty.
     */
    @Test
    fun `default - freshFiles is empty because globalContextFiles are skipped when addHistory=false`() = runBlocking {
        startAndComplete(
            globalContextFiles = listOf("src/Foo.kt"),
            gatheredFiles = mapOf("src/Foo.kt" to "foo-content")
        )

        stubGatherFiles(mapOf("src/New.kt" to "new-content"))
        service.continueDialog(
            sessionId = SESSION_ID,
            message = "continue",
            globalContextFiles = listOf("src/New.kt"),
            addHistory = false
        )

        assertEquals(
            emptyMap<String, String>(), capturedRequest.captured.freshFiles,
            "addHistory=false must skip globalContextFiles gather — LLM already has them"
        )
        assertEquals(emptyList<String>(), capturedRequest.captured.previouslyGatheredPaths)
    }

    // ==================== addHistory=true ====================

    @Test
    fun `addHistory=true - previouslyGatheredPaths contains all gathered file paths`() = runBlocking {
        startAndComplete(
            globalContextFiles = listOf("src/Foo.kt", "src/Bar.kt"),
            gatheredFiles = mapOf("src/Foo.kt" to "foo", "src/Bar.kt" to "bar")
        )

        stubGatherFiles(emptyMap())
        service.continueDialog(sessionId = SESSION_ID, message = "new chat", addHistory = true)

        assertTrue(
            capturedRequest.captured.previouslyGatheredPaths.containsAll(listOf("src/Foo.kt", "src/Bar.kt")),
            "addHistory=true must populate previouslyGatheredPaths with all gathered paths"
        )
        assertEquals(emptyMap<String, String>(), capturedRequest.captured.freshFiles)
    }

    @Test
    fun `addHistory=true on first message - previouslyGatheredPaths is empty (nothing gathered yet)`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())

        service.startTask(sessionId = SESSION_ID, currentMessage = "Task", addHistory = true)

        assertEquals(emptyList<String>(), capturedRequest.captured.previouslyGatheredPaths)
    }

    @Test
    fun `addHistory=true includes files gathered across multiple turns`() = runBlocking {
        startAndComplete(
            globalContextFiles = listOf("src/Foo.kt"),
            gatheredFiles = mapOf("src/Foo.kt" to "foo")
        )
        stubGatherFiles(mapOf("src/Bar.kt" to "bar"))
        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.AWAITING_PASTE
        every { clipboardPort.parseResponse(any()) } returns InteractionResponse(
            message = "need Bar",
            codeViewRequests = listOf(CodeViewRequest("src/Bar.kt", CodeGranularity.FULL))
        )
        service.handlePastedResponse(sessionId = SESSION_ID, rawText = "{...}")
        every { clipboardPort.parseResponse(any()) } returns simpleResponse()
        service.handlePastedResponse(sessionId = SESSION_ID, rawText = "{...}")
        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.SESSION_ACTIVE

        stubGatherFiles(emptyMap())
        service.continueDialog(sessionId = SESSION_ID, message = "new chat", addHistory = true)

        val paths = capturedRequest.captured.previouslyGatheredPaths
        assertTrue(paths.contains("src/Foo.kt"), "Foo.kt must appear in previouslyGatheredPaths")
        assertTrue(paths.contains("src/Bar.kt"), "Bar.kt must appear in previouslyGatheredPaths")
    }

    @Test
    fun `addHistory toggles per-message - false reverts to empty paths`() = runBlocking {
        startAndComplete(
            globalContextFiles = listOf("src/Foo.kt"),
            gatheredFiles = mapOf("src/Foo.kt" to "foo")
        )
        stubGatherFiles(emptyMap())

        service.continueDialog(sessionId = SESSION_ID, message = "with history", addHistory = true)
        assertTrue(capturedRequest.captured.previouslyGatheredPaths.contains("src/Foo.kt"))

        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.AWAITING_PASTE
        every { clipboardPort.parseResponse(any()) } returns simpleResponse()
        service.handlePastedResponse(sessionId = SESSION_ID, rawText = "{...}")
        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.SESSION_ACTIVE

        service.continueDialog(sessionId = SESSION_ID, message = "without history", addHistory = false)
        assertEquals(
            emptyList<String>(), capturedRequest.captured.previouslyGatheredPaths,
            "Reverting to addHistory=false must empty previouslyGatheredPaths again"
        )
    }

    // ==================== startTask basics ====================

    @Test
    fun `startTask returns WaitingForResponse in PLANNING phase`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())

        val result = service.startTask(sessionId = SESSION_ID, currentMessage = "Do X")

        assertInstanceOf(ClipboardStepResult.WaitingForResponse::class.java, result)
        assertEquals(InteractionPhase.PLANNING, (result as ClipboardStepResult.WaitingForResponse).phase)
    }

    @Test
    fun `startTask with globalContextFiles includes gathered content in freshFiles`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(mapOf("src/Foo.kt" to "foo-content"))

        service.startTask(
            sessionId = SESSION_ID,
            currentMessage = "Task",
            globalContextFiles = listOf("src/Foo.kt")
        )

        assertEquals(mapOf("src/Foo.kt" to "foo-content"), capturedRequest.captured.freshFiles)
    }

    @Test
    fun `startTask propagates project context failure as Error`(): Unit = runBlocking {
        coEvery { contextProvider.getProjectContext() } returns
                Result.Failure(ContextError.FileReadError("project", "network error"))

        val result = service.startTask(sessionId = SESSION_ID, currentMessage = "Task")

        assertInstanceOf(ClipboardStepResult.Error::class.java, result)
    }

    @Test
    fun `startTask records task in dialog history`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())

        service.startTask(sessionId = SESSION_ID, currentMessage = "My important task")

        assertTrue(
            capturedRequest.captured.chatHistory.any { it.role == "user" && it.content == "My important task" }
        )
    }

    @Test
    fun `startTask with globalContextFiles produces CHAT phase in request`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(mapOf("src/Foo.kt" to "content"))

        service.startTask(
            sessionId = SESSION_ID,
            currentMessage = "Task",
            globalContextFiles = listOf("src/Foo.kt")
        )

        assertEquals(InteractionPhase.CHAT, capturedRequest.captured.phase)
    }

    // ==================== continueDialog basics ====================

    @Test
    fun `continueDialog without active session returns Error`() = runBlocking {
        val result = service.continueDialog(sessionId = SESSION_ID, message = "next step")

        assertInstanceOf(ClipboardStepResult.Error::class.java, result)
        // New contract: the service first tries to restore the workspace from the repository;
        // with no persisted user messages the restore fails with this message.
        assertTrue((result as ClipboardStepResult.Error).message.contains("Cannot restore session state"))
    }

    /**
     * Verifies that continueDialog appends the message to dialogHistory.
     * Uses addHistory=true so chatHistory is included in the outgoing request.
     * With addHistory=false (token-saving), chatHistory is omitted by design.
     */
    @Test
    fun `continueDialog appends message to dialog history (verified via addHistory=true)`() = runBlocking {
        startAndComplete()
        stubGatherFiles(emptyMap())

        service.continueDialog(
            sessionId = SESSION_ID,
            message = "Please also fix Bar",
            addHistory = true
        )

        assertTrue(
            capturedRequest.captured.chatHistory.any { it.role == "user" && it.content == "Please also fix Bar" },
            "The user message must appear in chatHistory when addHistory=true"
        )
    }

    // ==================== handlePastedResponse ====================

    @Test
    fun `handlePastedResponse without active session returns Error`(): Unit = runBlocking {
        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.AWAITING_PASTE

        assertInstanceOf(
            ClipboardStepResult.Error::class.java,
            service.handlePastedResponse(sessionId = SESSION_ID, rawText = "{}")
        )
    }

    @Test
    fun `handlePastedResponse when parseResponse returns null returns Error`(): Unit = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(sessionId = SESSION_ID, currentMessage = "Task")
        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.AWAITING_PASTE
        every { clipboardPort.parseResponse(any()) } returns null

        assertInstanceOf(
            ClipboardStepResult.Error::class.java,
            service.handlePastedResponse(sessionId = SESSION_ID, rawText = "bad")
        )
    }

    @Test
    fun `handlePastedResponse with codeViewRequests triggers another WaitingForResponse`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(sessionId = SESSION_ID, currentMessage = "Task")
        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.AWAITING_PASTE

        stubGatherFiles(mapOf("src/Foo.kt" to "foo-content"))
        every { clipboardPort.parseResponse(any()) } returns InteractionResponse(
            message = "need Foo",
            codeViewRequests = listOf(CodeViewRequest("src/Foo.kt", CodeGranularity.FULL))
        )

        val result = service.handlePastedResponse(sessionId = SESSION_ID, rawText = "{...}")

        assertInstanceOf(ClipboardStepResult.WaitingForResponse::class.java, result)
        assertEquals(mapOf("src/Foo.kt" to "foo-content"), capturedRequest.captured.freshFiles)
    }

    @Test
    fun `handlePastedResponse with no requestedFiles and no mods returns Completed`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(sessionId = SESSION_ID, currentMessage = "Task")
        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.AWAITING_PASTE
        every { clipboardPort.parseResponse(any()) } returns simpleResponse("All done!")

        val result = service.handlePastedResponse(sessionId = SESSION_ID, rawText = "{...}")

        assertInstanceOf(ClipboardStepResult.Completed::class.java, result)
        assertTrue((result as ClipboardStepResult.Completed).message.contains("All done!"))
    }

    @Test
    fun `handlePastedResponse propagates commitMessage`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(sessionId = SESSION_ID, currentMessage = "Task")
        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.AWAITING_PASTE
        every { clipboardPort.parseResponse(any()) } returns
                InteractionResponse(message = "Done", commitMessage = "feat: add X")

        val result = service.handlePastedResponse(sessionId = SESSION_ID, rawText = "{...}")

        assertEquals("feat: add X", (result as ClipboardStepResult.Completed).commitMessage)
    }

    // ==================== Session lifecycle ====================

    @Test
    fun `session is inactive initially - continueDialog returns Error before startTask`() = runBlocking {
        val result = service.continueDialog(sessionId = SESSION_ID, message = "premature")

        assertInstanceOf(ClipboardStepResult.Error::class.java, result)
        assertTrue((result as ClipboardStepResult.Error).message.contains("Cannot restore session state"))
    }

    @Test
    fun `after startTask - continueDialog succeeds (session is active)`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(sessionId = SESSION_ID, currentMessage = "Task")
        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.SESSION_ACTIVE

        stubGatherFiles(emptyMap())
        val result = service.continueDialog(sessionId = SESSION_ID, message = "follow-up")

        assertInstanceOf(ClipboardStepResult.WaitingForResponse::class.java, result)
    }

    @Test
    fun `reset clears in-memory session - continueDialog returns Error after reset`(): Unit = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(sessionId = SESSION_ID, currentMessage = "Task")

        service.reset(SESSION_ID)

        assertInstanceOf(
            ClipboardStepResult.Error::class.java,
            service.continueDialog(sessionId = SESSION_ID, message = "after reset")
        )
    }

    @Test
    fun `status returns AWAITING_PASTE while waiting for LLM response`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(sessionId = SESSION_ID, currentMessage = "Task")
        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.AWAITING_PASTE

        assertEquals(ClipboardSessionStatus.AWAITING_PASTE, service.status(SESSION_ID))
    }

    @Test
    fun `status returns SESSION_ACTIVE after handlePastedResponse`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(sessionId = SESSION_ID, currentMessage = "Task")
        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.AWAITING_PASTE
        every { clipboardPort.parseResponse(any()) } returns simpleResponse()
        service.handlePastedResponse(sessionId = SESSION_ID, rawText = "{\"message\": \"done\"}")
        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.SESSION_ACTIVE

        assertEquals(ClipboardSessionStatus.SESSION_ACTIVE, service.status(SESSION_ID))
    }

    // ==================== redoLastRequest — Scenario A (workspace owner matches) ====================

    /**
     * Scenario A: sessionStateOwner == sessionId.
     * Redo reuses the existing in-memory workspace directly. The service MAY read the
     * repository for bookkeeping (plan/history persistence) — the essential contract is
     * that redo succeeds from in-memory state without requiring a domain rebuild.
     */
    @Test
    fun `redoLastRequest scenario A - reuses existing workspace when owner matches`(): Unit = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())
        // startTask sets sessionStateOwner = SESSION_ID
        service.startTask(sessionId = SESSION_ID, currentMessage = "Task")

        val result = service.redoLastRequest(sessionId = SESSION_ID, globalContextFiles = emptyList())

        assertInstanceOf(
            ClipboardStepResult.WaitingForResponse::class.java,
            result,
            "Scenario A: redo must succeed when workspace belongs to the same session"
        )
    }

    // ==================== redoLastRequest — Scenario B (workspace belongs to another session) ====================

    /**
     * Core bug scenario: Session A generates → Session B generates (overwrites workspace).
     * Copy JSON for Session A must still work — reads domain, rebuilds workspace.
     */
    @Test
    fun `redoLastRequest scenario B - works for session A even when session B owns workspace`(): Unit = runBlocking {
        val SESSION_A = "session-a"
        val SESSION_B = "session-b"

        // Session B generates last — sessionStateOwner = B
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(sessionId = SESSION_B, currentMessage = "Task B")

        // Domain has Session A in AWAITING_PASTE
        every { chatSessionRepository.getSessionById(SESSION_A) } returns
                buildDomainSession(
                    clipboardStatus = ClipboardSessionStatus.AWAITING_PASTE,
                    lastUserMessage = "Task A"
                )
        stubProjectContext()
        stubGatherFiles(emptyMap())

        val result = service.redoLastRequest(sessionId = SESSION_A, globalContextFiles = emptyList())

        assertInstanceOf(
            ClipboardStepResult.WaitingForResponse::class.java,
            result,
            "Scenario B: redo must succeed for session A even though session B owns the workspace"
        )
    }

    /**
     * Scenario B: requestedFiles from the last ASSISTANT message are passed to gatherFiles.
     */
    @Test
    fun `redoLastRequest scenario B - gathers files from last assistant requestedFiles`(): Unit = runBlocking {
        val SESSION_B = "session-b"
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(sessionId = SESSION_B, currentMessage = "Task B")

        every { chatSessionRepository.getSessionById(SESSION_ID) } returns
                buildDomainSession(
                    clipboardStatus = ClipboardSessionStatus.AWAITING_PASTE,
                    lastUserMessage = "Task A",
                    lastAssistantRequestedFiles = listOf("src/Foo.kt", "src/Bar.kt")
                )
        stubProjectContext()
        stubGatherFiles(emptyMap())

        service.redoLastRequest(sessionId = SESSION_ID, globalContextFiles = emptyList())

        coVerify {
            contextProvider.gatherFiles(
                match { it.containsAll(listOf("src/Foo.kt", "src/Bar.kt")) }
            )
        }
    }

    /**
     * Scenario B: session has IDLE status → Error (Generate was never called).
     */
    @Test
    fun `redoLastRequest scenario B - returns Error when session status is IDLE`(): Unit = runBlocking {
        val SESSION_B = "session-b"
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(sessionId = SESSION_B, currentMessage = "Task B")

        every { chatSessionRepository.getSessionById(SESSION_ID) } returns
                buildDomainSession(
                    clipboardStatus = ClipboardSessionStatus.IDLE,
                    lastUserMessage = "some message"
                )

        val result = service.redoLastRequest(sessionId = SESSION_ID, globalContextFiles = emptyList())

        assertInstanceOf(
            ClipboardStepResult.Error::class.java,
            result,
            "Scenario B: IDLE session must return Error — Generate was never called"
        )
    }

    /**
     * Scenario B: session not found in repository → Error.
     */
    @Test
    fun `redoLastRequest scenario B - returns Error when session not found in repository`(): Unit = runBlocking {
        val SESSION_B = "session-b"
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(sessionId = SESSION_B, currentMessage = "Task B")

        every { chatSessionRepository.getSessionById(SESSION_ID) } returns null

        val result = service.redoLastRequest(sessionId = SESSION_ID, globalContextFiles = emptyList())

        assertInstanceOf(ClipboardStepResult.Error::class.java, result)
    }

    /**
     * No Generate ever called (no startTask, sessionStateOwner = null).
     * Falls through to Scenario B → session not found → Error.
     */
    @Test
    fun `redoLastRequest - returns Error when no session was ever started`(): Unit = runBlocking {
        every { chatSessionRepository.getSessionById(SESSION_ID) } returns null

        val result = service.redoLastRequest(sessionId = SESSION_ID, globalContextFiles = emptyList())

        assertInstanceOf(ClipboardStepResult.Error::class.java, result)
    }
}
