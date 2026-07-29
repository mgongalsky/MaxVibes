package com.maxvibes.application.testsupport

import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.domain.model.chat.ChatSession

/**
 * Deterministic in-memory ChatSessionRepository for application unit tests.
 *
 * Direct setup through put does not count as persistence. Calls through
 * saveSession are recorded in savedSessions in invocation order.
 */
class InMemoryChatSessionRepository(
    initialSessions: List<ChatSession> = emptyList()
) : ChatSessionRepository {
    private val sessions = initialSessions
        .associateBy { it.id }
        .toMutableMap()

    val savedSessions = mutableListOf<ChatSession>()
    val deletedSessionIds = mutableListOf<String>()

    private var activeSessionId: String? = null
    private var globalContextFiles: List<String> = emptyList()

    val lastSavedSession: ChatSession?
        get() = savedSessions.lastOrNull()

    fun put(session: ChatSession) {
        sessions[session.id] = session
    }

    fun clearRecordedOperations() {
        savedSessions.clear()
        deletedSessionIds.clear()
    }

    override fun getAllSessions(): List<ChatSession> =
        sessions.values.toList()

    override fun getSessionById(id: String): ChatSession? =
        sessions[id]

    override fun getActiveSessionId(): String? =
        activeSessionId

    override fun setActiveSessionId(sessionId: String) {
        activeSessionId = sessionId
    }

    override fun saveSession(session: ChatSession) {
        sessions[session.id] = session
        savedSessions += session
    }

    override fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
        deletedSessionIds += sessionId
    }

    override fun getGlobalContextFiles(): List<String> =
        globalContextFiles

    override fun setGlobalContextFiles(files: List<String>) {
        globalContextFiles = files.toList()
    }
}
