package com.maxvibes.plugin.chat

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.chat.TokenUsage
import com.maxvibes.plugin.service.MaxVibesLogger
import java.time.Instant
import java.util.UUID

/**
 * XML persistence DTO для сообщения в чате.
 * Использует IntelliJ XML-аннотации и является мутабельным.
 * Для работы с чистым кодом используй доменный ChatMessage через toDomain().
 */
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

/**
 * XML persistence DTO для сессии чата.
 * Хранит данные в XML через IntelliJ-аннотации.
 * Для доменной логики используй toDomain() / fromDomain().
 *
 * ВАЖНО: @Tag("session") обязателен для совместимости с существующими XML-файлами.
 */
@Tag("session")
class XmlChatSession {
    @Attribute("id")
    var id: String = UUID.randomUUID().toString()

    @Attribute("title")
    var title: String = "New Chat"

    /** ID родительской сессии. null = корневая сессия. */
    @Attribute("parentId")
    var parentId: String? = null

    /** Глубина в дереве (0 = root). Вычисляется при загрузке. */
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

/**
 * Состояние для сериализации
 */
class ChatHistoryState {
    @XCollection(style = XCollection.Style.v2)
    var sessions: MutableList<XmlChatSession> = mutableListOf()

    var activeSessionId: String? = null

    /** Global context files — always included in every LLM request (relative paths from project root) */
    @XCollection(style = XCollection.Style.v2, elementTypes = [String::class])
    var globalContextFiles: MutableList<String> = mutableListOf()
}

/**
 * Узел дерева сессий для навигации.
 * Не сериализуется — строится динамически.
 * session — доменный ChatSession (иммутабельный).
 */
data class SessionTreeNode(
    val session: ChatSession,
    val children: MutableList<SessionTreeNode> = mutableListOf()
) {
    val id: String get() = session.id
    val title: String get() = session.title
    val depth: Int get() = session.depth
    val hasChildren: Boolean get() = children.isNotEmpty()
}

/**
 * Сервис хранения истории чатов (per-project).
 * Публичный API работает с доменным ChatSession.
 * Внутри — XmlChatSession для персистентности.
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
        recalculateDepths()
        MaxVibesLogger.info(
            "ChatHistory", "loadState", mapOf(
                "sessions" to state.sessions.size,
                "activeId" to (state.activeSessionId ?: "none"),
                "contextFiles" to state.globalContextFiles.size
            )
        )
    }

    // ==================== Basic Operations ====================

    fun getAllSessions(): List<ChatSession> {
        return state.sessions.map { it.toDomain() }
    }

    fun getSessions(): List<ChatSession> {
        return state.sessions.sortedByDescending { it.updatedAt }.map { it.toDomain() }
    }

    fun getSessionById(id: String): ChatSession? {
        return state.sessions.find { it.id == id }?.toDomain()
    }

    fun getActiveSession(): ChatSession {
        val activeId = state.activeSessionId
        val xmlSession = state.sessions.find { it.id == activeId }
        return xmlSession?.toDomain() ?: createNewSession()
    }

    fun setActiveSession(sessionId: String) {
        state.activeSessionId = sessionId
    }

    /**
     * Сохраняет доменную сессию в состояние (upsert по id).
     */
    fun saveSession(session: ChatSession) {
        val index = state.sessions.indexOfFirst { it.id == session.id }
        val xml = XmlChatSession.fromDomain(session)
        if (index >= 0) state.sessions[index] = xml
        else state.sessions.add(0, xml)
    }

    // ==================== Rename ====================

    /**
     * Переименовывает сессию.
     * @return true если переименование успешно, false если сессия не найдена.
     */
    fun renameSession(sessionId: String, newTitle: String): Boolean {
        val session = state.sessions.find { it.id == sessionId } ?: return false
        session.title = newTitle.trim().ifBlank { "Untitled" }
        session.updatedAt = Instant.now().toEpochMilli()
        return true
    }

    // ==================== Tree Operations ====================

    /** Возвращает корневые сессии (без родителя) */
    fun getRootSessions(): List<ChatSession> {
        return state.sessions
            .filter { it.parentId == null }
            .sortedByDescending { it.updatedAt }
            .map { it.toDomain() }
    }

    /** Возвращает дочерние сессии данной сессии */
    fun getChildren(sessionId: String): List<ChatSession> {
        return state.sessions
            .filter { it.parentId == sessionId }
            .sortedByDescending { it.updatedAt }
            .map { it.toDomain() }
    }

    /** Возвращает родительскую сессию */
    fun getParent(sessionId: String): ChatSession? {
        val session = state.sessions.find { it.id == sessionId } ?: return null
        return session.parentId?.let { getSessionById(it) }
    }

    /** Возвращает путь от корня до данной сессии (breadcrumb) */
    fun getSessionPath(sessionId: String): List<ChatSession> {
        val path = mutableListOf<ChatSession>()
        var currentId: String? = sessionId
        while (currentId != null) {
            val xml = state.sessions.find { it.id == currentId } ?: break
            path.add(0, xml.toDomain())
            currentId = xml.parentId
        }
        return path
    }

    /** Возвращает количество дочерних сессий (прямых) */
    fun getChildCount(sessionId: String): Int {
        return state.sessions.count { it.parentId == sessionId }
    }

    /** Возвращает общее количество потомков (рекурсивно) */
    fun getDescendantCount(sessionId: String): Int {
        val children = state.sessions.filter { it.parentId == sessionId }
        return children.size + children.sumOf { getDescendantCount(it.id) }
    }

    /** Проверяет, является ли сессия корневой */
    fun isRoot(sessionId: String): Boolean {
        return state.sessions.find { it.id == sessionId }?.parentId == null
    }

    /**
     * Строит полное дерево сессий для навигации.
     * Возвращает список корневых узлов с рекурсивно заполненными children.
     */
    fun buildTree(): List<SessionTreeNode> {
        val nodeMap = state.sessions.associate { it.id to SessionTreeNode(it.toDomain()) }.toMutableMap()

        for (xmlSession in state.sessions) {
            val parentId = xmlSession.parentId ?: continue
            nodeMap[parentId]?.children?.add(nodeMap[xmlSession.id]!!)
        }

        for (node in nodeMap.values) {
            node.children.sortByDescending { it.session.updatedAt }
        }

        return nodeMap.values
            .filter { it.session.parentId == null }
            .sortedByDescending { it.session.updatedAt }
    }

    // ==================== Create / Delete ====================

    /** Создаёт новую корневую сессию */
    fun createNewSession(): ChatSession {
        val domainSession = ChatSession(parentId = null, depth = 0)
        state.sessions.add(0, XmlChatSession.fromDomain(domainSession))
        state.activeSessionId = domainSession.id
        trimOldSessions()
        MaxVibesLogger.debug(
            "ChatHistory", "createNewSession", mapOf(
                "id" to domainSession.id,
                "total" to state.sessions.size
            )
        )
        return domainSession
    }

    /** Создаёт дочернюю сессию (ветку) от указанного родителя */
    fun createBranch(parentSessionId: String, branchTitle: String? = null): ChatSession? {
        val parentXml = state.sessions.find { it.id == parentSessionId } ?: return null

        val domainSession = ChatSession(
            parentId = parentSessionId,
            depth = parentXml.depth + 1,
            title = branchTitle ?: "Branch of: ${parentXml.title.take(30)}"
        )
        state.sessions.add(0, XmlChatSession.fromDomain(domainSession))
        state.activeSessionId = domainSession.id

        parentXml.updatedAt = Instant.now().toEpochMilli()

        trimOldSessions()
        return domainSession
    }

    /**
     * Удаляет сессию.
     * Стратегия: дети переподвешиваются к родителю удалённой сессии.
     */
    fun deleteSession(sessionId: String) {
        val session = state.sessions.find { it.id == sessionId } ?: return
        val parentId = session.parentId

        val children = state.sessions.filter { it.parentId == sessionId }
        for (child in children) {
            child.parentId = parentId
            child.depth = (parentId?.let { pid -> state.sessions.find { it.id == pid }?.depth?.plus(1) }) ?: 0
            recalculateChildDepths(child.id)
        }

        state.sessions.removeIf { it.id == sessionId }

        if (state.activeSessionId == sessionId) {
            state.activeSessionId = parentId
                ?: children.firstOrNull()?.id
                        ?: state.sessions.firstOrNull()?.id
        }
    }

    /**
     * Удаляет сессию вместе со всеми потомками (каскадное удаление).
     */
    fun deleteSessionCascade(sessionId: String) {
        val toDelete = collectDescendantIds(sessionId) + sessionId
        val parentId = state.sessions.find { it.id == sessionId }?.parentId
        state.sessions.removeIf { it.id in toDelete }

        if (state.activeSessionId in toDelete) {
            state.activeSessionId = parentId ?: state.sessions.firstOrNull()?.id
        }
    }

    fun clearActiveSession() {
        val session = getActiveSession()
        saveSession(session.cleared())
    }

    // ==================== Global Context Files ====================

    fun getGlobalContextFiles(): List<String> {
        return state.globalContextFiles.toList()
    }

    fun setGlobalContextFiles(files: List<String>) {
        state.globalContextFiles = files.distinct().toMutableList()
    }

    fun addGlobalContextFile(relativePath: String) {
        val normalized = relativePath.replace('\\', '/')
        if (normalized !in state.globalContextFiles) {
            state.globalContextFiles.add(normalized)
        }
    }

    fun removeGlobalContextFile(relativePath: String) {
        state.globalContextFiles.remove(relativePath.replace('\\', '/'))
    }

    // ==================== Internal Helpers ====================

    private fun collectDescendantIds(sessionId: String): Set<String> {
        val result = mutableSetOf<String>()
        val children = state.sessions.filter { it.parentId == sessionId }
        for (child in children) {
            result.add(child.id)
            result.addAll(collectDescendantIds(child.id))
        }
        return result
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

    private fun trimOldSessions() {
        if (state.sessions.size > 100) {
            val leaves = state.sessions
                .filter { s -> state.sessions.none { it.parentId == s.id } }
                .sortedBy { it.updatedAt }
            val toRemove = leaves.take(state.sessions.size - 100)
            state.sessions.removeAll(toRemove.toSet())
        }
    }

    companion object {
        fun getInstance(project: Project): ChatHistoryService {
            return project.getService(ChatHistoryService::class.java)
        }
    }
}
