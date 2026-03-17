package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.TokenUsage
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus

/**
 * Immutable state snapshot passed to [ChatPanel.render].
 *
 * All UI updates in [ChatPanel] are driven exclusively from this snapshot — no component
 * reads service state directly. This is the single source of truth for the View layer.
 *
 * @param currentSession     The currently active chat session; null if none exists.
 * @param sessionPath        Breadcrumb path from the root to the current session.
 * @param mode               Current interaction mode (API / Clipboard / CheapAPI).
 * @param attachedTrace      Pasted stacktrace/log text; null if not attached.
 * @param attachedErrors     IDE error output; null if not attached.
 * @param contextFilesCount  Number of global context files currently configured.
 * @param tokenUsage         Token usage for the current session; null if empty.
 * @param clipboardStatus    Current clipboard dialog status for the active session.
 *                           Drives button labels and mode indicator in Clipboard mode.
 */
data class ChatPanelState(
    val currentSession: ChatSession?,

    /** Путь от корня до текущей сессии (хлебные крошки). */
    val sessionPath: List<ChatSession> = emptyList(),

    /** Текущий режим взаимодействия. */
    val mode: InteractionMode = InteractionMode.API,

    /** Прикреплённый трейс (текст из буфера обмена). Null если не прикреплён. */
    val attachedTrace: String? = null,

    /** Прикреплённые ошибки IDE. Null если не прикреплены. */
    val attachedErrors: String? = null,

    /** Количество файлов контекста. */
    val contextFilesCount: Int = 0,

    /** Использование токенов текущей сессии. */
    val tokenUsage: TokenUsage? = null,

    /**
     * Текущий статус clipboard-диалога активной сессии.
     * Используется в [ChatPanel.updateModeUI] для управления лейблами кнопок и индикатором.
     */
    val clipboardStatus: ClipboardSessionStatus = ClipboardSessionStatus.IDLE
) {
    /** true если есть прикреплённые данные любого типа. */
    val hasAttachments: Boolean get() = attachedTrace != null || attachedErrors != null
}
