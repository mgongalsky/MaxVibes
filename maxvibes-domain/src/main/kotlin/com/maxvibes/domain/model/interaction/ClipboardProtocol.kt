package com.maxvibes.domain.model.interaction

import com.maxvibes.domain.model.code.CodeViewRequest

/**
 * Фаза clipboard-протокола — используется внутренне для трекинга.
 */
enum class ClipboardPhase {
    /** Фаза 1: задача + file tree -> список нужных файлов */
    PLANNING,

    /** Фаза 2+: задача + контекст файлов -> ответ + модификации */
    CHAT
}

data class ClipboardRequest(
    val phase: ClipboardPhase,
    /** Текущее сообщение пользователя в этом ходу диалога. */
    val currentMessage: String,
    val projectName: String,
    /** Системный промпт (формат ответа) */
    val systemInstruction: String = "",
    /** Файловое дерево проекта (всегда включено) */
    val fileTree: String = "",
    /** Полное содержимое запрошенных файлов (свежезапрошенные) */
    val freshFiles: Map<String, String> = emptyMap(),
    /** Пути ранее собранных файлов (для контекста, без содержимого) */
    val previouslyGatheredPaths: List<String> = emptyList(),
    /** История чата (полная) */
    val chatHistory: List<ClipboardHistoryEntry> = emptyList(),
    /** Дополнительный контекст (ошибки, трейсы) */
    val attachedContext: String? = null,
    /** Ошибки компиляции (из IDE) */
    val ideErrors: String? = null,
    /** Plan-only mode: discussion without code modifications */
    val planOnly: Boolean = false,
    /**
     * Optional task-scoped prompt injected alongside the system instruction.
     * Null when "Just Code" mode is active (no specific prompt selected).
     */
    val specificPrompt: String? = null
)

data class ClipboardHistoryEntry(
    val role: String, // "user" | "assistant"
    val content: String
)

data class ClipboardResponse(
    /** Текстовое сообщение пользователю (обязательно рекомендуется). */
    val message: String = "",
    /** Обоснование или пояснение от LLM. */
    val reasoning: String? = null,
    /**
     * Запрошенные файлы для следующего шага (legacy — для обратной совместимости).
     * Новый код должен использовать [codeViewRequests].
     */
    val requestedFiles: List<String> = emptyList(),
    /**
     * Структурированные запросы файлов с контролем гранулярности.
     *
     * Объединяет [requestedFiles] (превращаются в [com.maxvibes.domain.model.code.CodeGranularity.FULL])
     * и новое поле `requestedViews` из JSON-ответа LLM.
     * При совпадении пути элемент из `requestedViews` побеждает.
     */
    val codeViewRequests: List<CodeViewRequest> = emptyList(),
    /** Модификации кода. */
    val modifications: List<ClipboardModification> = emptyList(),
    /** Сгенерированный commit message — плагин автоматически вставит его в поле коммита в IDE. */
    val commitMessage: String? = null
)

/**
 * Модификация в clipboard-формате.
 */
data class ClipboardModification(
    val type: String,       // CREATE_FILE, REPLACE_FILE, REPLACE_ELEMENT, CREATE_ELEMENT, DELETE_ELEMENT, ADD_IMPORT, REMOVE_IMPORT
    val path: String,
    val content: String = "",
    val elementKind: String = "FILE",
    val position: String = "LAST_CHILD",
    /** For ADD_IMPORT/REMOVE_IMPORT: fully qualified import path, e.g. "com.example.dto.UserDTO" */
    val importPath: String = ""
)
