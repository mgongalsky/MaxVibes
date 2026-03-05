package com.maxvibes.plugin.chat

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.chat.TokenUsage
import com.maxvibes.plugin.service.MaxVibesLogger
import java.time.Instant
import java.util.UUID

@Tag("message")
class XmlChatMessage {
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

    fun toDomain(): ChatMessage = ChatMessage(
        id = id,
        role = role,
        content = content,
        timestamp = timestamp
    )

    fun toChatMessageDTO(): ChatMessageDTO = ChatMessageDTO(
        role = when (role) {
            MessageRole.USER -> ChatRole.USER
            MessageRole.ASSISTANT -> ChatRole.ASSISTANT
            MessageRole.SYSTEM -> ChatRole.SYSTEM
        },
        content = content
    )

    companion object {
        fun fromDomain(msg: ChatMessage) = XmlChatMessage(
            id = msg.id,
            role = msg.role,
            content = msg.content,
            timestamp = msg.timestamp
        )
    }
}

@Tag("session")
class XmlChatSession {
    @Attribute("id")
    var id: String = UUID.randomUUID().toString()

    @Attribute("title")
    var title: String = "New Chat"

    @Attribute("parentId")
    var parentId: String? = null

    @Attribute("depth")
    var depth: Int = 0

    @XCollection(style = XCollection.Style.v2)
    var messages: MutableList<XmlChatMessage> = mutableListOf()

    @Attribute("createdAt")
    var createdAt: Long = Instant.now().toEpochMilli()

    @Attribute("updatedAt")
    var updatedAt: Long = Instant.now().toEpochMilli()

    @Attribute("planningInputTokens")
    var planningInputTokens: Int = 0

    @Attribute("planningOutputTokens")
    var planningOutputTokens: Int = 0

    @Attribute("chatInputTokens")
    var chatInputTokens: Int = 0

    @Attribute("chatOutputTokens")
    var chatOutputTokens: Int = 0

    constructor()

    fun toTokenUsage(): TokenUsage = TokenUsage(
        planningInput = planningInputTokens,
        planningOutput = planningOutputTokens,
        chatInput = chatInputTokens,
        chatOutput = chatOutputTokens
    )

    fun toDomain(): ChatSession = ChatSession(
        id = id,
        title = title,
        parentId = parentId,
        depth = depth,
        messages = messages.map { it.toDomain() },
        tokenUsage = toTokenUsage(),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(session: ChatSession): XmlChatSession {
            val xml = XmlChatSession()
            xml.id = session.id
            xml.title = session.title
            xml.parentId = session.parentId
            xml.depth = session.depth
            xml.messages = session.messages.map { XmlChatMessage.fromDomain(it) }.toMutableList()
            xml.planningInputTokens = session.tokenUsage.planningInput
            xml.planningOutputTokens = session.tokenUsage.planningOutput
            xml.chatInputTokens = session.tokenUsage.chatInput
            xml.chatOutputTokens = session.tokenUsage.chatOutput
            xml.createdAt = session.createdAt
            xml.updatedAt = session.updatedAt
            return xml
        }
    }
}

class ChatHistoryState {
    @XCollection(style = XCollection.Style.v2)
    var sessions: MutableList<XmlChatSession> = mutableListOf()

    var activeSessionId: String? = null

    @XCollection(style = XCollection.Style.v2, elementTypes = [String::class])
    var globalContextFiles: MutableList<String> = mutableListOf()
}

/**
 * Pure persistence adapter для хранения истории чатов (per-project).
 * Реализует ChatSessionRepository — порт application layer.
 * Вся бизнес-логика дерева сессий находится в ChatTreeService.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "MaxVibesChatHistory",
    storages = [Storage("maxvibes-chat-history.xml")]
)
class ChatHistoryService : PersistentStateComponent<ChatHistoryState>, ChatSessionRepository {

    private var state = ChatHistoryState()

    override fun getState(): ChatHistoryState = state

    override fun loadState(state: ChatHistoryState) {
        XmlSerializerUtil.copyBean(state, this.state)
        recalculateDepths()
        MaxVibesLogger.info(
            "ChatHistory", "loadState", mapOf(
                "sessions" to state.sessions.size,
                "activeId" to (state.activeSessionId ?: "none"),
                "contextFiles" to state.globalContextFiles.size
            )
        )
    }

    override fun getAllSessions(): List<ChatSession> {
        return state.sessions.map { it.toDomain() }
    }

    override fun getSessionById(id: String): ChatSession? {
        return state.sessions.find { it.id == id }?.toDomain()
    }

    override fun getActiveSessionId(): String? = state.activeSessionId

    override fun setActiveSessionId(sessionId: String) {
        state.activeSessionId = sessionId
    }

    override fun saveSession(session: ChatSession) {
        val index = state.sessions.indexOfFirst { it.id == session.id }
        val xml = XmlChatSession.fromDomain(session)
        if (index >= 0) state.sessions[index] = xml
        else state.sessions.add(0, xml)
    }

    override fun deleteSession(sessionId: String) {
        state.sessions.removeIf { it.id == sessionId }
        if (state.activeSessionId == sessionId) {
            state.activeSessionId = state.sessions.firstOrNull()?.id
        }
    }

    override fun getGlobalContextFiles(): List<String> {
        return state.globalContextFiles.toList()
    }

    override fun setGlobalContextFiles(files: List<String>) {
        state.globalContextFiles = files.distinct().toMutableList()
    }

    private fun recalculateChildDepths(sessionId: String) {
        val parent = state.sessions.find { it.id == sessionId } ?: return
        val children = state.sessions.filter { it.parentId == sessionId }
        for (child in children) {
            child.depth = parent.depth + 1
            recalculateChildDepths(child.id)
        }
    }

    private fun recalculateDepths() {
        for (session in state.sessions) {
            if (session.parentId == null) {
                session.depth = 0
            }
        }
        for (session in state.sessions.filter { it.parentId == null }) {
            recalculateChildDepths(session.id)
        }
    }

    companion object {
        fun getInstance(project: Project): ChatHistoryService {
            return project.getService(ChatHistoryService::class.java)
        }
    }
}
