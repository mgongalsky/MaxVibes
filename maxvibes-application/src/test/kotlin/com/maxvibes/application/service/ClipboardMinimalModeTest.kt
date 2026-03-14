package com.maxvibes.application.service

import com.maxvibes.application.port.output.*
import com.maxvibes.domain.model.code.CodeElement
import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.context.*
import com.maxvibes.domain.model.interaction.*
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.shared.result.Result
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.maxvibes.domain.model.modification.ModificationError

/**
 * Verifies the minimal-mode payload policy introduced in [ClipboardInteractionService].
 *
 * **Rule under test**: when [ClipboardInteractionService.continueDialog] is called with
 * `addHistory = false`, the produced [ClipboardRequest] must NOT contain heavy context
 * fields (`systemInstruction`, `fileTree`, `chatHistory`, `attachedContext`, `planOnly`)
 * because the LLM already has all that information in its current chat window.
 *
 * When `addHistory = true`, or on the very first [ClipboardInteractionService.startTask]
 * call, the full context must be present.
 *
 * These tests run in the `maxvibes-application` module without any IntelliJ Platform SDK.
 */
class ClipboardMinimalModeTest {

    // ── Fake ports ────────────────────────────────────────────────────

    /** Captures every [ClipboardRequest] passed to the clipboard for inspection. */
    private val capturedRequests = mutableListOf<ClipboardRequest>()

    private val clipboardPort = object : ClipboardPort {
        override fun copyRequestToClipboard(request: ClipboardRequest): Boolean {
            capturedRequests.add(request)
            return true
        }

        override fun parseResponse(rawText: String): ClipboardResponse? = null
    }

    /** Returns a minimal but valid [ProjectContext] with a non-blank file tree. */
    private val projectContextPort = object : ProjectContextPort {
        override suspend fun getProjectContext(): Result<ProjectContext, ContextError> {
            val root = FileNode(name = "TestProject", path = "/", isDirectory = true)
            val fileTree = FileTree(root = root, totalFiles = 2, totalDirectories = 1)
            return Result.Success(
                ProjectContext(name = "TestProject", rootPath = "/test", fileTree = fileTree)
            )
        }

        override suspend fun getFileTree(
            maxDepth: Int,
            excludePatterns: List<String>
        ): Result<FileTree, ContextError> {
            val root = FileNode("root", "/", isDirectory = true)
            return Result.Success(FileTree(root, 0, 0))
        }

        /** Returns dummy file contents so allGatheredFiles gets populated. */
        override suspend fun gatherFiles(
            paths: List<String>,
            maxTotalSize: Long
        ): Result<GatheredContext, ContextError> {
            val contents = paths.associateWith { path -> "// content of $path" }
            return Result.Success(GatheredContext(files = contents, totalTokensEstimate = 10))
        }

        override suspend fun findDescriptionFiles(): Result<Map<String, String>, ContextError> =
            Result.Success(emptyMap())
    }

    private val notificationPort = object : NotificationPort {
        override fun showProgress(message: String, fraction: Double?) {}
        override fun showSuccess(message: String) {}
        override fun showError(message: String) {}
        override fun showWarning(message: String) {}
        override suspend fun askConfirmation(title: String, message: String) = true
    }

    private val codeRepository = object : CodeRepository {
        override suspend fun getFileContent(path: ElementPath): Result<String, CodeRepositoryError> =
            Result.Failure(CodeRepositoryError.NotFound(path.toString()))

        override suspend fun getElement(path: ElementPath): Result<CodeElement, CodeRepositoryError> =
            Result.Failure(CodeRepositoryError.NotFound(path.toString()))

        override suspend fun findElements(
            basePath: ElementPath,
            kinds: Set<ElementKind>?,
            namePattern: Regex?
        ): Result<List<CodeElement>, CodeRepositoryError> = Result.Success(emptyList())

        override suspend fun applyModification(modification: Modification): ModificationResult =
            // Return Failure — test fakes never exercise real modifications.
            ModificationResult.Failure(
                modification = modification,
                error = ModificationError.InvalidOperation("test fake")
            )

        override suspend fun applyModifications(
            modifications: List<Modification>
        ): List<ModificationResult> = emptyList()

        override suspend fun exists(path: ElementPath): Boolean = false
        override suspend fun validateSyntax(content: String): Result<Unit, CodeRepositoryError> =
            Result.Success(Unit)
    }

    /** Returns non-blank prompts so full-context assertions can detect them. */
    private val promptPort = object : PromptPort {
        override fun getPrompts() = PromptTemplates(
            chatSystem = "CHAT_SYSTEM_PROMPT",
            planningSystem = "PLANNING_SYSTEM_PROMPT"
        )

        override fun hasCustomPrompts() = false
        override fun openOrCreatePrompts() {}
    }

    private lateinit var service: ClipboardInteractionService

    @BeforeEach
    fun setup() {
        capturedRequests.clear()
        service = ClipboardInteractionService(
            contextProvider = projectContextPort,
            clipboardPort = clipboardPort,
            codeRepository = codeRepository,
            notificationPort = notificationPort,
            promptPort = promptPort
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * Starts a session with a global context file so [allGatheredFiles] is populated.
     * After this call the session is active and [isFirstMessage] will be `false` for
     * the next [continueDialog] invocation.
     */
    private fun startSessionWithContextFile(): ClipboardRequest {
        val result = runBlocking {
            service.startTask(
                task = "initial task",
                globalContextFiles = listOf("docs/README.md")
            )
        }
        return (result as ClipboardStepResult.WaitingForResponse).jsonRequest
    }

    // ── Tests: startTask (first message) ─────────────────────────────

    @Test
    fun `startTask always sends full context regardless of addHistory`() {
        val req = startSessionWithContextFile()

        // First message must always carry the full context so the LLM knows the project.
        assertTrue(
            req.systemInstruction.isNotBlank(),
            "startTask must include systemInstruction"
        )
        assertTrue(
            req.fileTree.isNotBlank(),
            "startTask must include fileTree"
        )
        assertTrue(
            req.task.isNotBlank(),
            "startTask must include a non-blank task"
        )
    }

    // ── Tests: continueDialog – minimal mode (addHistory = false) ─────

    @Test
    fun `continueDialog without addHistory produces minimal ClipboardRequest`() {
        startSessionWithContextFile()

        val result = runBlocking {
            service.continueDialog(
                message = "fix the bug",
                globalContextFiles = listOf("docs/README.md"),
                addHistory = false   // galochka off — LLM already has context in its chat
            )
        }

        assertTrue(
            result is ClipboardStepResult.WaitingForResponse,
            "expected WaitingForResponse but got $result"
        )
        val req = (result as ClipboardStepResult.WaitingForResponse).jsonRequest

        // These fields must be blank/empty so JsonClipboardProtocolCodec omits them from JSON.
        assertTrue(
            req.systemInstruction.isBlank(),
            "systemInstruction must be blank in minimal mode"
        )
        assertTrue(
            req.fileTree.isBlank(),
            "fileTree must be blank in minimal mode"
        )
        assertTrue(
            req.chatHistory.isEmpty(),
            "chatHistory must be empty in minimal mode"
        )
        assertNull(
            req.attachedContext,
            "attachedContext must be null in minimal mode"
        )
        assertFalse(
            req.planOnly,
            "planOnly must be false in minimal mode"
        )

        // Global context file must NOT leak into previouslyGatheredPaths when addHistory=false.
        assertFalse(
            req.previouslyGatheredPaths.contains("docs/README.md"),
            "global context file must not appear in previouslyGatheredPaths with addHistory=false"
        )
        assertTrue(
            req.previouslyGatheredPaths.isEmpty(),
            "previouslyGatheredPaths must be empty when addHistory=false"
        )

        // The user message must still be present.
        assertTrue(
            req.task.contains("fix the bug"),
            "task must contain the user's follow-up message"
        )
    }

    @Test
    fun `continueDialog minimal mode omits heavy fields across multiple turns`() {
        startSessionWithContextFile()

        // Second turn
        val result1 = runBlocking {
            service.continueDialog(message = "turn 2", addHistory = false)
        }
        // Third turn
        val result2 = runBlocking {
            service.continueDialog(message = "turn 3", addHistory = false)
        }

        listOf(result1, result2).forEach { result ->
            assertTrue(result is ClipboardStepResult.WaitingForResponse)
            val req = (result as ClipboardStepResult.WaitingForResponse).jsonRequest
            assertTrue(
                req.systemInstruction.isBlank(),
                "systemInstruction must always be blank in minimal mode"
            )
            assertTrue(
                req.fileTree.isBlank(),
                "fileTree must always be blank in minimal mode"
            )
            assertTrue(
                req.chatHistory.isEmpty(),
                "chatHistory must always be empty in minimal mode"
            )
        }
    }

    // ── Tests: continueDialog – full context mode (addHistory = true) ─

    @Test
    fun `continueDialog with addHistory sends full context`() {
        startSessionWithContextFile()

        val result = runBlocking {
            service.continueDialog(
                message = "fix the bug",
                globalContextFiles = listOf("docs/README.md"),
                addHistory = true   // galochka on — starting fresh LLM chat
            )
        }

        assertTrue(result is ClipboardStepResult.WaitingForResponse)
        val req = (result as ClipboardStepResult.WaitingForResponse).jsonRequest

        // Full context must be present.
        assertTrue(
            req.systemInstruction.isNotBlank(),
            "systemInstruction must be present when addHistory=true"
        )
        assertTrue(
            req.fileTree.isNotBlank(),
            "fileTree must be present when addHistory=true"
        )
        assertTrue(
            req.chatHistory.isNotEmpty(),
            "chatHistory must be present when addHistory=true"
        )
    }

    @Test
    fun `continueDialog with addHistory populates previouslyGatheredPaths`() {
        startSessionWithContextFile()  // gathers docs/README.md into allGatheredFiles

        val result = runBlocking {
            service.continueDialog(
                message = "continue with history",
                addHistory = true
            )
        }

        assertTrue(result is ClipboardStepResult.WaitingForResponse)
        val req = (result as ClipboardStepResult.WaitingForResponse).jsonRequest

        // Previously gathered file paths must be forwarded so LLM can re-request them.
        assertTrue(
            req.previouslyGatheredPaths.contains("docs/README.md"),
            "previouslyGatheredPaths must include files gathered in previous turns"
        )
    }

    // ── Tests: edge cases ─────────────────────────────────────────────

    @Test
    fun `continueDialog without active session returns Error`() {
        // No startTask called — no active session.
        val result = runBlocking {
            service.continueDialog(message = "orphan message", addHistory = false)
        }
        assertTrue(
            result is ClipboardStepResult.Error,
            "continueDialog without active session must return Error"
        )
    }

    @Test
    fun `ideErrors are always included even in minimal mode`() {
        startSessionWithContextFile()

        val result = runBlocking {
            service.continueDialog(
                message = "fix errors",
                ideErrors = "CompileError: unresolved reference 'foo'",
                addHistory = false
            )
        }

        assertTrue(result is ClipboardStepResult.WaitingForResponse)
        val req = (result as ClipboardStepResult.WaitingForResponse).jsonRequest

        // ideErrors are always relevant to the current message — must survive minimal mode.
        assertNotNull(req.ideErrors, "ideErrors must always be included, even in minimal mode")
        assertTrue(req.ideErrors!!.contains("unresolved reference"))

        // But heavy fields are still blank.
        assertTrue(req.systemInstruction.isBlank())
        assertTrue(req.fileTree.isBlank())
    }
}
