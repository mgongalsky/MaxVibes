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

    suspend fun startTask(
        task: String,
        history: List<ChatMessageDTO> = emptyList(),
        attachedContext: String? = null,
        planOnly: Boolean = false,
        ideErrors: String? = null,
        globalContextFiles: List<String> = emptyList()
    ): ClipboardStepResult {
        log("Starting new clipboard task: \"${task.take(60)}...\" (planOnly=$planOnly)")

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
            isFirstMessage = true
        )
    }

    suspend fun continueDialog(
        message: String,
        attachedContext: String? = null,
        planOnly: Boolean? = null,
        ideErrors: String? = null,
        globalContextFiles: List<String> = emptyList()
    ): ClipboardStepResult {
        val state = sessionState
            ?: return error("No active clipboard session. Start a new task first.")

        log("Continuing dialog: \"${message.take(60)}...\" (new planOnly=$planOnly)")

        sessionState = state.copy(
            attachedContext = attachedContext ?: state.attachedContext,
            ideErrors = ideErrors ?: state.ideErrors,
            planOnly = planOnly ?: state.planOnly
        )

        addToHistory(ChatRole.USER, message)

        val freshFiles = gatherRequestedFiles(globalContextFiles) ?: emptyMap()

        return generateAndCopyJson(
            freshFiles = freshFiles,
            isFirstMessage = false
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

    private fun generateAndCopyJson(
        freshFiles: Map<String, String>,
        isFirstMessage: Boolean,
        assistantMessage: String? = null,
        llmReasoning: String? = null
    ): ClipboardStepResult {
        val state = sessionState ?: return error("No active session")

        val previousPaths = state.allGatheredFiles.keys.toList()

        log(
            "Generating JSON: freshFiles=${freshFiles.size}, previousPaths=${previousPaths.size}, " +
                    "historySize=${state.dialogHistory.size}, planOnly=${state.planOnly}"
        )

        val request = ClipboardRequest(
            phase = if (state.allGatheredFiles.isEmpty() && freshFiles.isEmpty())
                ClipboardPhase.PLANNING else ClipboardPhase.CHAT,
            task = state.task,
            projectName = state.projectContext.name,
            systemInstruction = buildSystemInstruction(state),
            fileTree = state.projectContext.fileTree.toCompactString(maxDepth = 4),
            freshFiles = freshFiles,
            previouslyGatheredPaths = previousPaths,
            chatHistory = state.dialogHistory.map { msg ->
                ClipboardHistoryEntry(
                    role = when (msg.role) {
                        ChatRole.USER -> "user"
                        ChatRole.ASSISTANT -> "assistant"
                        ChatRole.SYSTEM -> "system"
                    },
                    content = msg.content
                )
            },
            attachedContext = state.attachedContext,
            ideErrors = state.ideErrors,
            planOnly = state.planOnly
        )

        lastRequest = request

        val copied = clipboardPort.copyRequestToClipboard(request)
        val copyStatus = if (copied) "copied to clipboard ✓" else "generated (copy manually)"

        val totalTokens = estimateTokens(request)
        state.lastInputTokens = totalTokens

        val statusMessage = "📋 JSON $copyStatus\nPaste into Claude/ChatGPT, then paste the response back here."

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

    private fun buildSystemInstruction(state: ClipboardSessionState): String {
        val isFirstPhase = state.allGatheredFiles.isEmpty()
        return if (isFirstPhase) buildPlanningInstruction(state) else buildChatInstruction(state)
    }

    private fun buildPlanningInstruction(state: ClipboardSessionState): String {
        val custom = state.prompts.planningSystem
        if (custom.isNotBlank()) return custom

        return """⚠️ CRITICAL: This is a MaxVibes clipboard protocol message. You MUST respond with ONLY a JSON object as plain text in the chat.
DO NOT use computer tools. DO NOT create files. DO NOT use bash. DO NOT use artifacts.
DO NOT write code to disk. Your ENTIRE response must be a single JSON object — nothing else.

You are an expert software architect assistant in a clipboard-based dialog through MaxVibes IDE plugin.

TASK: Analyze the task and project file tree, then decide what you need.

Your response must be EXACTLY this JSON format (and nothing else):
{
    "message": "Your thoughts, questions, or discussion about the task",
    "requestedFiles": ["path/to/file.kt", ...],
    "reasoning": "Why you need these specific files"
}

Rules:
- "message" is REQUIRED — always explain your thinking
- "requestedFiles" — list files you need to see. Leave empty [] if you just want to discuss.
- If the task is just a question/discussion (no coding needed), set "requestedFiles": [] and put your answer in "message"
- DO NOT wrap the JSON in markdown code blocks. Just output raw JSON.
- Project: ${state.projectContext.name}, Language: ${state.projectContext.techStack.language}"""
    }

    private fun buildChatInstruction(state: ClipboardSessionState): String {
        val custom = state.prompts.chatSystem
        val basePrompt =
            if (custom.isNotBlank()) custom else """⚠️ CRITICAL: This is a MaxVibes clipboard protocol message. You MUST respond with ONLY a JSON object as plain text in the chat.
DO NOT use computer tools. DO NOT create files. DO NOT use bash. DO NOT use artifacts.
DO NOT write code to disk. ALL code goes into the "modifications" array inside the JSON.
Your ENTIRE response must be a single JSON object — nothing else.

You are MaxVibes AI coding assistant in a continuous clipboard-based dialog.
Project: ${state.projectContext.name}, Language: ${state.projectContext.techStack.language}.

Your response must be EXACTLY this JSON format (and nothing else):
{
    "message": "Your detailed explanation, discussion, or answer",
    "requestedFiles": ["path/to/file.kt"],
    "modifications": [
        {
            "type": "REPLACE_ELEMENT",
            "path": "file:src/main/kotlin/com/example/User.kt/class[User]/function[validate]",
            "content": "fun validate(): Boolean {\\n    return name.isNotBlank()\\n}",
            "elementKind": "FUNCTION"
        }
    ]
}

ALL FIELDS ARE OPTIONAL except "message" which is always recommended."""

        if (!state.planOnly) return basePrompt

        return basePrompt + """

## ⚠️ PLAN-ONLY MODE — DISCUSSION REQUIRED

DO NOT generate any code changes in the "modifications" array. 
Keep "modifications": [] empty.
Your goal is to DISCUSS the plan with the user before any code is written.

Instead of code, you must:
1. Briefly explain what you understand from the task
2. List which files you plan to touch and what changes you'll make in each
3. Mention any architectural decisions or trade-offs
4. Ask the user to confirm or suggest corrections

Always output the JSON with "modifications": [] and put your discussion in "message"."""
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