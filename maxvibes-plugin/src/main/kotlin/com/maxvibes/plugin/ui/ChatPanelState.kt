package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.TokenUsage
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus

data class ChatPanelState(
    /** Текущая активная сессия. Null если сессий нет. */
    val currentSession: ChatSession?,

    /** Путь от корня до текущей сессии (хлебные крошки). */
    val sessionPath: List<ChatSession> = emptyList(),

    /** Текущий режим взаимодействия. */
    val mode: InteractionMode = InteractionMode.API,

    /**
     * true пока идёт запрос к LLM — блокирует кнопку отправки.
     * Deprecated: будет удалено в STEP 8; используйте [clipboardStatus] напрямую.
     */
    val isWaitingResponse: Boolean = false,

    /** Прикреплённый трейс (текст из буфера обмена). Null если не прикреплён. */
    val attachedTrace: String? = null,

    /** Прикреплённые ошибки IDE. Null если не прикреплены. */
    val attachedErrors: String? = null,

    /** Количество файлов контекста. */
    val contextFilesCount: Int = 0,

    /** Использование токенов текущей сессии. */
    val tokenUsage: TokenUsage? = null,

    /** Текущий статус clipboard-диалога активной сессии. */
    val clipboardStatus: ClipboardSessionStatus = ClipboardSessionStatus.IDLE
) {
    /** true если есть прикреплённые данные любого типа. */
    val hasAttachments: Boolean get() = attachedTrace != null || attachedErrors != null
}
