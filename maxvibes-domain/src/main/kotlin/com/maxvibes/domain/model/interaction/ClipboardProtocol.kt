package com.maxvibes.domain.model.interaction

import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.domain.model.planning.TaskPlan

/**
 * Фаза clipboard-протокола — используется внутренне для трекинга.
 */
enum class InteractionPhase {
    /** Фаза 1: задача + file tree -> список нужных файлов */
    PLANNING,

    /** Фаза 2+: задача + контекст файлов -> ответ + модификации */
    CHAT
}

data class ClipboardRequest(
    val phase: InteractionPhase,
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
    val chatHistory: List<InteractionHistoryEntry> = emptyList(),
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
    val specificPrompt: String? = null,

    /** Результаты shell-команд предыдущего хода (уже отформатированные для LLM). */
    val commandResults: String? = null,

    /** Attached images (screenshots) — one-shot, sent with this message only. Claude Code transport only. */
    val attachedImages: List<AttachedImage> = emptyList(),

    /**
     * Актуальное состояние плана сессии (planner panel), включая ручные toggles юзера.
     * Null — у сессии нет плана; поле в JSON тогда опускается.
     */
    val currentPlan: TaskPlan? = null
)

data class InteractionHistoryEntry(
    val role: String, // "user" | "assistant"
    val content: String
)

data class InteractionResponse(
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
    val modifications: List<InteractionModification> = emptyList(),
    /** Сгенерированный commit message — плагин автоматически вставит его в поле коммита в IDE. */
    val commitMessage: String? = null,

    /** Shell-команды, запрошенные LLM (last resort). Выполняются после подтверждения пользователем. */
    val commands: List<InteractionCommand> = emptyList(),

    /**
     * Questions the LLM asks the user before proceeding. When non-empty the turn
     * ends awaiting the user's answers, which arrive as the next regular message.
     * Mutually exclusive with [modifications] and [codeViewRequests] per protocol.
     */
    val questions: List<InteractionQuestion> = emptyList(),

    /**
     * Snapshot плана задачи от LLM (planner panel).
     * Null — поле отсутствовало в ответе, план сессии не меняется.
     * Non-null с пустым [TaskPlan.steps] — маркер очистки плана (обрабатывается сервисом).
     */
    val plan: TaskPlan? = null
)

/**
 * Модификация в clipboard-формате.
 */
data class InteractionModification(
    val type: String,       // CREATE_FILE, REPLACE_FILE, REPLACE_ELEMENT, CREATE_ELEMENT, DELETE_ELEMENT, ADD_IMPORT, REMOVE_IMPORT, RENAME_ELEMENT, SAFE_DELETE, MOVE_ELEMENT
    val path: String,
    val content: String = "",
    val elementKind: String = "FILE",
    val position: String = "LAST_CHILD",
    /** For ADD_IMPORT/REMOVE_IMPORT: fully qualified import path, e.g. "com.example.dto.UserDTO" */
    val importPath: String = "",
    /** For RENAME_ELEMENT: the new element name. */
    val newName: String = "",
    /** For MOVE_ELEMENT: project-relative destination directory, e.g. "src/main/kotlin/com/example/util". */
    val destination: String = ""
)

/**
 * Shell-команда в clipboard-формате (сырой вид из JSON-ответа LLM).
 * Конвертируется в доменный CommandRequest на этапе обработки.
 */
data class InteractionCommand(
    val command: String,
    val reason: String = "",
    val timeoutSec: Int = 120
)

/**
 * A question from the LLM to the user (structured `questions` channel).
 * Rendered in the chat with tappable options; the chosen answer is sent
 * back as a regular user message in the next turn.
 */
data class InteractionQuestion(
    val id: String,
    val question: String,
    /** 2-4 short answer options. May be empty for a free-form question. */
    val options: List<String> = emptyList()
)

/**
 * An image attached to a user message. Travels as an Anthropic image content block
 * next to the protocol JSON — never inside it.
 */
data class AttachedImage(
    /** MIME type: image/png or image/jpeg. */
    val mediaType: String,
    /** Base64 payload without the data: prefix. */
    val base64Data: String
)