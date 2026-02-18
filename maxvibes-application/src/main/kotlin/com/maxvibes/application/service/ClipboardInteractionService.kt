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
 * Непрерывный диалог:
 *   User msg → JSON (с fileTree) → clipboard → paste response →
 *     ├─ has requestedFiles? → gather files → JSON (с содержимым файлов) → clipboard → paste response → ...
 *     ├─ has modifications? → apply → show message → session alive, user can continue
 *     └─ message only? → show message → session alive, user can continue
 *
 * Контекст:
 *   - chatHistory: полная текстовая история (без кода)
 *   - freshFiles: полное содержимое только свежезапрошенных файлов
 *   - previouslyGatheredPaths: пути ранее собранных файлов (без содержимого)
 *   - fileTree: всегда включено
 *
 * Единый формат ответа Claude:
 *   { "message": "...", "requestedFiles": [...], "modifications": [...] }
 *   Все поля опциональны, но message рекомендуется всегда.
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

    /** Ждём ли мы вставки ответа от LLM */
    private var waitingForPaste: Boolean = false

    // ==================== Public API ====================

    /**
     * Начинает новый диалог: генерирует первый JSON с fileTree.
     */
    suspend fun startTask(
        task: String,
        history: List<ChatMessageDTO> = emptyList(),
        attachedContext: String? = null
    ): ClipboardStepResult {
        log("Starting new clipboard task: \"${task.take(60)}...\"")

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
            attachedContext = attachedContext
        )

        // Добавляем user message в историю
        addToHistory(ChatRole.USER, task)

        return generateAndCopyJson(
            freshFiles = emptyMap(),
            isFirstMessage = true
        )
    }

    /**
     * Продолжает существующий диалог: генерирует JSON с новым сообщением.
     * Используется когда пользователь хочет продолжить разговор (не paste).
     */
    suspend fun continueDialog(
        message: String,
        attachedContext: String? = null
    ): ClipboardStepResult {
        val state = sessionState
            ?: return error("No active clipboard session. Start a new task first.")

        log("Continuing dialog: \"${message.take(60)}...\"")

        // Обновляем attached context если есть
        if (!attachedContext.isNullOrBlank()) {
            sessionState = state.copy(attachedContext = attachedContext)
        }

        addToHistory(ChatRole.USER, message)

        return generateAndCopyJson(
            freshFiles = emptyMap(),
            isFirstMessage = false
        )
    }

    /**
     * Обрабатывает вставленный ответ от LLM.
     * Единый обработчик — сам определяет что делать по содержимому ответа.
     */
    suspend fun handlePastedResponse(rawText: String): ClipboardStepResult {
        val state = sessionState
            ?: return error("No active clipboard session. Start a new task first.")

        log("Parsing pasted response (${rawText.length} chars)...")

        waitingForPaste = false  // Response received, no longer waiting

        val response = clipboardPort.parseResponse(rawText)
        if (response == null) {
            log("ERROR: Failed to parse response")
            return error(
                "Failed to parse LLM response.\n\n" +
                        "Expected JSON format:\n" +
                        "{\n" +
                        "  \"message\": \"explanation text\",\n" +
                        "  \"requestedFiles\": [\"path/file.kt\"],  // optional\n" +
                        "  \"modifications\": [...]                  // optional\n" +
                        "}\n\n" +
                        "Tip: make sure you pasted the complete response."
            )
        }

        log("Parsed: message=${response.message.take(50)}..., " +
                "requestedFiles=${response.requestedFiles.size}, " +
                "modifications=${response.modifications.size}, " +
                "reasoning=${response.reasoning?.take(40) ?: "none"}")

        // Добавляем ответ ассистента в историю (только message, без кода)
        if (response.message.isNotBlank()) {
            addToHistory(ChatRole.ASSISTANT, response.message)
        }

        return processUnifiedResponse(response)
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
    }

    // ==================== Core Logic ====================

    /**
     * Обрабатывает единый ответ — определяет действия по содержимому.
     */
    private suspend fun processUnifiedResponse(response: ClipboardResponse): ClipboardStepResult {
        val state = sessionState ?: return error("No active session")

        val hasFiles = response.requestedFiles.isNotEmpty()
        val hasMods = response.modifications.isNotEmpty()
        val hasMessage = response.message.isNotBlank()

        log("Processing: hasFiles=$hasFiles, hasMods=$hasMods, hasMessage=$hasMessage")

        // --- Шаг 1: Применяем модификации (если есть) ---
        val modResults = if (hasMods) {
            applyModifications(response.modifications)
        } else {
            emptyList()
        }

        // --- Шаг 2: Собираем запрошенные файлы (если есть) ---
        if (hasFiles) {
            val freshFiles = gatherRequestedFiles(response.requestedFiles)
            if (freshFiles == null) {
                // Ошибка сбора — но всё равно показываем message и mods
                return buildCompletedResult(response, modResults,
                    extraMessage = "\n\n⚠️ Failed to gather some requested files.")
            }

            // Если были и моды и файлы — показываем результат модов + новый JSON
            val modSummary = if (modResults.isNotEmpty()) {
                buildModSummary(modResults) + "\n\n"
            } else ""

            // Генерируем следующий JSON с новыми файлами
            return generateAndCopyJson(
                freshFiles = freshFiles,
                isFirstMessage = false,
                prefixMessage = modSummary + buildFileGatherMessage(response, freshFiles)
            )
        }

        // --- Шаг 3: Только message и/или mods — показываем результат ---
        // Сессия остаётся активной для продолжения диалога!
        return buildCompletedResult(response, modResults)
    }

    /**
     * Генерирует JSON-запрос и копирует в буфер.
     */
    private fun generateAndCopyJson(
        freshFiles: Map<String, String>,
        isFirstMessage: Boolean,
        prefixMessage: String? = null
    ): ClipboardStepResult {
        val state = sessionState ?: return error("No active session")

        val previousPaths = state.allGatheredFiles.keys.toList()

        log("Generating JSON: freshFiles=${freshFiles.size}, previousPaths=${previousPaths.size}, " +
                "historySize=${state.dialogHistory.size}")

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
            attachedContext = state.attachedContext
        )

        val copied = clipboardPort.copyRequestToClipboard(request)
        val copyStatus = if (copied) "copied to clipboard ✓" else "generated (copy manually)"

        val totalTokens = estimateTokens(request)
        val phase = request.phase.name.lowercase()

        val userMessage = buildString {
            if (!prefixMessage.isNullOrBlank()) {
                appendLine(prefixMessage)
                appendLine()
            }

            appendLine("📋 JSON $copyStatus")
            appendLine("   Phase: $phase | History: ${state.dialogHistory.size} msgs | ~$totalTokens tokens")

            if (freshFiles.isNotEmpty()) {
                appendLine("   📁 Fresh files (${freshFiles.size}):")
                freshFiles.keys.forEach { path ->
                    appendLine("      • ${path.substringAfterLast('/')}")
                }
            }
            if (previousPaths.isNotEmpty()) {
                appendLine("   📂 Previously gathered: ${previousPaths.size} file(s)")
            }

            appendLine()
            append("Paste this into Claude/ChatGPT, then paste the response back here.")
        }

        log("JSON ready: $copyStatus, ~$totalTokens tokens")

        waitingForPaste = true

        return ClipboardStepResult.WaitingForResponse(
            phase = request.phase,
            userMessage = userMessage,
            jsonRequest = request
        )
    }

    // ==================== File Gathering ====================

    private suspend fun gatherRequestedFiles(
        requestedPaths: List<String>
    ): Map<String, String>? {
        val state = sessionState ?: return null

        // Фильтруем уже собранные файлы
        val newPaths = requestedPaths.filter { it !in state.allGatheredFiles }
        val alreadyGathered = requestedPaths.filter { it in state.allGatheredFiles }

        if (alreadyGathered.isNotEmpty()) {
            log("Already gathered (skipping): ${alreadyGathered.size} files")
        }

        if (newPaths.isEmpty()) {
            log("All requested files already gathered, re-sending existing")
            // Все файлы уже были — отправляем из кэша
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

        // Сохраняем в кэш
        state.allGatheredFiles.putAll(gathered.files)

        log("Gathered ${gathered.files.size} files, total cached: ${state.allGatheredFiles.size}")

        // Возвращаем только свежезапрошенные (полное содержимое)
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
        extraMessage: String = ""
    ): ClipboardStepResult {
        val successCount = modResults.count { it is ModificationResult.Success }
        val failCount = modResults.size - successCount

        val message = buildString {
            if (response.message.isNotBlank()) {
                append(response.message)
            }
            if (modResults.isNotEmpty()) {
                if (isNotBlank()) appendLine()
                append(buildModSummary(modResults))
            }
            if (extraMessage.isNotBlank()) {
                append(extraMessage)
            }
            if (isBlank()) {
                append("Done (no message from LLM).")
            }
        }

        // Сессия НЕ сбрасывается — пользователь может продолжить диалог!
        if (modResults.isNotEmpty()) {
            notificationPort.showSuccess("Done. Session active — you can continue the dialog.")
        }

        log("Completed: message=${response.message.take(40)}..., mods=$successCount ok/$failCount fail. Session stays active.")

        return ClipboardStepResult.Completed(
            message = message,
            modifications = modResults,
            success = failCount == 0
        )
    }

    private fun buildModSummary(modResults: List<ModificationResult>): String = buildString {
        val ok = modResults.filterIsInstance<ModificationResult.Success>()
        val fail = modResults.filterIsInstance<ModificationResult.Failure>()
        appendLine("\n───────────────")
        if (ok.isNotEmpty()) {
            appendLine("✅ Applied ${ok.size} change(s):")
            ok.forEach { appendLine("   • ${it.affectedPath.value.substringAfterLast('/')}") }
        }
        if (fail.isNotEmpty()) {
            appendLine("❌ Failed ${fail.size} change(s):")
            fail.forEach { appendLine("   • ${it.error.message}") }
        }
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

        return if (isFirstPhase) {
            buildPlanningInstruction(state)
        } else {
            buildChatInstruction(state)
        }
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
        if (custom.isNotBlank()) return custom

        return """⚠️ CRITICAL: This is a MaxVibes clipboard protocol message. You MUST respond with ONLY a JSON object as plain text in the chat.
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
            "content": "fun validate(): Boolean {\n    return name.isNotBlank()\n}",
            "elementKind": "FUNCTION"
        }
    ]
}

ALL FIELDS ARE OPTIONAL except "message" which is always recommended.

## Modification types (prefer element-level for existing files!)

| type | When | path | content | extra fields |
|------|------|------|---------|-------------|
| REPLACE_ELEMENT | Change a function/class/property | file:path/File.kt/class[X]/function[Y] | Complete element | elementKind |
| CREATE_ELEMENT | Add new element to parent | file:path/File.kt/class[X] | New element | elementKind, position |
| DELETE_ELEMENT | Remove an element | file:path/File.kt/class[X]/function[Y] | (empty) | |
| ADD_IMPORT | Add import | file:path/File.kt | (empty) | importPath: "com.example.Foo" |
| REMOVE_IMPORT | Remove import | file:path/File.kt | (empty) | importPath: "com.example.Bar" |
| CREATE_FILE | New file | src/main/kotlin/.../File.kt | Full file | |
| REPLACE_FILE | Rewrite entire file (sparingly!) | src/main/kotlin/.../File.kt | Full file | |

## Element path format
file:src/main/kotlin/com/example/User.kt/class[User]/function[validate]
Segments: class[Name], interface[Name], object[Name], function[Name], property[Name], companion_object, init

## Rules
- PREFER REPLACE_ELEMENT/CREATE_ELEMENT over REPLACE_FILE — saves tokens!
- Only use REPLACE_FILE when the majority of the file changes
- For REPLACE_ELEMENT: content = complete element (annotations, modifiers, signature, body)
- For CREATE_ELEMENT: set elementKind (FUNCTION, CLASS, PROPERTY) and position (LAST_CHILD, FIRST_CHILD)
- Use ADD_IMPORT/REMOVE_IMPORT for imports — don't manually edit imports
- "content" must be complete, compilable Kotlin code
- Previously gathered files are listed by path — you already saw them in earlier messages
- DO NOT wrap the JSON in markdown code blocks. Just output raw JSON.
- ALL code MUST go in modifications[].content — never use tools or file creation."""
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
        return textSize / 4  // rough token estimate
    }

    private fun log(message: String) {
        println("[MaxVibes Clipboard] $message")
    }

    private fun error(message: String): ClipboardStepResult.Error {
        log("ERROR: $message")
        return ClipboardStepResult.Error(message)
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
    val dialogHistory: MutableList<ChatMessageDTO>,
    val prompts: PromptTemplates,
    /** Все когда-либо собранные файлы за эту сессию (path → content) */
    val allGatheredFiles: MutableMap<String, String>,
    val attachedContext: String? = null
)