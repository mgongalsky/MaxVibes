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
import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.chat.TokenUsage
import com.maxvibes.plugin.service.MaxVibesLogger
import java.time.Instant
import java.util.UUID
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus

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

    /**
     * File paths requested by the LLM in this ASSISTANT message.
     * Serialized as a nested XCollection — absent in old XML reads as empty list (backward compat).
     */
    @XCollection(style = XCollection.Style.v2, elementTypes = [String::class])
    var requestedFiles: MutableList<String> = mutableListOf()

    constructor()

    constructor(
        id: String,
        role: MessageRole,
        content: String,
        timestamp: Long,
        requestedFiles: List<String> = emptyList()
    ) {
        this.id = id
        this.role = role
        this.content = content
        this.timestamp = timestamp
        this.requestedFiles = requestedFiles.toMutableList()
    }

    /** Converts this XML DTO to the domain [ChatMessage]. */
    fun toDomain(): ChatMessage = ChatMessage(
        id = id,
        role = role,
        content = content,
        timestamp = timestamp,
        requestedFiles = requestedFiles.toList()
    )

    companion object {
        /** Creates an XML DTO from a domain [ChatMessage] for serialization. */
        fun fromDomain(msg: ChatMessage) = XmlChatMessage(
            id = msg.id,
            role = msg.role,
            content = msg.content,
            timestamp = msg.timestamp,
            requestedFiles = msg.requestedFiles
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

    /** Статус clipboard-сессии, сохраняется как строка для надёжной XML-сериализации.
     *  Дефолт "IDLE" обеспечивает backward compatibility со старыми XML без этого поля. */
    @Attribute("clipboardStatus")
    var clipboardStatus: String = "IDLE"

    constructor()

    fun toTokenUsage(): TokenUsage = TokenUsage(
        planningInput = planningInputTokens,
        planningOutput = planningOutputTokens,
        chatInput = chatInputTokens,
        chatOutput = chatOutputTokens
    )

    /** Конвертирует XML-объект в доменную модель ChatSession.
     *  clipboardStatus десериализуется с защитным fallback на IDLE —
     *  на случай если в XML окажется устаревшее/неизвестное значение. */
    fun toDomain(): ChatSession = ChatSession(
        id = id,
        title = title,
        parentId = parentId,
        depth = depth,
        messages = messages.map { it.toDomain() },
        tokenUsage = toTokenUsage(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        clipboardStatus = try {
            ClipboardSessionStatus.valueOf(clipboardStatus)
        } catch (_: IllegalArgumentException) {
            // Защита от неизвестных значений из старых версий плагина
            ClipboardSessionStatus.IDLE
        }
    )

    companion object {
        /** Создаёт XML-объект из доменной модели ChatSession для сериализации.
         *  clipboardStatus сохраняется как строковое имя enum-константы. */
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
            // Сериализуем статус как строку — IntelliJ XML-сериализатор работает с примитивами надёжнее
            xml.clipboardStatus = session.clipboardStatus.name
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
