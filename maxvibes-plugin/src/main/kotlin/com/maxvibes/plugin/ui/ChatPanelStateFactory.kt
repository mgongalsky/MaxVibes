package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionMode

/** Builds immutable render state from the current application snapshot. */
class ChatPanelStateFactory(
    private val activeSession: () -> ChatSession,
    private val sessionPath: (String) -> List<ChatSession>,
    private val currentMode: () -> InteractionMode,
    private val attachedTrace: () -> String?,
    private val attachedErrors: () -> String?,
    private val contextFilesCount: () -> Int,
    private val availablePrompts: () -> List<String>,
    private val validatePromptName: (String?) -> String?
) {
    fun build(): ChatPanelState {
        val session = activeSession()
        val mode = currentMode()
        return ChatPanelState(
            currentSession = session,
            sessionPath = sessionPath(session.id),
            mode = mode,
            attachedTrace = attachedTrace(),
            attachedErrors = attachedErrors(),
            contextFilesCount = contextFilesCount(),
            tokenUsage = session.tokenUsage.takeIf { !it.isEmpty() },
            clipboardStatus = session.clipboardStatus,
            availablePrompts = availablePrompts(),
            selectedSpecificPromptName = validatePromptName(session.selectedSpecificPromptName),
            claudeCodeApproveVisible = mode == InteractionMode.CLAUDE_CODE &&
                    session.clipboardStatus == ClipboardSessionStatus.AWAITING_APPROVE,
            claudeCodeSending = false,
            plan = session.plan
        )
    }
}
