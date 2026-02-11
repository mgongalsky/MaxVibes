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
 * Сессия чата с поддержкой иерархии (дерево диалогов).
 *
 * parentId == null означает корневую сессию (root).
 * Дети вычисляются динамически через ChatHistoryService.getChildren().
 */
@Tag("session")
class ChatSession {
    @Attribute("id")
    var id: String = UUID.randomUUID().toString()

    @Attribute("title")
    var title: String = "New Chat"

    /** ID родительской сессии. null = корневая сессия. */
    @Attribute("parentId")
    var parentId: String? = null

    /** Глубина в дереве (0 = root). Вычисляется при загрузке, для удобства. */
    @Attribute("depth")
    var depth: Int = 0

    @XCollection(style = XCollection.Style.v2)
    var messages: MutableList<ChatMessage> = mutableListOf()

    @Attribute("createdAt")
    var createdAt: Long = Instant.now().toEpochMilli()

    @Attribute("updatedAt")
    var updatedAt: Long = Instant.now().toEpochMilli()

    constructor()

    constructor(
        id: String,
        title: String,
        messages: MutableList<ChatMessage>,
        createdAt: Long,
        updatedAt: Long,
        parentId: String? = null,
        depth: Int = 0
    ) {
        this.id = id
        this.title = title
        this.messages = messages
        this.createdAt = createdAt
        this.updatedAt = updatedAt
        this.parentId = parentId
        this.depth = depth
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

    /** Global context files — always included in every LLM request (relative paths from project root) */
    @XCollection(style = XCollection.Style.v2, elementTypes = [String::class])
    var globalContextFiles: MutableList<String> = mutableListOf()
}

/**
 * Узел дерева сессий для навигации.
 * Не сериализуется — строится динамически.
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
 * Поддерживает иерархию сессий (дерево диалогов).
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
    }

    // ==================== Basic Operations ====================

    fun getAllSessions(): List<ChatSession> {
        return state.sessions.toList()
    }

    fun getSessions(): List<ChatSession> {
        return state.sessions.sortedByDescending { it.updatedAt }
    }

    fun getSessionById(id: String): ChatSession? {
        return state.sessions.find { it.id == id }
    }

    fun getActiveSession(): ChatSession {
        val activeId = state.activeSessionId
        val session = state.sessions.find { it.id == activeId }
        return session ?: createNewSession()
    }

    fun setActiveSession(sessionId: String) {
        state.activeSessionId = sessionId
    }

    // ==================== Rename ====================

    /**
     * Переименовывает сессию.
     * @return true если переименование успешно, false если сессия не найдена.
     */
    fun renameSession(sessionId: String, newTitle: String): Boolean {
        val session = getSessionById(sessionId) ?: return false
        session.title = newTitle.trim().ifBlank { "Untitled" }
        session.updatedAt = java.time.Instant.now().toEpochMilli()
        return true
    }

    // ==================== Tree Operations ====================

    /** Возвращает корневые сессии (без родителя) */
    fun getRootSessions(): List<ChatSession> {
        return state.sessions
            .filter { it.parentId == null }
            .sortedByDescending { it.updatedAt }
    }

    /** Возвращает дочерние сессии данной сессии */
    fun getChildren(sessionId: String): List<ChatSession> {
        return state.sessions
            .filter { it.parentId == sessionId }
            .sortedByDescending { it.updatedAt }
    }

    /** Возвращает родительскую сессию */
    fun getParent(sessionId: String): ChatSession? {
        val session = getSessionById(sessionId) ?: return null
        return session.parentId?.let { getSessionById(it) }
    }

    /** Возвращает путь от корня до данной сессии (breadcrumb) */
    fun getSessionPath(sessionId: String): List<ChatSession> {
        val path = mutableListOf<ChatSession>()
        var current = getSessionById(sessionId)
        while (current != null) {
            path.add(0, current)
            current = current.parentId?.let { getSessionById(it) }
        }
        return path
    }

    /** Возвращает количество дочерних сессий (прямых) */
    fun getChildCount(sessionId: String): Int {
        return state.sessions.count { it.parentId == sessionId }
    }

    /** Возвращает общее количество потомков (рекурсивно) */
    fun getDescendantCount(sessionId: String): Int {
        val children = getChildren(sessionId)
        return children.size + children.sumOf { getDescendantCount(it.id) }
    }

    /** Проверяет, является ли сессия корневой */
    fun isRoot(sessionId: String): Boolean {
        return getSessionById(sessionId)?.parentId == null
    }

    /**
     * Строит полное дерево сессий для навигации.
     * Возвращает список корневых узлов с рекурсивно заполненными children.
     */
    fun buildTree(): List<SessionTreeNode> {
        val nodeMap = state.sessions.associate { it.id to SessionTreeNode(it) }.toMutableMap()

        // Привязываем детей к родителям
        for (session in state.sessions) {
            val parentId = session.parentId ?: continue
            nodeMap[parentId]?.children?.add(nodeMap[session.id]!!)
        }

        // Сортируем детей по updatedAt desc
        for (node in nodeMap.values) {
            node.children.sortByDescending { it.session.updatedAt }
        }

        // Возвращаем корневые узлы
        return nodeMap.values
            .filter { it.session.parentId == null }
            .sortedByDescending { it.session.updatedAt }
    }

    // ==================== Create / Delete ====================

    /** Создаёт новую корневую сессию */
    fun createNewSession(): ChatSession {
        val session = ChatSession().apply {
            parentId = null
            depth = 0
        }
        state.sessions.add(0, session)
        state.activeSessionId = session.id
        trimOldSessions()
        return session
    }

    /** Создаёт дочернюю сессию (ветку) от указанного родителя */
    fun createBranch(parentSessionId: String, branchTitle: String? = null): ChatSession? {
        val parent = getSessionById(parentSessionId) ?: return null

        val session = ChatSession().apply {
            parentId = parentSessionId
            depth = parent.depth + 1
            title = branchTitle ?: "Branch of: ${parent.title.take(30)}"
        }
        state.sessions.add(0, session)
        state.activeSessionId = session.id

        // Обновляем updatedAt родителя, чтобы он поднялся в списке
        parent.updatedAt = java.time.Instant.now().toEpochMilli()

        trimOldSessions()
        return session
    }

    /**
     * Удаляет сессию.
     * Стратегия: дети переподвешиваются к родителю удалённой сессии.
     * Если удаляется корневая — дети становятся корневыми.
     */
    fun deleteSession(sessionId: String) {
        val session = getSessionById(sessionId) ?: return
        val parentId = session.parentId

        // Переподвешиваем детей
        val children = getChildren(sessionId)
        for (child in children) {
            child.parentId = parentId
            child.depth = (parentId?.let { getSessionById(it)?.depth?.plus(1) }) ?: 0
            recalculateChildDepths(child.id)
        }

        // Удаляем сессию
        state.sessions.removeIf { it.id == sessionId }

        // Если удалили активную — переключаемся
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
        state.sessions.removeIf { it.id in toDelete }

        if (state.activeSessionId in toDelete) {
            val parent = getSessionById(sessionId)?.parentId
            state.activeSessionId = parent ?: state.sessions.firstOrNull()?.id
        }
    }

    fun clearActiveSession() {
        getActiveSession().clear()
    }

    // ==================== Global Context Files ====================

    /** Get the list of global context files */
    fun getGlobalContextFiles(): List<String> {
        return state.globalContextFiles.toList()
    }

    /** Set the list of global context files */
    fun setGlobalContextFiles(files: List<String>) {
        state.globalContextFiles = files.distinct().toMutableList()
    }

    /** Add a global context file (if not already present) */
    fun addGlobalContextFile(relativePath: String) {
        val normalized = relativePath.replace('\\', '/')
        if (normalized !in state.globalContextFiles) {
            state.globalContextFiles.add(normalized)
        }
    }

    /** Remove a global context file */
    fun removeGlobalContextFile(relativePath: String) {
        state.globalContextFiles.remove(relativePath.replace('\\', '/'))
    }

    // ==================== Internal Helpers ====================

    /** Собирает все ID потомков рекурсивно */
    private fun collectDescendantIds(sessionId: String): Set<String> {
        val result = mutableSetOf<String>()
        val children = getChildren(sessionId)
        for (child in children) {
            result.add(child.id)
            result.addAll(collectDescendantIds(child.id))
        }
        return result
    }

    /** Пересчитывает depth для всех потомков данной сессии */
    private fun recalculateChildDepths(sessionId: String) {
        val parent = getSessionById(sessionId) ?: return
        val children = getChildren(sessionId)
        for (child in children) {
            child.depth = parent.depth + 1
            recalculateChildDepths(child.id)
        }
    }

    /** Пересчитывает depth для всех сессий (вызывается при загрузке) */
    private fun recalculateDepths() {
        // Сначала все корневые
        for (session in state.sessions) {
            if (session.parentId == null) {
                session.depth = 0
            }
        }
        // Затем рекурсивно от корней
        for (session in state.sessions.filter { it.parentId == null }) {
            recalculateChildDepths(session.id)
        }
    }

    /** Обрезаем старые сессии если их слишком много (сохраняя иерархию) */
    private fun trimOldSessions() {
        if (state.sessions.size > 100) {
            // Удаляем самые старые листовые сессии (без детей)
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