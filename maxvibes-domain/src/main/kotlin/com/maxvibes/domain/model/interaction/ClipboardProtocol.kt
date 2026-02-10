package com.maxvibes.domain.model.interaction

/**
 * Фаза clipboard-протокола. Те же две фазы что и API mode,
 * но данные передаются через JSON-копипаст.
 */
enum class ClipboardPhase {
    /** Фаза 1: задача + file tree → список нужных файлов */
    PLANNING,
    /** Фаза 2: задача + контекст файлов → ответ + модификации */
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
    /** Для PLANNING: fileTree; для CHAT: содержимое файлов */
    val context: Map<String, String>,
    /** История чата (только для CHAT фазы) */
    val chatHistory: List<ClipboardHistoryEntry> = emptyList(),
    /** Системный промпт (вшит в JSON чтобы LLM знал формат ответа) */
    val systemInstruction: String = ""
)

data class ClipboardHistoryEntry(
    val role: String, // "user" | "assistant"
    val content: String
)

/**
 * Ответ от LLM, вставленный пользователем.
 * Парсится MaxVibes из текста.
 */
data class ClipboardResponse(
    val phase: ClipboardPhase,
    val message: String = "",
    /** Для PLANNING: список запрошенных файлов */
    val requestedFiles: List<String> = emptyList(),
    val reasoning: String? = null,
    /** Для CHAT: модификации кода */
    val modifications: List<ClipboardModification> = emptyList()
)

/**
 * Модификация в clipboard-формате.
 */
data class ClipboardModification(
    val type: String,       // CREATE_FILE, REPLACE_FILE, etc.
    val path: String,
    val content: String = "",
    val elementKind: String = "FILE",
    val position: String = "LAST_CHILD"
)