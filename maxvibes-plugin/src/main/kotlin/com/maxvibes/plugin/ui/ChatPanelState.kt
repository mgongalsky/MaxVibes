package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.TokenUsage
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus

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
    val clipboardStatus: ClipboardSessionStatus = ClipboardSessionStatus.IDLE,

    /**
     * List of available specific prompt names for the dropdown.
     * Does NOT include "Just Code" — UI prepends it.
     */
    val availablePrompts: List<String> = emptyList(),

    /**
     * Name of the currently selected specific prompt, or null for "Just Code".
     */
    val selectedSpecificPromptName: String? = null
) {
    /** true если есть прикреплённые данные любого типа. */
    val hasAttachments: Boolean get() = attachedTrace != null || attachedErrors != null
}
