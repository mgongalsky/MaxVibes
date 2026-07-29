package com.maxvibes.plugin.ui

import com.maxvibes.application.service.ChatTreeService
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.interaction.InteractionMode

/** Routes a completed shell-command batch back into its originating interaction mode. */
internal class CommandResultRouter(
    private val chatTreeService: ChatTreeService,
    private val submitClipboard: (ChatSession, String) -> Unit,
    private val submitClaudeCode: (ChatSession, String) -> Unit,
    private val submitApi: (ChatSession, String) -> Unit,
    private val onMissingSession: () -> Unit
) {
    fun route(sessionId: String, mode: InteractionMode, formatted: String) {
        val session = chatTreeService.getSessionById(sessionId)
        if (session == null) {
            onMissingSession()
            return
        }

        when (mode) {
            InteractionMode.CLIPBOARD -> submitClipboard(session, formatted)
            InteractionMode.CLAUDE_CODE -> submitClaudeCode(session, formatted)
            InteractionMode.API,
            InteractionMode.CHEAP_API -> submitApi(session, formatted)
        }
    }
}
