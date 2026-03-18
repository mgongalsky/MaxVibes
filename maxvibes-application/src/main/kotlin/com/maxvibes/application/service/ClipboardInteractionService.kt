package com.maxvibes.application.service

import com.maxvibes.application.port.output.*
import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.context.ProjectContext
import com.maxvibes.domain.model.interaction.*
import com.maxvibes.domain.model.modification.InsertPosition
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.shared.result.Result
import com.maxvibes.application.port.output.LoggerPort
import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.domain.model.chat.MessageRole

/**
 * Application-layer service that orchestrates the clipboard-mode LLM dialog.
 *
 * Builds JSON requests, copies them to the system clipboard, parses pasted LLM responses,
 * and applies resulting code modifications via [codeRepository].
 *
 * State transitions are delegated to [ClipboardSessionManager].
 *
 * Public API surface:
 * - [handleUserInput] — unified entry point; routes by session status.
 * - [startTask] / [continueDialog] / [handlePastedResponse] — explicit stage calls.
 * - [status] — current [ClipboardSessionStatus] for a session.
 * - [reset] — clears in-memory state and sets session back to IDLE.
 * - [redoLastRequest] — re-generates JSON for any session, even after switching chats.
 *
 * @param contextProvider        Provides project file tree and file content.
 * @param clipboardPort          Copies requests to and parses responses from the system clipboard.
 * @param codeRepository         Applies PSI-level code modifications.
 * @param notificationPort       Displays progress/success/warning notifications in the IDE.
 * @param promptPort             Supplies system prompt templates; falls back to [PromptTemplates.EMPTY].
 * @param logger                 Optional logger; pass null in unit tests to suppress output.
 * @param sessionManager         State-machine manager; wired in by MaxVibesService DI.
 * @param chatSessionRepository  Read/write access to persisted chat sessions.
 *   Used to save [requestedFiles] into domain messages and to read session state
 *   for [redoLastRequest] Scenario B (workspace belongs to a different session).
 */
class ClipboardInteractionService(
    private val contextProvider: ProjectContextPort,
    private val clipboardPort: ClipboardPort,
    private val codeRepository: CodeRepository,
    private val notificationPort: NotificationPort,
    private val promptPort: PromptPort? = null,
    private val logger: LoggerPort? = null,
    private val sessionManager: ClipboardSessionManager,
    private val chatSessionRepository: ChatSessionRepository
) {
    /** In-memory workspace: messages, gathered files, prompts, project context. */
    private var sessionState: ClipboardSessionState? = null

    /** ID of the session that owns the current [sessionState]. Guards against cross-session misuse. */
    private var sessionStateOwner: String? = null

    // ==================== Status Routing ====================

    /**
     * Returns the current clipboard status for the given session.
     * Delegates directly to [ClipboardSessionManager.statusFor].
     */
    private fun currentStatus(sessionId: String): ClipboardSessionStatus =
        sessionManager.statusFor(sessionId)

    // ==================== Public API ====================

    /**
     * Unified entry point for clipboard-mode UI interactions.
     *
     * Routes to [startTask], [continueDialog], or [handlePastedResponse] based on the
     * current session status, so UI code does not need to track internal states.
     */
    suspend fun handleUserInput(
        sessionId: String,
        userInput: String,
        history: List<ChatMessageDTO> = emptyList(),
        attachedContext: String? = null,
        planOnly: Boolean = false,
        ideErrors: String? = null,
        globalContextFiles: List<String> = emptyList(),
        addHistory: Boolean = false
    ): ClipboardStepResult = when (currentStatus(sessionId)) {
        ClipboardSessionStatus.AWAITING_PASTE -> handlePastedResponse(sessionId, userInput)
        ClipboardSessionStatus.SESSION_ACTIVE -> continueDialog(
            sessionId = sessionId,
            message = userInput,
            attachedContext = attachedContext,
            planOnly = planOnly,
            ideErrors = ideErrors,
            globalContextFiles = globalContextFiles,
            addHistory = addHistory
        )

        ClipboardSessionStatus.IDLE -> startTask(
            sessionId = sessionId,
            currentMessage = userInput,
            history = history,
            attachedContext = attachedContext,
            planOnly = planOnly,
            ideErrors = ideErrors,
            globalContextFiles = globalContextFiles,
            addHistory = addHistory
        )
    }

    /**
     * Starts a new clipboard task session.
     *
     * Gathers global context files, initialises session state, and copies the first
     * JSON request to the clipboard for the user to paste into an external LLM.
     */
    suspend fun startTask(
        sessionId: String,
        currentMessage: String,
        history: List<ChatMessageDTO> = emptyList(),
        attachedContext: String? = null,
        planOnly: Boolean = false,
        ideErrors: String? = null,
        globalContextFiles: List<String> = emptyList(),
        addHistory: Boolean = false
    ): ClipboardStepResult {
        log("Starting clipboard session: planOnly=$planOnly, addHistory=$addHistory")

        // Transition IDLE -> SESSION_ACTIVE
        sessionManager.transition(sessionId, ClipboardEvent.StartSession)

        notificationPort.showProgress("Gathering project context...", 0.1)
        val projectContextResult = contextProvider.getProjectContext()
        if (projectContextResult is Result.Failure) {
            return error("Failed to get project context: ${projectContextResult.error.message}")
        }
        val projectContext = (projectContextResult as Result.Success).value
        val prompts = promptPort?.getPrompts() ?: PromptTemplates.EMPTY

        log("Project: ${projectContext.name}, files in tree: ${projectContext.fileTree.totalFiles}")

        // Initialise in-memory workspace for this dialog
        sessionState = ClipboardSessionState(
            currentMessage = currentMessage,
            projectContext = projectContext,
            dialogHistory = history.toMutableList(),
            prompts = prompts,
            allGatheredFiles = mutableMapOf(),
            attachedContext = attachedContext,
            ideErrors = ideErrors,
            planOnly = planOnly
        )
        sessionStateOwner = sessionId

        addToHistory(ChatRole.USER, currentMessage)
        val freshFiles = gatherRequestedFiles(globalContextFiles) ?: emptyMap()
        return generateAndCopyJson(
            sessionId = sessionId,
            freshFiles = freshFiles,
            isFirstMessage = true,
            addHistory = addHistory
        )
    }

    /**
     * Continues an existing clipboard dialog session with a new user message.
     */
    suspend fun continueDialog(
        sessionId: String,
        message: String,
        attachedContext: String? = null,
        planOnly: Boolean? = null,
        ideErrors: String? = null,
        globalContextFiles: List<String> = emptyList(),
        addHistory: Boolean = false
    ): ClipboardStepResult {
        val state = sessionState
            ?: return error("No active clipboard session. Start a new task first.")

        log("Continuing dialog: addHistory=$addHistory")

        sessionState = state.copy(
            attachedContext = attachedContext ?: state.attachedContext,
            ideErrors = ideErrors ?: state.ideErrors,
            planOnly = planOnly ?: state.planOnly
        )

        addToHistory(ChatRole.USER, message)

        // In minimal mode the LLM already has globalContextFiles — re-sending them wastes tokens
        val filesToGather = if (addHistory) globalContextFiles else emptyList()
        val freshFiles = gatherRequestedFiles(filesToGather) ?: emptyMap()
        return generateAndCopyJson(
            sessionId = sessionId,
            freshFiles = freshFiles,
            isFirstMessage = false,
            addHistory = addHistory
        )
    }

    /**
     * Handles a raw LLM response pasted by the user.
     *
     * Wraps [handlePastedResponseInternal] in a top-level exception handler so unexpected
     * parse errors are surfaced as [ClipboardStepResult.Error] rather than crashing.
     */
    suspend fun handlePastedResponse(sessionId: String, rawText: String): ClipboardStepResult {
        return try {
            handlePastedResponseInternal(sessionId, rawText)
        } catch (e: Exception) {
            val msg = "Unexpected error processing response: ${e.javaClass.simpleName}: ${e.message}"
            println("[MaxVibes Clipboard] FATAL: $msg")
            logger?.error("Clipboard", msg, e)
            ClipboardStepResult.Error(msg)
        }
    }

    private suspend fun handlePastedResponseInternal(sessionId: String, rawText: String): ClipboardStepResult {
        // Guard: transition check BEFORE touching in-memory state
        val transitioned = sessionManager.transition(sessionId, ClipboardEvent.ResponsePasted)
        if (!transitioned) {
            return error("Cannot accept response paste: session is not in AWAITING_PASTE state.")
        }

        val state = sessionState
            ?: return error("No active clipboard session. Start a new task first.")

        log("Parsing pasted response (${rawText.length} chars)...")

        val outputTokens = rawText.length / 4
        val inputTokens = state.lastInputTokens

        val response = try {
            clipboardPort.parseResponse(rawText)
        } catch (e: Exception) {
            log("ERROR: Exception during JSON parse: ${e.message}")
            return error("Failed to parse response JSON: ${e.message}")
        }

        if (response == null) {
            log("ERROR: Failed to parse response — null returned")
            return error("Failed to parse LLM response. Make sure you pasted complete raw JSON without markdown code blocks.")
        }

        val reasoningDisplay = response.reasoning?.take(40) ?: "none"
        log(
            "Parsed: message=${response.message.take(50)}, " +
                    "requestedFiles=${response.requestedFiles.size}, " +
                    "modifications=${response.modifications.size}, " +
                    "reasoning=$reasoningDisplay"
        )

        if (response.message.isNotBlank()) {
            addToHistory(ChatRole.ASSISTANT, response.message)
        }

        // Persist requested file paths into the last ASSISTANT message in the domain.
        // Required for redoLastRequest Scenario B — when the in-memory workspace
        // belongs to a different session after the user switches chats.
        if (response.requestedFiles.isNotEmpty()) {
            persistRequestedFilesIntoDomain(sessionId, response.requestedFiles)
        }

        return processUnifiedResponse(sessionId, response, inputTokens, outputTokens)
    }

    /**
     * Saves [requestedFiles] from the LLM response into the last ASSISTANT message
     * of the domain session. No-op if session not found or no ASSISTANT message exists.
     */
    private fun persistRequestedFilesIntoDomain(sessionId: String, requestedFiles: List<String>) {
        val session = chatSessionRepository.getSessionById(sessionId) ?: return
        val messages = session.messages.toMutableList()
        val lastAssistantIdx = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastAssistantIdx < 0) return
        messages[lastAssistantIdx] = messages[lastAssistantIdx].copy(requestedFiles = requestedFiles)
        chatSessionRepository.saveSession(session.copy(messages = messages))
        log("Persisted ${requestedFiles.size} requestedFiles into domain message for session $sessionId")
    }

    /**
     * Returns the current [ClipboardSessionStatus] for the given session.
     */
    fun status(sessionId: String): ClipboardSessionStatus = currentStatus(sessionId)

    /**
     * Resets the clipboard session: clears in-memory workspace and transitions the session to IDLE.
     */
    fun reset(sessionId: String) {
        log("Session reset (sessionId=$sessionId)")
        sessionState = null
        sessionStateOwner = null
        sessionManager.transition(sessionId, ClipboardEvent.Reset)
    }

    // ==================== Core Logic ====================

    private suspend fun processUnifiedResponse(
        sessionId: String,
        response: ClipboardResponse,
        inputTokens: Int = 0,
        outputTokens: Int = 0
    ): ClipboardStepResult {
        val state = sessionState ?: return error("No active session")

        val hasFiles = response.requestedFiles.isNotEmpty()
        val hasMods = response.modifications.isNotEmpty()
        val hasMessage = response.message.isNotBlank()

        log("Processing: hasFiles=$hasFiles, hasMods=$hasMods, hasMessage=$hasMessage")

        val modResults = if (hasMods) applyModifications(response.modifications)
        else emptyList<ModificationResult>()

        if (hasFiles) {
            val gatheredFilesMap = gatherRequestedFiles(response.requestedFiles)
            if (gatheredFilesMap == null) {
                return buildCompletedResult(
                    response = response,
                    modResults = modResults,
                    extraMessage = "Failed to gather some requested files.",
                    inputTokens = inputTokens,
                    outputTokens = outputTokens
                )
            }
            val assistantMsg = response.message.trim().takeIf { it.isNotBlank() }
            val reasoningStr = response.reasoning?.takeIf { it.isNotBlank() }
            return generateAndCopyJson(
                sessionId = sessionId,
                freshFiles = gatheredFilesMap,
                isFirstMessage = false,
                assistantMessage = assistantMsg,
                llmReasoning = reasoningStr
            )
        }

        return buildCompletedResult(
            response = response,
            modResults = modResults,
            inputTokens = inputTokens,
            outputTokens = outputTokens
        )
    }

    /**
     * Builds a [ClipboardRequest] via [ClipboardRequestBuilder], copies it to the clipboard,
     * and returns [ClipboardStepResult.WaitingForResponse].
     */
    private fun generateAndCopyJson(
        sessionId: String,
        freshFiles: Map<String, String>,
        isFirstMessage: Boolean,
        assistantMessage: String? = null,
        llmReasoning: String? = null,
        addHistory: Boolean = false
    ): ClipboardStepResult {
        val state = sessionState ?: return error("No active session")

        val request = ClipboardRequestBuilder.build(
            state = state,
            freshFiles = freshFiles,
            isFirstMessage = isFirstMessage,
            addHistory = addHistory,
            planOnlySuffix = PLAN_ONLY_SUFFIX
        )

        val copied = clipboardPort.copyRequestToClipboard(request)
        val copyStatus = if (copied) "copied to clipboard" else "generated (copy manually)"

        val totalTokens = estimateTokens(request)
        state.lastInputTokens = totalTokens

        log("JSON ready: $copyStatus, ~$totalTokens tokens")

        sessionManager.transition(sessionId, ClipboardEvent.JsonCopied)

        return ClipboardStepResult.WaitingForResponse(
            phase = request.phase,
            statusMessage = "JSON $copyStatus. Paste into Claude/ChatGPT, then paste the response back here.",
            assistantMessage = assistantMessage,
            jsonRequest = request,
            estimatedInputTokens = totalTokens,
            llmReasoning = llmReasoning,
            freshFileNames = freshFiles.keys.map { it.substringAfterLast('/') },
            previouslyGatheredCount = request.previouslyGatheredPaths.size
        )
    }

    /**
     * Re-generates and copies the clipboard JSON for the given session.
     *
     * Does NOT add messages to history, does NOT trigger state machine transitions.
     * Simply rebuilds the JSON payload and copies it to the clipboard.
     *
     * Two scenarios:
     * - **A**: In-memory workspace belongs to this session → reuse it, call [generateAndCopyJson] directly.
     * - **B**: Workspace belongs to another session → rebuild minimal workspace from domain
     *   (fresh projectContext + last user message + last requestedFiles), then call [generateAndCopyJson].
     *
     * Returns [ClipboardStepResult.Error] if:
     * - session not found (Scenario B)
     * - session status is IDLE (Generate was never called)
     * - session has no USER messages
     * - project context cannot be loaded (Scenario B)
     */
    suspend fun redoLastRequest(
        sessionId: String,
        globalContextFiles: List<String>
    ): ClipboardStepResult {

        // --- Scenario A: workspace already belongs to this session ---
        if (sessionStateOwner == sessionId && sessionState != null) {
            log("Redo scenario A: reusing existing workspace for session $sessionId")
            val freshFiles = gatherRequestedFiles(globalContextFiles) ?: emptyMap()
            return generateAndCopyJson(
                sessionId = sessionId,
                freshFiles = freshFiles,
                isFirstMessage = false
            )
        }

        // --- Scenario B: workspace belongs to another session, rebuild from domain ---
        log("Redo scenario B: rebuilding workspace from domain for session $sessionId")

        val session = chatSessionRepository.getSessionById(sessionId)
            ?: return error("Session not found: $sessionId")

        if (session.clipboardStatus == ClipboardSessionStatus.IDLE) {
            return error("No active clipboard session for this chat.")
        }

        val lastUserMessage = session.messages
            .lastOrNull { it.role == MessageRole.USER }
            ?.content
            ?: return error("No user message found in session $sessionId")

        // File paths from the last LLM response only (not the entire accumulated list)
        val lastRequestedFiles = session.messages
            .lastOrNull { it.role == MessageRole.ASSISTANT && it.requestedFiles.isNotEmpty() }
            ?.requestedFiles
            ?: emptyList()

        // Fresh project context is required to build the file tree in the JSON
        val projectContextResult = contextProvider.getProjectContext()
        if (projectContextResult is Result.Failure) {
            return error("Failed to get project context: ${projectContextResult.error.message}")
        }
        val projectContext = (projectContextResult as Result.Success).value
        val prompts = promptPort?.getPrompts() ?: PromptTemplates.EMPTY

        // Build minimal workspace with just enough data for generateAndCopyJson
        sessionState = ClipboardSessionState(
            currentMessage = lastUserMessage,
            projectContext = projectContext,
            dialogHistory = session.messages
                .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
                .map {
                    ChatMessageDTO(
                        role = if (it.role == MessageRole.USER) ChatRole.USER else ChatRole.ASSISTANT,
                        content = it.content
                    )
                }
                .toMutableList(),
            prompts = prompts,
            allGatheredFiles = mutableMapOf(),
            planOnly = false
        )
        sessionStateOwner = sessionId

        val filesToGather = (globalContextFiles + lastRequestedFiles).distinct()
        val freshFiles = gatherRequestedFiles(filesToGather) ?: emptyMap()
        return generateAndCopyJson(
            sessionId = sessionId,
            freshFiles = freshFiles,
            isFirstMessage = false
        )
    }

    // ==================== File Gathering ====================

    private suspend fun gatherRequestedFiles(requestedPaths: List<String>): Map<String, String>? {
        val state = sessionState ?: return null

        val newPaths = requestedPaths.filter { it !in state.allGatheredFiles }
        val alreadyGathered = requestedPaths.filter { it in state.allGatheredFiles }

        if (alreadyGathered.isNotEmpty()) {
            log("Already gathered (skipping): ${alreadyGathered.size} files")
        }

        if (newPaths.isEmpty()) {
            log("All requested files already gathered, re-sending existing")
            return requestedPaths.associateWith { state.allGatheredFiles[it] ?: "" }
        }

        log("Gathering ${newPaths.size} new files...")
        notificationPort.showProgress("Gathering ${newPaths.size} files...", 0.4)

        val gatherResult = contextProvider.gatherFiles(newPaths)
        if (gatherResult is Result.Failure) {
            log("ERROR: Failed to gather files: ${gatherResult.error.message}")
            return null
        }
        val gathered = (gatherResult as Result.Success).value
        state.allGatheredFiles.putAll(gathered.files)
        log("Gathered ${gathered.files.size} files, total cached: ${state.allGatheredFiles.size}")
        return gathered.files
    }

    // ==================== Modifications ====================

    private suspend fun applyModifications(clipboardMods: List<ClipboardModification>): List<ModificationResult> {
        val modifications = clipboardMods.mapNotNull { convertModification(it) }
        if (modifications.isEmpty()) return emptyList()

        log("Applying ${modifications.size} modifications...")
        notificationPort.showProgress("Applying ${modifications.size} changes...", 0.8)

        val results = codeRepository.applyModifications(modifications)
        val successCount = results.count { it is ModificationResult.Success }
        val failCount = results.size - successCount

        log("Modifications: $successCount success, $failCount failed")
        if (failCount > 0) notificationPort.showWarning("Applied $successCount changes, $failCount failed")
        else if (successCount > 0) notificationPort.showSuccess("Applied $successCount changes")
        return results
    }

    // ==================== Result Building ====================

    private fun buildCompletedResult(
        response: ClipboardResponse,
        modResults: List<ModificationResult>,
        extraMessage: String = "",
        inputTokens: Int = 0,
        outputTokens: Int = 0
    ): ClipboardStepResult {
        val successCount = modResults.count { it is ModificationResult.Success }
        val failCount = modResults.size - successCount

        val messageText = buildString {
            if (response.message.isNotBlank()) append(response.message)
            if (extraMessage.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(extraMessage)
            }
            if (isEmpty()) append("Done.")
        }

        if (modResults.isNotEmpty()) notificationPort.showSuccess("Done. Session active — you can continue the dialog.")
        log("Completed: mods=$successCount ok/$failCount fail.")

        return ClipboardStepResult.Completed(
            message = messageText.trim(),
            modifications = modResults,
            success = failCount == 0,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            llmReasoning = response.reasoning?.takeIf { it.isNotBlank() },
            commitMessage = response.commitMessage?.takeIf { it.isNotBlank() }
        )
    }

    // ==================== Helpers ====================

    private fun addToHistory(role: ChatRole, content: String) {
        val state = sessionState ?: return
        state.dialogHistory.add(ChatMessageDTO(role = role, content = content))
    }

    private fun estimateTokens(request: ClipboardRequest): Int {
        val textSize = request.systemInstruction.length +
                request.fileTree.length +
                request.freshFiles.values.sumOf { it.length } +
                request.chatHistory.sumOf { it.content.length } +
                (request.attachedContext?.length ?: 0)
        return textSize / 4
    }

    private fun log(message: String) {
        println("[MaxVibes Clipboard] $message")
        logger?.info("Clipboard", message)
    }

    private fun error(message: String): ClipboardStepResult.Error {
        println("[MaxVibes Clipboard] ERROR: $message")
        logger?.error("Clipboard", message)
        return ClipboardStepResult.Error(message)
    }

    private fun convertModification(mod: ClipboardModification): Modification? {
        if (mod.type.isBlank() || mod.path.isBlank()) return null
        val elementPath = ElementPath(mod.path)
        val elementKind = try {
            ElementKind.valueOf(mod.elementKind.uppercase())
        } catch (_: Exception) {
            ElementKind.FILE
        }
        val position = try {
            InsertPosition.valueOf(mod.position.uppercase())
        } catch (_: Exception) {
            InsertPosition.LAST_CHILD
        }

        return when (mod.type.uppercase()) {
            "CREATE_FILE" -> Modification.CreateFile(targetPath = elementPath, content = mod.content)
            "REPLACE_FILE" -> Modification.ReplaceFile(targetPath = elementPath, newContent = mod.content)
            "DELETE_FILE" -> Modification.DeleteFile(targetPath = elementPath)
            "CREATE_ELEMENT" -> Modification.CreateElement(
                targetPath = elementPath, elementKind = elementKind,
                content = mod.content, position = position
            )

            "REPLACE_ELEMENT" -> Modification.ReplaceElement(targetPath = elementPath, newContent = mod.content)
            "DELETE_ELEMENT" -> Modification.DeleteElement(targetPath = elementPath)
            "ADD_IMPORT" -> {
                val fqn = mod.importPath.ifBlank { mod.content.removePrefix("import ").trim() }
                if (fqn.isBlank()) null else Modification.AddImport(targetPath = elementPath, importPath = fqn)
            }

            "REMOVE_IMPORT" -> {
                val fqn = mod.importPath.ifBlank { mod.content.removePrefix("import ").trim() }
                if (fqn.isBlank()) null else Modification.RemoveImport(targetPath = elementPath, importPath = fqn)
            }

            else -> null
        }
    }

    companion object {
        private val PLAN_ONLY_SUFFIX = "\n\n" +
                "## PLAN-ONLY MODE — DISCUSSION REQUIRED\n\n" +
                "DO NOT generate any code changes in the modifications array.\n" +
                "Keep modifications empty.\n" +
                "Your goal is to DISCUSS the plan with the user before any code is written.\n\n" +
                "Instead of code, you must:\n" +
                "1. Briefly explain what you understand from the task\n" +
                "2. List which files you plan to touch and what changes you'll make in each\n" +
                "3. Mention any architectural decisions or trade-offs\n" +
                "4. Ask the user to confirm or suggest corrections\n\n" +
                "Always output the JSON with empty modifications and put your discussion in message."
    }
}

// ==================== Results ====================

sealed class ClipboardStepResult {
    data class WaitingForResponse(
        val phase: ClipboardPhase,
        val statusMessage: String,
        val assistantMessage: String? = null,
        val jsonRequest: ClipboardRequest,
        val estimatedInputTokens: Int = 0,
        val llmReasoning: String? = null,
        val freshFileNames: List<String> = emptyList(),
        val previouslyGatheredCount: Int = 0
    ) : ClipboardStepResult()

    data class Completed(
        val message: String,
        val modifications: List<ModificationResult>,
        val success: Boolean,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val llmReasoning: String? = null,
        val commitMessage: String? = null
    ) : ClipboardStepResult()

    data class Error(val message: String) : ClipboardStepResult()
}

// ==================== Internal State ====================

