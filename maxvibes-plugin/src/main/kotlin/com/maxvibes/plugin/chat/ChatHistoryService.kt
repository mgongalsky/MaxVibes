package com.maxvibes.plugin.chat

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import java.time.Instant
import java.util.UUID

/**
 * Сообщение в чате
 */
@Tag("message")
class ChatMessage {
    @Attribute("id")
    var id: String = UUID.randomUUID().toString()

    @Attribute("role")
    var role: MessageRole = MessageRole.USER

    @Tag("content")
    var content: String = ""

    @Attribute("timestamp")
    var timestamp: Long = Instant.now().toEpochMilli()

    constructor()

    constructor(id: String, role: MessageRole, content: String, timestamp: Long) {
        this.id = id
        this.role = role
        this.content = content
        this.timestamp = timestamp
    }
}

enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}

/**
 * Сессия чата
 */
@Tag("session")
class ChatSession {
    @Attribute("id")
    var id: String = UUID.randomUUID().toString()

    @Attribute("title")
    var title: String = "New Chat"

    @XCollection(style = XCollection.Style.v2)
    var messages: MutableList<ChatMessage> = mutableListOf()

    @Attribute("createdAt")
    var createdAt: Long = Instant.now().toEpochMilli()

    @Attribute("updatedAt")
    var updatedAt: Long = Instant.now().toEpochMilli()

    constructor()

    constructor(id: String, title: String, messages: MutableList<ChatMessage>, createdAt: Long, updatedAt: Long) {
        this.id = id
        this.title = title
        this.messages = messages
        this.createdAt = createdAt
        this.updatedAt = updatedAt
    }

    fun addMessage(role: MessageRole, content: String): ChatMessage {
        val message = ChatMessage(
            UUID.randomUUID().toString(),
            role,
            content,
            Instant.now().toEpochMilli()
        )
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
    @XCollection(style = XCollection.Style.v2)
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

    fun getSessions(): List<ChatSession> {
        return state.sessions.sortedByDescending { it.updatedAt }
    }

    fun getActiveSession(): ChatSession {
        val activeId = state.activeSessionId
        val session = state.sessions.find { it.id == activeId }
        return session ?: createNewSession()
    }

    fun setActiveSession(sessionId: String) {
        state.activeSessionId = sessionId
    }

    fun createNewSession(): ChatSession {
        val session = ChatSession()
        state.sessions.add(0, session)
        state.activeSessionId = session.id

        if (state.sessions.size > 50) {
            state.sessions = state.sessions.take(50).toMutableList()
        }

        return session
    }

    fun deleteSession(sessionId: String) {
        state.sessions.removeIf { it.id == sessionId }
        if (state.activeSessionId == sessionId) {
            state.activeSessionId = state.sessions.firstOrNull()?.id
        }
    }

    fun clearActiveSession() {
        getActiveSession().clear()
    }

    companion object {
        fun getInstance(project: Project): ChatHistoryService {
            return project.getService(ChatHistoryService::class.java)
        }
    }
}