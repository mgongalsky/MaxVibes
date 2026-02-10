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

/**
 * Сервис для clipboard-режима взаимодействия.
 *
 * Multi-step workflow:
 *   1. User sends task → Planning JSON → clipboard
 *   2. User pastes planning response → gather files → Chat JSON → clipboard
 *   3. User pastes chat response → apply modifications
 */
class ClipboardInteractionService(
    private val contextProvider: ProjectContextPort,
    private val clipboardPort: ClipboardPort,
    private val codeRepository: CodeRepository,
    private val notificationPort: NotificationPort,
    private val promptPort: PromptPort? = null
) {
    /** Текущее состояние clipboard-сессии */
    private var sessionState: ClipboardSessionState? = null

    /**
     * Шаг 1: Пользователь ввёл задачу. Генерируем Planning JSON.
     */
    suspend fun startTask(task: String, history: List<ChatMessageDTO> = emptyList()): ClipboardStepResult {
        notificationPort.showProgress("Gathering project context...", 0.1)

        val projectContextResult = contextProvider.getProjectContext()
        if (projectContextResult is Result.Failure) {
            return ClipboardStepResult.Error("Failed to get project context: ${projectContextResult.error.message}")
        }
        val projectContext = (projectContextResult as Result.Success).value
        val prompts = promptPort?.getPrompts() ?: PromptTemplates.EMPTY

        sessionState = ClipboardSessionState(
            task = task,
            projectContext = projectContext,
            history = history,
            prompts = prompts,
            currentPhase = ClipboardPhase.PLANNING
        )

        val request = ClipboardRequest(
            phase = ClipboardPhase.PLANNING,
            task = task,
            projectName = projectContext.name,
            context = mapOf("fileTree" to projectContext.fileTree.toCompactString(maxDepth = 4)),
            systemInstruction = buildPlanningInstruction(projectContext, prompts)
        )

        val copied = clipboardPort.copyRequestToClipboard(request)
        val status = if (copied) "copied to clipboard" else "generated"

        return ClipboardStepResult.WaitingForResponse(
            phase = ClipboardPhase.PLANNING,
            userMessage = "📋 Planning JSON $status\n\nPaste this into Claude/ChatGPT, then paste the response back here.",
            jsonRequest = request
        )
    }

    /**
     * Шаг 2/3: Пользователь вставил ответ от LLM.
     */
    suspend fun handlePastedResponse(rawText: String): ClipboardStepResult {
        val state = sessionState
            ?: return ClipboardStepResult.Error("No active clipboard session. Start a new task first.")

        val response = clipboardPort.parseResponse(rawText)
            ?: return ClipboardStepResult.Error(
                "Failed to parse LLM response. Make sure you pasted the complete JSON response.\n" +
                        "Expected: {\"requestedFiles\": [...]} or {\"message\": \"...\", \"modifications\": [...]}"
            )

        return when (state.currentPhase) {
            ClipboardPhase.PLANNING -> handlePlanningResponse(response, state)
            ClipboardPhase.CHAT -> handleChatResponse(response, state)
        }
    }

    private suspend fun handlePlanningResponse(
        response: ClipboardResponse,
        state: ClipboardSessionState
    ): ClipboardStepResult {
        val requestedFiles = response.requestedFiles
        if (requestedFiles.isEmpty()) {
            return ClipboardStepResult.Error("LLM didn't request any files. Try rephrasing the task.")
        }

        notificationPort.showProgress("Gathering ${requestedFiles.size} files...", 0.4)

        val gatherResult = contextProvider.gatherFiles(requestedFiles)
        if (gatherResult is Result.Failure) {
            return ClipboardStepResult.Error("Failed to gather files: ${gatherResult.error.message}")
        }
        val gatheredContext = (gatherResult as Result.Success).value

        // Переходим в CHAT фазу
        sessionState = state.copy(
            currentPhase = ClipboardPhase.CHAT,
            gatheredFiles = gatheredContext.files
        )

        val historyEntries = state.history.map { msg ->
            ClipboardHistoryEntry(
                role = when (msg.role) {
                    ChatRole.USER -> "user"
                    ChatRole.ASSISTANT -> "assistant"
                    ChatRole.SYSTEM -> "system"
                },
                content = msg.content
            )
        }

        val request = ClipboardRequest(
            phase = ClipboardPhase.CHAT,
            task = state.task,
            projectName = state.projectContext.name,
            context = gatheredContext.files,
            chatHistory = historyEntries,
            systemInstruction = buildChatInstruction(state.projectContext, state.prompts)
        )

        val copied = clipboardPort.copyRequestToClipboard(request)
        val status = if (copied) "copied to clipboard" else "generated"

        return ClipboardStepResult.WaitingForResponse(
            phase = ClipboardPhase.CHAT,
            userMessage = "📋 Chat JSON with ${gatheredContext.files.size} files $status (~${gatheredContext.totalTokensEstimate} tokens)\n\n" +
                    "Paste this into Claude/ChatGPT, then paste the response back here.",
            jsonRequest = request
        )
    }

    private suspend fun handleChatResponse(
        response: ClipboardResponse,
        state: ClipboardSessionState
    ): ClipboardStepResult {
        val modifications = response.modifications.mapNotNull { convertModification(it) }

        val modResults = if (modifications.isNotEmpty()) {
            notificationPort.showProgress("Applying ${modifications.size} changes...", 0.8)
            codeRepository.applyModifications(modifications)
        } else {
            emptyList()
        }

        val successCount = modResults.count { it is ModificationResult.Success }
        val failCount = modResults.size - successCount

        // Сессия завершена
        sessionState = null

        if (modResults.isNotEmpty()) {
            if (failCount > 0) notificationPort.showWarning("Applied $successCount changes, $failCount failed")
            else notificationPort.showSuccess("Applied $successCount changes")
        } else {
            notificationPort.showSuccess("Done")
        }

        return ClipboardStepResult.Completed(
            message = response.message,
            modifications = modResults,
            success = failCount == 0
        )
    }

    fun isWaitingForResponse(): Boolean = sessionState != null
    fun getCurrentPhase(): ClipboardPhase? = sessionState?.currentPhase
    fun reset() { sessionState = null }

    // ==================== Helpers ====================

    private fun buildPlanningInstruction(projectContext: ProjectContext, prompts: PromptTemplates): String {
        return prompts.planningSystem.ifBlank {
            "You are an expert software architect. Analyze the task and project file tree.\n" +
                    "Respond ONLY with JSON: {\"requestedFiles\": [\"path/to/file.kt\", ...], \"reasoning\": \"why these files\"}\n" +
                    "Project: ${projectContext.name}, Language: ${projectContext.techStack.language}"
        }
    }

    private fun buildChatInstruction(projectContext: ProjectContext, prompts: PromptTemplates): String {
        return prompts.chatSystem.ifBlank {
            """You are MaxVibes AI coding assistant. Project: ${projectContext.name}, Language: ${projectContext.techStack.language}.

CRITICAL: Respond with a SINGLE JSON object. Do NOT write any text outside the JSON.
Put ALL your explanation, reasoning, and description of changes into the "message" field.
The "message" field should be detailed — explain what you're creating, why, and how it works.

Response format:
```json
{
    "message": "Your full detailed explanation here. Describe what files you're creating/modifying, what features you're adding, how the code works, any important notes. This text will be shown to the user in the chat window, so make it informative and helpful.",
    "modifications": [
        {
            "type": "CREATE_FILE",
            "path": "src/main/kotlin/com/example/File.kt",
            "content": "full file content with package, imports, everything"
        }
    ]
}
```

Rules:
- "type": "CREATE_FILE" for new files, "REPLACE_FILE" for modifying existing files
- "content" must contain complete, compilable code with package declarations and imports
- "message" must contain your full explanation — do NOT leave it empty
- If no code changes needed, set "modifications": [] and put your answer in "message"
- Do NOT write anything outside the JSON block"""
        }
    }

    private fun convertModification(mod: ClipboardModification): Modification? {
        if (mod.type.isBlank() || mod.path.isBlank()) return null
        val elementPath = ElementPath(mod.path)
        val elementKind = try { ElementKind.valueOf(mod.elementKind.uppercase()) } catch (_: Exception) { ElementKind.FILE }
        val position = try { InsertPosition.valueOf(mod.position.uppercase()) } catch (_: Exception) { InsertPosition.LAST_CHILD }

        return when (mod.type.uppercase()) {
            "CREATE_FILE" -> Modification.CreateFile(targetPath = elementPath, content = mod.content)
            "REPLACE_FILE" -> Modification.ReplaceFile(targetPath = elementPath, newContent = mod.content)
            "DELETE_FILE" -> Modification.DeleteFile(targetPath = elementPath)
            "CREATE_ELEMENT" -> Modification.CreateElement(targetPath = elementPath, elementKind = elementKind, content = mod.content, position = position)
            "REPLACE_ELEMENT" -> Modification.ReplaceElement(targetPath = elementPath, newContent = mod.content)
            "DELETE_ELEMENT" -> Modification.DeleteElement(targetPath = elementPath)
            else -> null
        }
    }
}

// ==================== Results ====================

sealed class ClipboardStepResult {
    data class WaitingForResponse(
        val phase: ClipboardPhase,
        val userMessage: String,
        val jsonRequest: ClipboardRequest
    ) : ClipboardStepResult()

    data class Completed(
        val message: String,
        val modifications: List<ModificationResult>,
        val success: Boolean
    ) : ClipboardStepResult()

    data class Error(val message: String) : ClipboardStepResult()
}

// ==================== Internal State ====================

private data class ClipboardSessionState(
    val task: String,
    val projectContext: ProjectContext,
    val history: List<ChatMessageDTO>,
    val prompts: PromptTemplates,
    val currentPhase: ClipboardPhase,
    val gatheredFiles: Map<String, String> = emptyMap()
)