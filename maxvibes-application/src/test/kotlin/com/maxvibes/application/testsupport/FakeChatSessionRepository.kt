package com.maxvibes.application.testsupport

import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.domain.model.chat.ChatSession

/** In-memory [ChatSessionRepository] — backs the real [com.maxvibes.application.service.ClipboardSessionManager] in tests. */
class FakeChatSessionRepository : ChatSessionRepository {

    private val sessions = linkedMapOf<String, ChatSession>()
    private var activeSessionId: String? = null
    private var globalContextFiles: List<String> = emptyList()

    override fun getAllSessions(): List<ChatSession> = sessions.values.toList()

    override fun getSessionById(id: String): ChatSession? = sessions[id]

    override fun getActiveSessionId(): String? = activeSessionId

    override fun setActiveSessionId(sessionId: String) {
        activeSessionId = sessionId
    }

    override fun saveSession(session: ChatSession) {
        sessions[session.id] = session
    }

    override fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    override fun getGlobalContextFiles(): List<String> = globalContextFiles

    override fun setGlobalContextFiles(files: List<String>) {
        globalContextFiles = files
    }
}
