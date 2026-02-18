package com.maxvibes.domain.model.interaction

/**
 * Фаза clipboard-протокола — используется внутренне для трекинга.
 */
enum class ClipboardPhase {
    /** Фаза 1: задача + file tree → список нужных файлов */
    PLANNING,
    /** Фаза 2+: задача + контекст файлов → ответ + модификации */
    CHAT
}

/**
 * Запрос для копирования в чат LLM.
 * Генерируется MaxVibes, копируется пользователем.
 */
data class ClipboardRequest(
    val phase: ClipboardPhase,
    val task: String,
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
    val attachedContext: String? = null
)

data class ClipboardHistoryEntry(
    val role: String, // "user" | "assistant"
    val content: String
)

/**
 * Единый ответ от LLM. Все поля опциональны.
 *
 * Claude может комбинировать любые поля в одном ответе:
 * - message only → чистый диалог (обсуждение, планы)
 * - message + requestedFiles → нужны ещё файлы
 * - message + modifications → код + объяснение
 * - message + requestedFiles + modifications → всё сразу
 */
data class ClipboardResponse(
    /** Текстовое сообщение пользователю (обязательно рекомендуется) */
    val message: String = "",
    /** Запрошенные файлы для следующего шага */
    val requestedFiles: List<String> = emptyList(),
    /** Объяснение выбора файлов / рассуждение */
    val reasoning: String? = null,
    /** Модификации кода */
    val modifications: List<ClipboardModification> = emptyList()
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