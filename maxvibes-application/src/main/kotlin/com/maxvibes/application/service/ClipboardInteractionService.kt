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

/**
 * Application-layer service that orchestrates the clipboard-mode LLM dialog.
 *
 * Builds JSON requests, copies them to the system clipboard, parses pasted LLM responses,
 * and applies resulting code modifications via [codeRepository].
 *
 * State transitions are delegated to [ClipboardSessionManager] when provided.
 * Until STEP 5 wires the manager, it defaults to null and all transitions are no-ops.
 *
 * @param contextProvider   Provides project file tree and file content.
 * @param clipboardPort     Copies requests to and parses responses from the system clipboard.
 * @param codeRepository    Applies PSI-level code modifications.
 * @param notificationPort  Displays progress/success/warning notifications in the IDE.
 * @param promptPort        Supplies system prompt templates; falls back to [PromptTemplates.EMPTY].
 * @param logger            Optional logger; pass null in unit tests to suppress output.
 * @param sessionManager    Optional state-machine manager; null until STEP 5 wires it in.
 */
class ClipboardInteractionService(
    private val contextProvider: ProjectContextPort,
    private val clipboardPort: ClipboardPort,
    private val codeRepository: CodeRepository,
    private val notificationPort: NotificationPort,
    private val promptPort: PromptPort? = null,
    private val logger: LoggerPort? = null,
    private val sessionManager: ClipboardSessionManager? = null
) {
    /** In-memory session state: messages, gathered files, prompts, etc. */
    private var sessionState: ClipboardSessionState? = null

    /** Last generated request — used by [recopyLastRequest]. */
    private var lastRequest: ClipboardRequest? = null

    /**
     * Backing field for the deprecated [isWaitingForResponse] compat API.
     * Set to true by [generateAndCopyJson], cleared by [handlePastedResponseInternal] and [reset].
     * Will be removed in STEP 8.
     */
    private var waitingForPaste: Boolean = false

    // ==================== Status Routing ====================

    /**
     * Returns the current clipboard status for the given session.
     * Falls back to [ClipboardSessionStatus.IDLE] when no manager is wired (pre-STEP 5).
     */
    private fun currentStatus(sessionId: String): ClipboardSessionStatus =
        sessionManager?.statusFor(sessionId) ?: ClipboardSessionStatus.IDLE

    // ==================== Public API ====================

    /**
     * Unified entry point for clipboard-mode UI interactions.
     *
     * Routes to [startTask], [continueDialog], or [handlePastedResponse] based on the
     * current session status, so UI code does not need to track internal states.
     *
     * @param sessionId          The ID of the current chat session.
     * @param userInput          Raw user input or pasted LLM response.
     * @param history            Pre-existing dialog history (used by [startTask] only).
     * @param attachedContext    Additional context text attached by the user.
     * @param planOnly           When true, instructs the LLM not to generate code changes.
     * @param ideErrors          IDE error output to include in the request.
     * @param globalContextFiles Paths to always include as fresh files.
     * @param addHistory         When true, full context and previously gathered paths are sent.
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
        // Waiting for a pasted LLM response — treat input as the paste content
        ClipboardSessionStatus.AWAITING_PASTE -> handlePastedResponse(sessionId, userInput)

        // Session live — continue the conversation with a new user message
        ClipboardSessionStatus.SESSION_ACTIVE -> continueDialog(
            sessionId = sessionId,
            message = userInput,
            attachedContext = attachedContext,
            planOnly = planOnly,
            ideErrors = ideErrors,
            globalContextFiles = globalContextFiles,
            addHistory = addHistory
        )

        // No active session — start a fresh task
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
     *
     * @param sessionId          The ID of the current chat session.
     * @param currentMessage     The user message to send to the LLM.
     * @param history            Pre-existing dialog history.
     * @param attachedContext    Additional context text.
     * @param planOnly           When true, the LLM is instructed not to generate code changes.
     * @param ideErrors          IDE error output to include.
     * @param globalContextFiles Paths that should always be included as fresh files.
     * @param addHistory         When true, previously gathered paths are included in the request.
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

        // Transition IDLE -> SESSION_ACTIVE; no-op when manager is null (pre-STEP 5)
        sessionManager?.transition(sessionId, ClipboardEvent.StartSession)

        notificationPort.showProgress("Gathering project context...", 0.1)
        val projectContextResult = contextProvider.getProjectContext()
        if (projectContextResult is Result.Failure) {
            return error("Failed to get project context: ${projectContextResult.error.message}")
        }
        val projectContext = (projectContextResult as Result.Success).value
        val prompts = promptPort?.getPrompts() ?: PromptTemplates.EMPTY

        log("Project: ${projectContext.name}, files in tree: ${projectContext.fileTree.totalFiles}")

        // Initialise in-memory session state for this dialog
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
     *
     * @param sessionId          The ID of the current chat session.
     * @param message            The user's follow-up message.
     * @param attachedContext    Replaces (or inherits) the attached context for this turn.
     * @param planOnly           Overrides plan-only mode; inherits previous value if null.
     * @param ideErrors          IDE error output for this turn.
     * @param globalContextFiles Paths to include as fresh files — only honoured when [addHistory]=true.
     * @param addHistory         When true, full context and all previously gathered paths are sent.
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
     *
     * @param sessionId The ID of the current chat session.
     * @param rawText   The raw text pasted from the external LLM interface.
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
        // Guard: state-machine transition check BEFORE touching in-memory state.
        // Returns false only when the manager explicitly rejects (invalid state).
        // Returns null when no manager is present (pre-STEP 5) — treat as accepted.
        val transitioned = sessionManager?.transition(sessionId, ClipboardEvent.ResponsePasted)
        if (transitioned == false) {
            return error("Cannot accept response paste: session is not in AWAITING_PASTE state.")
        }

        // Clear compat backing field
        waitingForPaste = false

        val state = sessionState
            ?: return error("No active clipboard session. Start a new task first.")

        log("Parsing pasted response (${rawText.length} chars)...")

        val outputTokens = rawText.length / 4
        val inputTokens = state.lastInputTokens

        // Parse the raw clipboard text into a structured response
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

        return processUnifiedResponse(sessionId, response, inputTokens, outputTokens)
    }

    /**
     * Returns the current clipboard phase for the active session, or null if no session is open.
     * Phase is PLANNING until the first files are gathered; CHAT afterwards.
     */
    fun getCurrentPhase(): ClipboardPhase? {
        val state = sessionState ?: return null
        return if (state.allGatheredFiles.isEmpty()) ClipboardPhase.PLANNING else ClipboardPhase.CHAT
    }

    /**
     * Returns the current [ClipboardSessionStatus] for the given session.
     * Delegates to [ClipboardSessionManager.statusFor]; falls back to IDLE when no manager is wired.
     */
    fun status(sessionId: String): ClipboardSessionStatus = currentStatus(sessionId)

    /**
     * Resets the clipboard session: clears in-memory state and transitions the session to IDLE.
     *
     * @param sessionId The ID of the session to reset.
     */
    fun reset(sessionId: String) {
        log("Session reset (sessionId=$sessionId)")
        sessionState = null
        lastRequest = null
        waitingForPaste = false
        sessionManager?.transition(sessionId, ClipboardEvent.Reset)
    }

    // ==================== Deprecated Legacy API ====================
    // Preserved for backward-compatibility during STEP 4 -> STEP 8 transition.

    /** @suppress Use [status] with an explicit sessionId instead. */
    @Deprecated("Use status(sessionId) instead", ReplaceWith("status(sessionId)"))
    fun isWaitingForResponse(): Boolean = waitingForPaste

    /** @suppress Use [status] with an explicit sessionId instead. */
    @Deprecated("Use status(sessionId) instead", ReplaceWith("status(sessionId)"))
    fun hasActiveSession(): Boolean = sessionState != null

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

        // Apply modifications before gathering additional files
        val modResults = if (hasMods) applyModifications(response.modifications)
        else emptyList<ModificationResult>()

        // If the LLM requested more files, gather them and send a follow-up request
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
     * Builds a [ClipboardRequest], copies it to the clipboard, and returns
     * [ClipboardStepResult.WaitingForResponse].
     *
     * ## Token-saving policy (Minimal mode)
     *
     * When [isFirstMessage] is false AND [addHistory] is false, the request is minimal:
     * only the current user message, freshly-requested files, and errors are included.
     * Heavy context fields are left blank so the codec omits them from JSON.
     *
     * @param sessionId        Session ID — used to fire the [ClipboardEvent.JsonCopied] transition.
     * @param freshFiles       Files gathered in this turn (path to content).
     * @param isFirstMessage   True for the very first message in a session.
     * @param assistantMessage Optional message to show above the status.
     * @param llmReasoning     Reasoning snippet from the previous LLM response.
     * @param addHistory       When true, full context and previously gathered paths are populated.
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

        // Minimal-mode: LLM already has all context in its chat window
        val isMinimal = !isFirstMessage && !addHistory
        val previousPaths: List<String> =
            if (addHistory) state.allGatheredFiles.keys.toList() else emptyList()

        log(
            "Generating JSON: freshFiles=${freshFiles.size}, previousPaths=${previousPaths.size}, " +
                    "historySize=${state.dialogHistory.size}, isMinimal=$isMinimal"
        )

        // In minimal mode carry only the latest user message
        val taskContent = if (isMinimal) {
            state.dialogHistory.lastOrNull { it.role == ChatRole.USER }?.content ?: state.currentMessage
        } else {
            state.currentMessage
        }

        // System prompt: omitted in minimal mode — codec skips blank strings
        val systemInstruction = if (isMinimal) "" else {
            if (state.allGatheredFiles.isEmpty()) {
                state.prompts.planningSystem
            } else {
                buildString {
                    append(state.prompts.chatSystem)
                    if (state.planOnly) append(PLAN_ONLY_SUFFIX)
                }
            }
        }

        val request = ClipboardRequest(
            phase = if (state.allGatheredFiles.isEmpty() && freshFiles.isEmpty())
                ClipboardPhase.PLANNING else ClipboardPhase.CHAT,
            currentMessage = taskContent,
            projectName = state.projectContext.name,
            // Blank/empty fields are omitted by JsonClipboardProtocolCodec.encode()
            systemInstruction = systemInstruction,
            fileTree = if (isMinimal) "" else state.projectContext.fileTree.toCompactString(maxDepth = 4),
            freshFiles = freshFiles,
            previouslyGatheredPaths = previousPaths,
            chatHistory = if (isMinimal) emptyList() else state.dialogHistory.map { msg ->
                ClipboardHistoryEntry(
                    role = when (msg.role) {
                        ChatRole.USER -> "user"
                        ChatRole.ASSISTANT -> "assistant"
                        ChatRole.SYSTEM -> "system"
                    },
                    content = msg.content
                )
            },
            attachedContext = if (isMinimal) null else state.attachedContext,
            ideErrors = state.ideErrors,
            planOnly = if (isMinimal) false else state.planOnly
        )

        lastRequest = request

        val copied = clipboardPort.copyRequestToClipboard(request)
        val copyStatus = if (copied) "copied to clipboard" else "generated (copy manually)"

        val totalTokens = estimateTokens(request)
        state.lastInputTokens = totalTokens

        log("JSON ready: $copyStatus, ~$totalTokens tokens")

        // Transition SESSION_ACTIVE -> AWAITING_PASTE (or AWAITING_PASTE -> AWAITING_PASTE on retry)
        sessionManager?.transition(sessionId, ClipboardEvent.JsonCopied)
        // Drive the deprecated isWaitingForResponse() compat API
        waitingForPaste = true

        return ClipboardStepResult.WaitingForResponse(
            phase = request.phase,
            statusMessage = "JSON $copyStatus. Paste into Claude/ChatGPT, then paste the response back here.",
            assistantMessage = assistantMessage,
            jsonRequest = request,
            estimatedInputTokens = totalTokens,
            llmReasoning = llmReasoning,
            freshFileNames = freshFiles.keys.map { it.substringAfterLast('/') },
            previouslyGatheredCount = previousPaths.size
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

    /**
     * Re-copies the last generated request JSON to the system clipboard.
     * Useful when the user accidentally dismissed the clipboard content before pasting.
     *
     * @return true if the content was successfully copied; false if there is no previous request.
     */
    fun recopyLastRequest(): Boolean {
        val req = lastRequest ?: return false
        return clipboardPort.copyRequestToClipboard(req)
    }

    companion object {
        /**
         * Suffix appended to the chat system prompt in plan-only mode.
         * Instructs the LLM to discuss the plan without generating code changes.
         * Single source of truth — used only inside [generateAndCopyJson].
         */
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

private data class ClipboardSessionState(
    /** Текущее сообщение пользователя, с которого началась или продолжается сессия. */
    val currentMessage: String,
    val projectContext: ProjectContext,
    val dialogHistory: MutableList<ChatMessageDTO>,
    val prompts: PromptTemplates,
    val allGatheredFiles: MutableMap<String, String>,
    val attachedContext: String? = null,
    val ideErrors: String? = null,
    var lastInputTokens: Int = 0,
    val planOnly: Boolean = false
)
