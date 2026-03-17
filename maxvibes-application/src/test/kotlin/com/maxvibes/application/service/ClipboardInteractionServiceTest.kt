package com.maxvibes.application.service

import com.maxvibes.application.port.output.*
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

/**
 * Unit tests for [ClipboardInteractionService].
 *
 * Key design under test:
 * - addHistory=false (default): [ClipboardRequest.previouslyGatheredPaths] is ALWAYS empty.
 * - addHistory=true: previously gathered file paths ARE included.
 *
 * Session state transitions are mocked via [ClipboardSessionManager] — all [transition] calls
 * return true so the service logic is exercised without a real repository.
 */
class ClipboardInteractionServiceTest {

    /** Shared session ID used across all test scenarios. */
    private val SESSION_ID = "test-session"

    // ==================== Mocks ====================

    private val contextProvider = mockk<ProjectContextPort>()
    private val clipboardPort = mockk<ClipboardPort>()
    private val codeRepository = mockk<CodeRepository>(relaxed = true)
    private val notificationPort = mockk<NotificationPort>(relaxed = true)
    private val promptPort = mockk<PromptPort>()
    private val logger = mockk<LoggerPort>(relaxed = true)

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
            sessionManager = sessionManager
        )
        every { clipboardPort.copyRequestToClipboard(capture(capturedRequest)) } returns true
        every { promptPort.getPrompts() } returns PromptTemplates(
            planningSystem = "planning-prompt",
            chatSystem = "chat-prompt"
        )
        // All transitions succeed by default — tests that need specific status stub statusFor()
        every { sessionManager.transition(any(), any()) } returns true
        // Default status: IDLE unless overridden in a test
        every { sessionManager.statusFor(any()) } returns ClipboardSessionStatus.IDLE
    }

    // ==================== Helpers ====================

    private fun mockProjectContext(name: String = "TestProject"): ProjectContext {
        val fileTree = mockk<com.maxvibes.domain.model.context.FileTree>(relaxed = true)
        every { fileTree.totalFiles } returns 5
        every { fileTree.toCompactString(any()) } returns "mock-tree"
        return mockk<ProjectContext>().also {
            every { it.name } returns name
            every { it.fileTree } returns fileTree
        }
    }

    private fun stubProjectContext(ctx: ProjectContext = mockProjectContext()) {
        coEvery { contextProvider.getProjectContext() } returns Result.Success(ctx)
    }

    private fun stubGatherFiles(files: Map<String, String>) {
        val gathered = mockk<GatheredContext>()
        every { gathered.files } returns files
        coEvery { contextProvider.gatherFiles(any()) } returns Result.Success(gathered)
    }

    private fun simpleResponse(message: String = "Done.") = ClipboardResponse(message = message)

    /**
     * Runs a full startTask → handlePastedResponse cycle so files end up in allGatheredFiles.
     * After this the service has an active in-memory session (sessionState != null).
     *
     * Note: [sessionManager.statusFor] must return AWAITING_PASTE after startTask for
     * handlePastedResponse to accept the transition. This is ensured by stubbing it to
     * AWAITING_PASTE during the paste step.
     */
    private suspend fun startAndComplete(
        currentMessage: String = "Fix the bug",
        globalContextFiles: List<String> = emptyList(),
        gatheredFiles: Map<String, String> = emptyMap()
    ) {
        stubProjectContext()
        stubGatherFiles(gatheredFiles)
        service.startTask(
            sessionId = SESSION_ID,
            currentMessage = currentMessage,
            globalContextFiles = globalContextFiles
        )
        // Simulate AWAITING_PASTE so handlePastedResponse accepts the ResponsePasted transition
        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.AWAITING_PASTE
        every { clipboardPort.parseResponse(any()) } returns simpleResponse()
        service.handlePastedResponse(sessionId = SESSION_ID, rawText = "{\"message\": \"ok\"}")
        // After paste processing, status transitions to SESSION_ACTIVE
        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.SESSION_ACTIVE
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

        val req = capturedRequest.captured
        assertEquals(
            emptyList<String>(), req.previouslyGatheredPaths,
            "Token-saving default: previouslyGatheredPaths must be empty when addHistory=false"
        )
    }

    @Test
    fun `default - freshFiles contains only files from current turn`() = runBlocking {
        startAndComplete(
            globalContextFiles = listOf("src/Foo.kt"),
            gatheredFiles = mapOf("src/Foo.kt" to "foo-content")
        )

        stubGatherFiles(mapOf("src/New.kt" to "new-content"))
        service.continueDialog(
            sessionId = SESSION_ID,
            message = "also need New",
            globalContextFiles = listOf("src/New.kt"),
            addHistory = false
        )

        val req = capturedRequest.captured
        assertEquals(mapOf("src/New.kt" to "new-content"), req.freshFiles)
        assertEquals(emptyList<String>(), req.previouslyGatheredPaths)
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

        val req = capturedRequest.captured
        assertTrue(
            req.previouslyGatheredPaths.containsAll(listOf("src/Foo.kt", "src/Bar.kt")),
            "addHistory=true must populate previouslyGatheredPaths with all gathered paths"
        )
        assertEquals(emptyMap<String, String>(), req.freshFiles)
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
        val responseWithFiles = ClipboardResponse(
            message = "need Bar",
            requestedFiles = listOf("src/Bar.kt")
        )
        stubGatherFiles(mapOf("src/Bar.kt" to "bar"))
        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.AWAITING_PASTE
        every { clipboardPort.parseResponse(any()) } returns responseWithFiles
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
    fun `addHistory toggles per-message — false reverts to empty paths`() = runBlocking {
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
        assertEquals(ClipboardPhase.PLANNING, (result as ClipboardStepResult.WaitingForResponse).phase)
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

    // ==================== continueDialog basics ====================

    @Test
    fun `continueDialog without active session returns Error`() = runBlocking {
        val result = service.continueDialog(sessionId = SESSION_ID, message = "next step")

        assertInstanceOf(ClipboardStepResult.Error::class.java, result)
        assertTrue((result as ClipboardStepResult.Error).message.contains("No active clipboard session"))
    }

    @Test
    fun `continueDialog appends message to dialog history`() = runBlocking {
        startAndComplete()
        stubGatherFiles(emptyMap())

        service.continueDialog(sessionId = SESSION_ID, message = "Please also fix Bar")

        assertTrue(
            capturedRequest.captured.chatHistory.any { it.role == "user" && it.content == "Please also fix Bar" }
        )
    }

    // ==================== handlePastedResponse ====================

    @Test
    fun `handlePastedResponse without active session returns Error`(): Unit = runBlocking {
        // AWAITING_PASTE status alone is not enough — in-memory sessionState must also be set
        // (sessionState is null here because startTask was never called)
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
    fun `handlePastedResponse with requestedFiles triggers another WaitingForResponse`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(sessionId = SESSION_ID, currentMessage = "Task")
        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.AWAITING_PASTE

        stubGatherFiles(mapOf("src/Foo.kt" to "foo-content"))
        every { clipboardPort.parseResponse(any()) } returns ClipboardResponse(
            message = "need Foo",
            requestedFiles = listOf("src/Foo.kt")
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
                ClipboardResponse(message = "Done", commitMessage = "feat: add X")

        val result = service.handlePastedResponse(sessionId = SESSION_ID, rawText = "{...}")

        assertEquals("feat: add X", (result as ClipboardStepResult.Completed).commitMessage)
    }

    // ==================== Session lifecycle ====================

    @Test
    fun `session is inactive initially - continueDialog returns Error before startTask`() = runBlocking {
        // No startTask called — in-memory sessionState is null
        val result = service.continueDialog(sessionId = SESSION_ID, message = "premature")

        assertInstanceOf(ClipboardStepResult.Error::class.java, result)
        assertTrue((result as ClipboardStepResult.Error).message.contains("No active clipboard session"))
    }

    @Test
    fun `after startTask - continueDialog succeeds (session is active)`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(sessionId = SESSION_ID, currentMessage = "Task")
        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.SESSION_ACTIVE

        stubGatherFiles(emptyMap())
        val result = service.continueDialog(sessionId = SESSION_ID, message = "follow-up")

        // Session is active — continueDialog returns WaitingForResponse, not Error
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

        // Simulate manager tracking the JsonCopied transition
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

        // Simulate manager tracking the ResponsePasted transition
        every { sessionManager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.SESSION_ACTIVE

        assertEquals(ClipboardSessionStatus.SESSION_ACTIVE, service.status(SESSION_ID))
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

        // Files were gathered — request phase must be CHAT, not PLANNING
        assertEquals(ClipboardPhase.CHAT, capturedRequest.captured.phase)
    }
}
