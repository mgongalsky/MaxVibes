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

class ClipboardInteractionService(
    private val contextProvider: ProjectContextPort,
    private val clipboardPort: ClipboardPort,
    private val codeRepository: CodeRepository,
    private val notificationPort: NotificationPort,
    private val promptPort: PromptPort? = null,
    private val logger: LoggerPort? = null
) {
    /** Текущее состояние clipboard-сессии */
    private var sessionState: ClipboardSessionState? = null

    /** Ждём ли мы вставки ответа от LLM */
    private var waitingForPaste: Boolean = false
    private var lastRequest: ClipboardRequest? = null

    // ==================== Public API ====================

    /**
     * Starts a new clipboard task session.
     *
     * Gathers global context files, initialises session state, and copies the first
     * JSON request to the clipboard for the user to paste into an external LLM.
     *
     * @param task        The user's task description.
     * @param history     Pre-existing dialog history.
     * @param attachedContext Additional context text attached by the user.
     * @param planOnly    When true, the LLM is instructed not to generate code changes.
     * @param ideErrors   IDE error output to include in the request.
     * @param globalContextFiles Paths that should always be included as fresh files.
     * @param addHistory  UI: "Add History" — when true, previously gathered file paths
     *                    are included in [ClipboardRequest.previouslyGatheredPaths] so
     *                    the LLM can request them again without re-uploading content.
     *                    When false (default), that list is empty, minimising token usage.
     */
    suspend fun startTask(
        task: String,
        history: List<ChatMessageDTO> = emptyList(),
        attachedContext: String? = null,
        planOnly: Boolean = false,
        ideErrors: String? = null,
        globalContextFiles: List<String> = emptyList(),
        addHistory: Boolean = false
    ): ClipboardStepResult {
        log("Starting new clipboard task: \"${task.take(60)}...\" (planOnly=$planOnly, addHistory=$addHistory)")

        notificationPort.showProgress("Gathering project context...", 0.1)
        val projectContextResult = contextProvider.getProjectContext()
        if (projectContextResult is Result.Failure) {
            return error("Failed to get project context: ${projectContextResult.error.message}")
        }
        val projectContext = (projectContextResult as Result.Success).value
        val prompts = promptPort?.getPrompts() ?: PromptTemplates.EMPTY

        log("Project: ${projectContext.name}, files in tree: ${projectContext.fileTree.totalFiles}")

        sessionState = ClipboardSessionState(
            task = task,
            projectContext = projectContext,
            dialogHistory = history.toMutableList(),
            prompts = prompts,
            allGatheredFiles = mutableMapOf(),
            attachedContext = attachedContext,
            ideErrors = ideErrors,
            planOnly = planOnly
        )

        addToHistory(ChatRole.USER, task)

        val freshFiles = gatherRequestedFiles(globalContextFiles) ?: emptyMap()

        return generateAndCopyJson(
            freshFiles = freshFiles,
            isFirstMessage = true,
            addHistory = addHistory
        )
    }

    /**
     * Continues an existing clipboard dialog session with a new user message.
     *
     * @param message       The user's follow-up message.
     * @param attachedContext Replaces (or inherits) the attached context for this turn.
     * @param planOnly      Overrides plan-only mode for this turn; inherits previous value if null.
     * @param ideErrors     IDE error output for this turn.
     * @param globalContextFiles Paths to include as fresh files — only honoured when [addHistory]=true.
     *                      In minimal mode the LLM already has these files in its chat window, so
     *                      re-sending them wastes tokens.
     * @param addHistory    UI: "Add History" — when true, the list of all previously gathered
     *                      file paths is sent so the LLM can request them again if its context
     *                      was lost. When false (default), the list is empty to save tokens.
     */
    suspend fun continueDialog(
        message: String,
        attachedContext: String? = null,
        planOnly: Boolean? = null,
        ideErrors: String? = null,
        globalContextFiles: List<String> = emptyList(),
        addHistory: Boolean = false
    ): ClipboardStepResult {
        val state = sessionState
            ?: return error("No active clipboard session. Start a new task first.")

        log("Continuing dialog: \"${message.take(60)}...\" (new planOnly=$planOnly, addHistory=$addHistory)")

        sessionState = state.copy(
            attachedContext = attachedContext ?: state.attachedContext,
            ideErrors = ideErrors ?: state.ideErrors,
            planOnly = planOnly ?: state.planOnly
        )

        addToHistory(ChatRole.USER, message)

        // In minimal mode the LLM already has globalContextFiles in its chat window from the
        // first message — passing them again wastes tokens. Only gather them in full-context mode.
        val filesToGather = if (addHistory) globalContextFiles else emptyList()
        val freshFiles = gatherRequestedFiles(filesToGather) ?: emptyMap()

        return generateAndCopyJson(
            freshFiles = freshFiles,
            isFirstMessage = false,
            addHistory = addHistory
        )
    }

    suspend fun handlePastedResponse(rawText: String): ClipboardStepResult {
        return try {
            handlePastedResponseInternal(rawText)
        } catch (e: Exception) {
            val msg = "Unexpected error processing response: ${e.javaClass.simpleName}: ${e.message}"
            println("[MaxVibes Clipboard] FATAL: $msg")
            logger?.error("Clipboard", msg, e)
            ClipboardStepResult.Error("⚠️ $msg\n\nThe session is still active — you can try pasting the response again.")
        }
    }

    private suspend fun handlePastedResponseInternal(rawText: String): ClipboardStepResult {
        val state = sessionState
            ?: return error("No active clipboard session. Start a new task first.")

        log("Parsing pasted response (${rawText.length} chars)...")

        waitingForPaste = false

        val outputTokens = rawText.length / 4
        val inputTokens = state.lastInputTokens

        val response = try {
            clipboardPort.parseResponse(rawText)
        } catch (e: Exception) {
            log("ERROR: Exception during JSON parse: ${e.message}")
            return error("Failed to parse response JSON: ${e.message}\n\nMake sure you pasted the complete raw JSON (no markdown backticks).")
        }

        if (response == null) {
            log("ERROR: Failed to parse response — null returned")
            return error(
                "Failed to parse LLM response.\n\n" +
                        "Expected JSON format:\n" +
                        "{\n" +
                        "  \"message\": \"explanation text\",\n" +
                        "  \"requestedFiles\": [\"path/file.kt\"],  // optional\n" +
                        "  \"modifications\": [...]                  // optional\n" +
                        "}\n\n" +
                        "Tip: make sure you pasted the complete response without markdown code blocks."
            )
        }

        log(
            "Parsed: message=${response.message.take(50)}..., " +
                    "requestedFiles=${response.requestedFiles.size}, " +
                    "modifications=${response.modifications.size}, " +
                    "reasoning=${response.reasoning?.take(40) ?: "none"}"
        )

        if (response.message.isNotBlank()) {
            addToHistory(ChatRole.ASSISTANT, response.message)
        }

        return processUnifiedResponse(response, inputTokens, outputTokens)
    }

    fun isWaitingForResponse(): Boolean = waitingForPaste
    fun getCurrentPhase(): ClipboardPhase? {
        val state = sessionState ?: return null
        return if (state.allGatheredFiles.isEmpty()) ClipboardPhase.PLANNING else ClipboardPhase.CHAT
    }

    fun hasActiveSession(): Boolean = sessionState != null
    fun reset() {
        log("Session reset")
        sessionState = null
        waitingForPaste = false
        lastRequest = null
    }

    // ==================== Core Logic ====================

    private suspend fun processUnifiedResponse(
        response: ClipboardResponse,
        inputTokens: Int = 0,
        outputTokens: Int = 0
    ): ClipboardStepResult {
        val state = sessionState ?: return error("No active session")

        val hasFiles = response.requestedFiles.isNotEmpty()
        val hasMods = response.modifications.isNotEmpty()
        val hasMessage = response.message.isNotBlank()

        log("Processing: hasFiles=$hasFiles, hasMods=$hasMods, hasMessage=$hasMessage")

        val modResults = if (hasMods) {
            applyModifications(response.modifications)
        } else {
            emptyList<ModificationResult>()
        }

        if (hasFiles) {
            val gatheredFilesMap = gatherRequestedFiles(response.requestedFiles)
            if (gatheredFilesMap == null) {
                return buildCompletedResult(
                    response = response,
                    modResults = modResults,
                    extraMessage = "\n\n⚠️ Failed to gather some requested files.",
                    inputTokens = inputTokens,
                    outputTokens = outputTokens
                )
            }

            val assistantDisplayMessage = response.message.trim()
            val reasoningStr: String? = response.reasoning?.takeIf { it.isNotBlank() }

            return generateAndCopyJson(
                freshFiles = gatheredFilesMap,
                isFirstMessage = false,
                assistantMessage = if (assistantDisplayMessage.isNotBlank()) assistantDisplayMessage else null,
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
     * Builds a [ClipboardRequest], copies it to the clipboard, and transitions the
     * session into [ClipboardStepResult.WaitingForResponse].
     *
     * ## Token-saving policy (Minimal mode)
     *
     * When [isFirstMessage] is `false` AND [addHistory] is `false`, the request is
     * **minimal**: only the current user message (`task`), freshly-requested file
     * contents (`files`), and errors are included. Heavy context fields —
     * `systemInstruction`, `fileTree`, `chatHistory`, `attachedContext` — are
     * deliberately left blank/empty so the codec omits them from the JSON.
     *
     * This is safe because the LLM is in an ongoing chat and already has the full
     * context from the previous message. Re-sending it wastes tokens and can
     * confuse newer models with repeated system instructions.
     *
     * **Full context** is sent only:
     * - On the very first message in a session ([isFirstMessage] = `true`), OR
     * - When the user checks "Add History" ([addHistory] = `true`), which signals
     *   that a fresh LLM chat is starting and needs all the context again.
     *
     * @param freshFiles       Files gathered in this turn (path → content).
     * @param isFirstMessage   True for the very first message in a session.
     * @param assistantMessage Assistant message to display above the status (from file-gather step).
     * @param llmReasoning     Reasoning snippet from the previous LLM response, shown in UI.
     * @param addHistory       When true, [ClipboardRequest.previouslyGatheredPaths] is populated
     *                         with all file paths gathered so far, AND full context is included,
     *                         giving a fresh LLM chat everything it needs to continue.
     */
    private fun generateAndCopyJson(
        freshFiles: Map<String, String>,
        isFirstMessage: Boolean,
        assistantMessage: String? = null,
        llmReasoning: String? = null,
        addHistory: Boolean = false
    ): ClipboardStepResult {
        val state = sessionState ?: return error("No active session")

        // --- Minimal-mode flag ---
        // Continuation without "Add History": LLM already has all context in its chat.
        // Skip heavy fields to save tokens.
        val isMinimal = !isFirstMessage && !addHistory

        // Only populate previouslyGatheredPaths when addHistory is on (full-context mode).
        val previousPaths: List<String> =
            if (addHistory) state.allGatheredFiles.keys.toList() else emptyList()

        log(
            "Generating JSON: freshFiles=${freshFiles.size}, previousPaths=${previousPaths.size}, " +
                    "historySize=${state.dialogHistory.size}, planOnly=${state.planOnly}, " +
                    "addHistory=$addHistory, isMinimal=$isMinimal"
        )

        // In minimal mode, carry only the current user message as the task so the LLM
        // knows what follow-up action is requested without re-reading the full history.
        val taskContent = if (isMinimal) {
            state.dialogHistory.lastOrNull { it.role == ChatRole.USER }?.content ?: state.task
        } else {
            state.task
        }

        // System prompt: omitted in minimal mode — codec skips blank strings.
        val systemInstruction = if (isMinimal) "" else {
            // Planning phase uses planning-specific prompt; chat phase uses chat prompt.
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
            task = taskContent,
            projectName = state.projectContext.name,
            // Blank/empty fields are omitted by JsonClipboardProtocolCodec.encode().
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
            // attachedContext and planOnly are only meaningful in full-context mode.
            attachedContext = if (isMinimal) null else state.attachedContext,
            ideErrors = state.ideErrors,   // always include — directly relevant to current message
            planOnly = if (isMinimal) false else state.planOnly
        )

        lastRequest = request

        val copied = clipboardPort.copyRequestToClipboard(request)
        val copyStatus = if (copied) "copied to clipboard ✓" else "generated (copy manually)"

        val totalTokens = estimateTokens(request)
        state.lastInputTokens = totalTokens

        val statusMessage =
            "\uD83D\uDCCB JSON $copyStatus\nPaste into Claude/ChatGPT, then paste the response back here."

        log("JSON ready: $copyStatus, ~$totalTokens tokens")

        waitingForPaste = true

        return ClipboardStepResult.WaitingForResponse(
            phase = request.phase,
            statusMessage = statusMessage,
            assistantMessage = assistantMessage,
            jsonRequest = request,
            estimatedInputTokens = totalTokens,
            llmReasoning = llmReasoning,
            freshFileNames = freshFiles.keys.map { it.substringAfterLast('/') },
            previouslyGatheredCount = previousPaths.size
        )
    }

    // ==================== File Gathering ====================

    private suspend fun gatherRequestedFiles(
        requestedPaths: List<String>
    ): Map<String, String>? {
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

    private suspend fun applyModifications(
        clipboardMods: List<ClipboardModification>
    ): List<ModificationResult> {
        val modifications = clipboardMods.mapNotNull { convertModification(it) }
        if (modifications.isEmpty()) return emptyList()

        log("Applying ${modifications.size} modifications...")
        notificationPort.showProgress("Applying ${modifications.size} changes...", 0.8)

        val results = codeRepository.applyModifications(modifications)

        val successCount = results.count { it is ModificationResult.Success }
        val failCount = results.size - successCount

        log("Modifications: $successCount success, $failCount failed")

        if (failCount > 0) {
            notificationPort.showWarning("Applied $successCount changes, $failCount failed")
        } else if (successCount > 0) {
            notificationPort.showSuccess("Applied $successCount changes")
        }

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
            if (response.message.isNotBlank()) {
                append(response.message)
            }
            if (extraMessage.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(extraMessage)
            }
            if (isEmpty()) {
                append("Done.")
            }
        }

        if (modResults.isNotEmpty()) {
            notificationPort.showSuccess("Done. Session active — you can continue the dialog.")
        }

        log("Completed: message=${response.message.take(40)}..., mods=$successCount ok/$failCount fail.")

        val reasoningStr: String? = response.reasoning?.takeIf { it.isNotBlank() }

        return ClipboardStepResult.Completed(
            message = messageText.trim(),
            modifications = modResults,
            success = failCount == 0,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            llmReasoning = reasoningStr,
            commitMessage = response.commitMessage?.takeIf { it.isNotBlank() }
        )
    }

    private fun buildFileGatherMessage(
        response: ClipboardResponse,
        freshFiles: Map<String, String>
    ): String = buildString {
        if (response.reasoning?.isNotBlank() == true) {
            appendLine("💭 ${response.reasoning}")
            appendLine()
        }
        appendLine("📁 Gathered ${freshFiles.size} file(s):")
        freshFiles.keys.forEach { path ->
            appendLine("   • ${path.substringAfterLast('/')}")
        }
    }

    // ==================== System Instructions ====================

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
                targetPath = elementPath,
                elementKind = elementKind,
                content = mod.content,
                position = position
            )

            "REPLACE_ELEMENT" -> Modification.ReplaceElement(targetPath = elementPath, newContent = mod.content)
            "DELETE_ELEMENT" -> Modification.DeleteElement(targetPath = elementPath)
            "ADD_IMPORT" -> {
                val importFqName = mod.importPath.ifBlank { mod.content.removePrefix("import ").trim() }
                if (importFqName.isBlank()) null
                else Modification.AddImport(targetPath = elementPath, importPath = importFqName)
            }

            "REMOVE_IMPORT" -> {
                val importFqName = mod.importPath.ifBlank { mod.content.removePrefix("import ").trim() }
                if (importFqName.isBlank()) null
                else Modification.RemoveImport(targetPath = elementPath, importPath = importFqName)
            }

            else -> null
        }
    }

    fun recopyLastRequest(): Boolean {
        val req = lastRequest ?: return false
        return clipboardPort.copyRequestToClipboard(req)
    }

    companion object {
        /**
         * Суффикс, добавляемый к chat-промпту в plan-only режиме.
         * Не содержит базовый промпт — только дополнение, запрещающее генерацию кода.
         * Единственный источник plan-only текста: живёт здесь, используется в generateAndCopyJson.
         */
        private val PLAN_ONLY_SUFFIX = """

## ⚠️ PLAN-ONLY MODE — DISCUSSION REQUIRED

DO NOT generate any code changes in the "modifications" array. 
Keep "modifications": [] empty.
Your goal is to DISCUSS the plan with the user before any code is written.

Instead of code, you must:
1. Briefly explain what you understand from the task
2. List which files you plan to touch and what changes you'll make in each
3. Mention any architectural decisions or trade-offs
4. Ask the user to confirm or suggest corrections

Always output the JSON with "modifications": [] and put your discussion in "message".""".trimIndent()
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
    val task: String,
    val projectContext: ProjectContext,
    val dialogHistory: MutableList<ChatMessageDTO>,
    val prompts: PromptTemplates,
    val allGatheredFiles: MutableMap<String, String>,
    val attachedContext: String? = null,
    val ideErrors: String? = null,
    var lastInputTokens: Int = 0,
    val planOnly: Boolean = false
)