package com.maxvibes.plugin.chat

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import java.time.Instant
import java.util.UUID

/**
 * Сообщение в чате
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole = MessageRole.USER,
    val content: String = "",
    val timestamp: Long = Instant.now().toEpochMilli()
)

enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}

/**
 * Сессия чата
 */
data class ChatSession(
    var id: String = UUID.randomUUID().toString(),
    var title: String = "New Chat",
    var messages: MutableList<ChatMessage> = mutableListOf(),
    var createdAt: Long = Instant.now().toEpochMilli(),
    var updatedAt: Long = Instant.now().toEpochMilli()
) {
    fun addMessage(role: MessageRole, content: String): ChatMessage {
        val message = ChatMessage(role = role, content = content)
        messages.add(message)
        updatedAt = Instant.now().toEpochMilli()

        // Auto-title from first user message
        if (title == "New Chat" && role == MessageRole.USER) {
            title = content.take(40) + if (content.length > 40) "..." else ""
        }

        return message
    }

    fun clear() {
        messages.clear()
        updatedAt = Instant.now().toEpochMilli()
    }
}

/**
 * Состояние для сериализации
 */
class ChatHistoryState {
    var sessions: MutableList<ChatSession> = mutableListOf()
    var activeSessionId: String? = null
}

/**
 * Сервис хранения истории чатов (per-project)
 */
@Service(Service.Level.PROJECT)
@State(
    name = "MaxVibesChatHistory",
    storages = [Storage("maxvibes-chat-history.xml")]
)
class ChatHistoryService : PersistentStateComponent<ChatHistoryState> {

    private var state = ChatHistoryState()

    override fun getState(): ChatHistoryState = state

    override fun loadState(state: ChatHistoryState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    /**
     * Получить все сессии (отсортированы по дате обновления)
     */
    fun getSessions(): List<ChatSession> {
        return state.sessions.sortedByDescending { it.updatedAt }
    }

    /**
     * Получить активную сессию (или создать новую)
     */
    fun getActiveSession(): ChatSession {
        val activeId = state.activeSessionId
        val session = state.sessions.find { it.id == activeId }

        return session ?: createNewSession()
    }

    /**
     * Установить активную сессию
     */
    fun setActiveSession(sessionId: String) {
        state.activeSessionId = sessionId
    }

    /**
     * Создать новую сессию
     */
    fun createNewSession(): ChatSession {
        val session = ChatSession()
        state.sessions.add(0, session)
        state.activeSessionId = session.id

        // Лимит на количество сессий (хранить последние 50)
        if (state.sessions.size > 50) {
            state.sessions = state.sessions.take(50).toMutableList()
        }

        return session
    }

    /**
     * Удалить сессию
     */
    fun deleteSession(sessionId: String) {
        state.sessions.removeIf { it.id == sessionId }

        // Если удалили активную — переключаемся на первую
        if (state.activeSessionId == sessionId) {
            state.activeSessionId = state.sessions.firstOrNull()?.id
        }
    }

    /**
     * Очистить текущую сессию
     */
    fun clearActiveSession() {
        getActiveSession().clear()
    }

    companion object {
        fun getInstance(project: Project): ChatHistoryService {
            return project.getService(ChatHistoryService::class.java)
        }
    }
}