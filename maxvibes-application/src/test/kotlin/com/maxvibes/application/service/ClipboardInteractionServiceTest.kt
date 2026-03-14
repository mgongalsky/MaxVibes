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
 *   The LLM in an ongoing chat already knows its context — no need to waste tokens.
 * - addHistory=true ("Add History" checkbox): previously gathered file paths ARE included,
 *   so a fresh LLM chat can re-request whatever it needs without full re-upload.
 */
class ClipboardInteractionServiceTest {

    // ==================== Mocks ====================

    private val contextProvider = mockk<ProjectContextPort>()
    private val clipboardPort = mockk<ClipboardPort>()
    private val codeRepository = mockk<CodeRepository>(relaxed = true)
    private val notificationPort = mockk<NotificationPort>(relaxed = true)
    private val promptPort = mockk<PromptPort>()
    private val logger = mockk<LoggerPort>(relaxed = true)

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
            logger = logger
        )
        every { clipboardPort.copyRequestToClipboard(capture(capturedRequest)) } returns true
        every { promptPort.getPrompts() } returns PromptTemplates(
            planningSystem = "planning-prompt",
            chatSystem = "chat-prompt"
        )
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
     * After this the session is still active and NOT waiting for paste.
     */
    private suspend fun startAndComplete(
        task: String = "Fix the bug",
        globalContextFiles: List<String> = emptyList(),
        gatheredFiles: Map<String, String> = emptyMap()
    ) {
        stubProjectContext()
        stubGatherFiles(gatheredFiles)
        service.startTask(task = task, globalContextFiles = globalContextFiles)
        every { clipboardPort.parseResponse(any()) } returns simpleResponse()
        service.handlePastedResponse("{\"message\": \"ok\"}")
    }

    // ==================== Default behaviour (addHistory=false) ====================

    @Test
    fun `default - previouslyGatheredPaths is empty even when files were gathered`() = runBlocking {
        // Arrange: gather two files in turn 1.
        startAndComplete(
            globalContextFiles = listOf("src/Foo.kt", "src/Bar.kt"),
            gatheredFiles = mapOf("src/Foo.kt" to "foo", "src/Bar.kt" to "bar")
        )

        // Act: turn 2 with default addHistory=false.
        stubGatherFiles(emptyMap())
        service.continueDialog(message = "next", addHistory = false)

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
            message = "also need New",
            globalContextFiles = listOf("src/New.kt"),
            addHistory = false
        )

        val req = capturedRequest.captured
        // Only the new file appears — NOT the old Foo.kt
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
        service.continueDialog(message = "new chat", addHistory = true)

        val req = capturedRequest.captured
        assertTrue(
            req.previouslyGatheredPaths.containsAll(listOf("src/Foo.kt", "src/Bar.kt")),
            "addHistory=true must populate previouslyGatheredPaths with all gathered paths"
        )
        // Content is NOT duplicated into freshFiles
        assertEquals(emptyMap<String, String>(), req.freshFiles)
    }

    @Test
    fun `addHistory=true on first message - previouslyGatheredPaths is empty (nothing gathered yet)`() = runBlocking {
        // allGatheredFiles is empty on first message regardless of addHistory.
        stubProjectContext()
        stubGatherFiles(emptyMap())

        service.startTask(task = "Task", addHistory = true)

        assertEquals(emptyList<String>(), capturedRequest.captured.previouslyGatheredPaths)
    }

    @Test
    fun `addHistory=true includes files gathered across multiple turns`() = runBlocking {
        // Turn 1: gather Foo.kt
        startAndComplete(
            globalContextFiles = listOf("src/Foo.kt"),
            gatheredFiles = mapOf("src/Foo.kt" to "foo")
        )
        // Turn 2: gather Bar.kt (response requests it)
        val responseWithFiles = ClipboardResponse(
            message = "need Bar",
            requestedFiles = listOf("src/Bar.kt")
        )
        stubGatherFiles(mapOf("src/Bar.kt" to "bar"))
        every { clipboardPort.parseResponse(any()) } returns responseWithFiles
        service.handlePastedResponse("{...}")
        // Complete turn 2
        every { clipboardPort.parseResponse(any()) } returns simpleResponse()
        service.handlePastedResponse("{...}")

        // Turn 3 with addHistory=true — should see BOTH files
        stubGatherFiles(emptyMap())
        service.continueDialog(message = "new chat", addHistory = true)

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

        // Turn 2: addHistory=true → paths populated
        service.continueDialog(message = "with history", addHistory = true)
        assertTrue(capturedRequest.captured.previouslyGatheredPaths.contains("src/Foo.kt"))

        every { clipboardPort.parseResponse(any()) } returns simpleResponse()
        service.handlePastedResponse("{...}")

        // Turn 3: addHistory=false → paths empty again
        service.continueDialog(message = "without history", addHistory = false)
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

        val result = service.startTask(task = "Do X")

        assertInstanceOf(ClipboardStepResult.WaitingForResponse::class.java, result)
        assertEquals(ClipboardPhase.PLANNING, (result as ClipboardStepResult.WaitingForResponse).phase)
    }

    @Test
    fun `startTask with globalContextFiles includes gathered content in freshFiles`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(mapOf("src/Foo.kt" to "foo-content"))

        service.startTask(task = "Task", globalContextFiles = listOf("src/Foo.kt"))

        assertEquals(mapOf("src/Foo.kt" to "foo-content"), capturedRequest.captured.freshFiles)
    }

    @Test
    fun `startTask propagates project context failure as Error`() = runBlocking {
        coEvery { contextProvider.getProjectContext() } returns
                Result.Failure(ContextError.FileReadError("project", "network error"))

        val result = service.startTask(task = "Task")

        assertInstanceOf(ClipboardStepResult.Error::class.java, result)
    }

    @Test
    fun `startTask records task in dialog history`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())

        service.startTask(task = "My important task")

        assertTrue(
            capturedRequest.captured.chatHistory.any { it.role == "user" && it.content == "My important task" }
        )
    }

    // ==================== continueDialog basics ====================

    @Test
    fun `continueDialog without active session returns Error`() = runBlocking {
        val result = service.continueDialog(message = "next step")

        assertInstanceOf(ClipboardStepResult.Error::class.java, result)
        assertTrue((result as ClipboardStepResult.Error).message.contains("No active clipboard session"))
    }

    @Test
    fun `continueDialog appends message to dialog history`() = runBlocking {
        startAndComplete()
        stubGatherFiles(emptyMap())

        service.continueDialog(message = "Please also fix Bar")

        assertTrue(
            capturedRequest.captured.chatHistory.any { it.role == "user" && it.content == "Please also fix Bar" }
        )
    }

    // ==================== handlePastedResponse ====================

    @Test
    fun `handlePastedResponse without active session returns Error`() = runBlocking {
        assertInstanceOf(ClipboardStepResult.Error::class.java, service.handlePastedResponse("{}"))
    }

    @Test
    fun `handlePastedResponse when parseResponse returns null returns Error`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(task = "Task")

        every { clipboardPort.parseResponse(any()) } returns null

        assertInstanceOf(ClipboardStepResult.Error::class.java, service.handlePastedResponse("bad"))
    }

    @Test
    fun `handlePastedResponse with requestedFiles triggers another WaitingForResponse`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(task = "Task")

        stubGatherFiles(mapOf("src/Foo.kt" to "foo-content"))
        every { clipboardPort.parseResponse(any()) } returns ClipboardResponse(
            message = "need Foo",
            requestedFiles = listOf("src/Foo.kt")
        )

        val result = service.handlePastedResponse("{...}")

        assertInstanceOf(ClipboardStepResult.WaitingForResponse::class.java, result)
        assertEquals(mapOf("src/Foo.kt" to "foo-content"), capturedRequest.captured.freshFiles)
    }

    @Test
    fun `handlePastedResponse with no requestedFiles and no mods returns Completed`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(task = "Task")
        every { clipboardPort.parseResponse(any()) } returns simpleResponse("All done!")

        val result = service.handlePastedResponse("{...}")

        assertInstanceOf(ClipboardStepResult.Completed::class.java, result)
        assertTrue((result as ClipboardStepResult.Completed).message.contains("All done!"))
    }

    @Test
    fun `handlePastedResponse propagates commitMessage`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(task = "Task")
        every { clipboardPort.parseResponse(any()) } returns
                ClipboardResponse(message = "Done", commitMessage = "feat: add X")

        val result = service.handlePastedResponse("{...}")

        assertEquals("feat: add X", (result as ClipboardStepResult.Completed).commitMessage)
    }

    // ==================== Session lifecycle ====================

    @Test
    fun `hasActiveSession is false initially and true after startTask`() = runBlocking {
        assertFalse(service.hasActiveSession())
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(task = "Task")
        assertTrue(service.hasActiveSession())
    }

    @Test
    fun `reset clears session`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(task = "Task")
        service.reset()
        assertFalse(service.hasActiveSession())
        assertInstanceOf(ClipboardStepResult.Error::class.java, service.continueDialog(message = "after reset"))
    }

    @Test
    fun `isWaitingForResponse becomes false after handlePastedResponse`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(emptyMap())
        service.startTask(task = "Task")
        assertTrue(service.isWaitingForResponse())

        every { clipboardPort.parseResponse(any()) } returns simpleResponse()
        service.handlePastedResponse("{\"message\": \"done\"}")

        assertFalse(service.isWaitingForResponse())
    }

    @Test
    fun `getCurrentPhase returns CHAT after globalContextFiles are gathered`() = runBlocking {
        stubProjectContext()
        stubGatherFiles(mapOf("src/Foo.kt" to "content"))
        service.startTask(task = "Task", globalContextFiles = listOf("src/Foo.kt"))

        assertEquals(ClipboardPhase.CHAT, service.getCurrentPhase())
    }
}
