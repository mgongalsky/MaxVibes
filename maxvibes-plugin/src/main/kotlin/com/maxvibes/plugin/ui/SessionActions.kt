package com.maxvibes.plugin.ui

import com.maxvibes.application.service.ChatTreeService
import com.maxvibes.domain.model.chat.ChatSession

/**
 * Session CRUD and selected-prompt operations previously owned by
 * [ChatMessageController].
 */
internal class SessionActions(
    private val chatTreeService: ChatTreeService,
    private val onSessionChanged: (ChatSession?) -> Unit,
    private val onSessionRenamed: (ChatSession) -> Unit
) {
    fun createNewSession() {
        onSessionChanged(chatTreeService.createNewSession())
    }

    fun deleteCurrentSession(sessionId: String) {
        chatTreeService.deleteSession(sessionId)
        onSessionChanged(chatTreeService.getActiveSession())
    }

    fun renameSession(sessionId: String, newTitle: String) {
        chatTreeService.renameSession(sessionId, newTitle)?.let(onSessionRenamed)
    }

    fun branchSession(parentSessionId: String, title: String) {
        chatTreeService.createBranch(parentSessionId, title)?.let(onSessionChanged)
    }

    fun loadSession(sessionId: String) {
        chatTreeService.setActiveSession(sessionId)
        chatTreeService.getSessionById(sessionId)?.let(onSessionChanged)
    }

    fun selectSpecificPrompt(name: String?) {
        val session = chatTreeService.getActiveSession() ?: return
        val updated = session.withSelectedPrompt(name)
        chatTreeService.saveSession(updated)
        onSessionChanged(updated)
    }
}
