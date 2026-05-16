package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.TokenUsage
import com.maxvibes.domain.model.interaction.ClaudeCodeActivity
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionMode

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
    val selectedSpecificPromptName: String? = null,

    /**
     * True when the current mode is [InteractionMode.CLAUDE_CODE] AND status is
     * [ClipboardSessionStatus.AWAITING_APPROVE]. Drives visibility of the Approve button.
     */
    val claudeCodeApproveVisible: Boolean = false,

    /**
     * True when a Claude Code send is in flight. Reserved for future use to disable
     * Send and Approve while a request is being processed; current implementation
     * relies on [ChatPanel.setInputEnabled] for the same effect.
     */
    val claudeCodeSending: Boolean = false,

    /**
     * Transient live-activity event from the Claude Code transport for the active
     * session, or null when no send is in flight. Drives the [LiveActivityBubble]
     * shown beneath the conversation panel. Never persisted — survives only as long
     * as the underlying `ClaudeCodeActivityTracker` holds it (cleared on send completion).
     */
    val liveActivity: ClaudeCodeActivity? = null
) {
    /** true если есть прикреплённые данные любого типа. */
    val hasAttachments: Boolean get() = attachedTrace != null || attachedErrors != null
}
