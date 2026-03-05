package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.chat.SessionTreeNode

class ChatTreeService(private val repository: ChatSessionRepository) {

    companion object {
        private const val MAX_SESSIONS = 100
    }

    // ==================== Active Session ====================

    fun getActiveSession(): ChatSession {
        val id = repository.getActiveSessionId()
        return if (id != null) repository.getSessionById(id) ?: createNewSession()
        else createNewSession()
    }

    fun setActiveSession(sessionId: String) {
        repository.setActiveSessionId(sessionId)
    }

    // ==================== Retrieval ====================

    fun getAllSessions(): List<ChatSession> = repository.getAllSessions()

    fun getSessionById(id: String): ChatSession? = repository.getSessionById(id)

    fun getSessions(): List<ChatSession> =
        repository.getAllSessions().sortedByDescending { it.updatedAt }

    fun getRootSessions(): List<ChatSession> =
        repository.getAllSessions().filter { it.isRoot }.sortedByDescending { it.updatedAt }

    fun getChildren(sessionId: String): List<ChatSession> =
        repository.getAllSessions()
            .filter { it.parentId == sessionId }
            .sortedByDescending { it.updatedAt }

    fun getParent(sessionId: String): ChatSession? {
        val session = repository.getSessionById(sessionId) ?: return null
        return session.parentId?.let { repository.getSessionById(it) }
    }

    fun getSessionPath(sessionId: String): List<ChatSession> {
        val path = mutableListOf<ChatSession>()
        var current = repository.getSessionById(sessionId)
        while (current != null) {
            path.add(0, current)
            current = current.parentId?.let { repository.getSessionById(it) }
        }
        return path
    }

    fun getChildCount(sessionId: String): Int =
        repository.getAllSessions().count { it.parentId == sessionId }

    fun getDescendantCount(sessionId: String): Int {
        val children = getChildren(sessionId)
        return children.size + children.sumOf { getDescendantCount(it.id) }
    }

    fun buildTree(): List<SessionTreeNode> {
        val all = repository.getAllSessions()
        fun buildNode(session: ChatSession): SessionTreeNode {
            val children = all
                .filter { it.parentId == session.id }
                .sortedByDescending { it.updatedAt }
                .map { buildNode(it) }
            return SessionTreeNode(session, children)
        }
        return all.filter { it.isRoot }.sortedByDescending { it.updatedAt }.map { buildNode(it) }
    }

    // ==================== Mutations ====================

    fun createNewSession(): ChatSession {
        val session = ChatSession()
        repository.saveSession(session)
        repository.setActiveSessionId(session.id)
        trimOldSessions()
        return session
    }

    fun createBranch(parentSessionId: String, branchTitle: String? = null): ChatSession? {
        val parent = repository.getSessionById(parentSessionId) ?: return null
        val session = ChatSession(
            parentId = parentSessionId,
            depth = parent.depth + 1,
            title = branchTitle ?: "Branch of: ${parent.title.take(30)}"
        )
        repository.saveSession(session)
        repository.setActiveSessionId(session.id)
        repository.saveSession(parent.touch())
        trimOldSessions()
        return session
    }

    fun renameSession(sessionId: String, newTitle: String): ChatSession? {
        val session = repository.getSessionById(sessionId) ?: return null
        val updated = session.withTitle(newTitle)
        repository.saveSession(updated)
        return updated
    }

    fun addMessage(sessionId: String, role: MessageRole, content: String): ChatSession {
        val session = repository.getSessionById(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")
        val message = ChatMessage(role = role, content = content)
        val updated = session.withMessage(message)
        repository.saveSession(updated)
        return updated
    }

    fun addPlanningTokens(sessionId: String, input: Int, output: Int): ChatSession {
        val session = repository.getSessionById(sessionId) ?: return ChatSession(id = sessionId)
        val updated = session.addPlanningTokens(input, output)
        repository.saveSession(updated)
        return updated
    }

    fun addChatTokens(sessionId: String, input: Int, output: Int): ChatSession {
        val session = repository.getSessionById(sessionId) ?: return ChatSession(id = sessionId)
        val updated = session.addChatTokens(input, output)
        repository.saveSession(updated)
        return updated
    }

    fun clearSession(sessionId: String): ChatSession? {
        val session = repository.getSessionById(sessionId) ?: return null
        val cleared = session.cleared()
        repository.saveSession(cleared)
        return cleared
    }

    /**
     * Удаляет сессию. Дети переподвешиваются к родителю удалённой сессии.
     */
    fun deleteSession(sessionId: String) {
        val session = repository.getSessionById(sessionId) ?: return
        val parentId = session.parentId
        val parentDepth = parentId?.let { repository.getSessionById(it)?.depth } ?: -1

        val children = getChildren(sessionId)
        for (child in children) {
            val updatedChild = child.withParent(parentId, parentDepth + 1)
            repository.saveSession(updatedChild)
            recalculateChildDepths(child.id, parentDepth + 1)
        }

        repository.deleteSession(sessionId)

        if (repository.getActiveSessionId() == sessionId) {
            val newActive = parentId
                ?: children.firstOrNull()?.id
                ?: repository.getAllSessions().firstOrNull()?.id
            if (newActive != null) repository.setActiveSessionId(newActive)
        }
    }

    /**
     * Удаляет сессию вместе со всеми потомками.
     */
    fun deleteSessionCascade(sessionId: String) {
        val toDelete = collectDescendantIds(sessionId) + sessionId
        val activeId = repository.getActiveSessionId()

        toDelete.forEach { repository.deleteSession(it) }

        if (activeId in toDelete) {
            val parentId = repository.getSessionById(sessionId)?.parentId
            val newActive = parentId ?: repository.getAllSessions().firstOrNull()?.id
            if (newActive != null) repository.setActiveSessionId(newActive)
        }
    }

    // ==================== Context Files ====================

    fun getGlobalContextFiles(): List<String> = repository.getGlobalContextFiles()

    fun setGlobalContextFiles(files: List<String>) = repository.setGlobalContextFiles(files)

    fun addGlobalContextFile(relativePath: String) {
        val normalized = relativePath.replace('\\', '/')
        val current = repository.getGlobalContextFiles()
        if (normalized !in current) {
            repository.setGlobalContextFiles(current + normalized)
        }
    }

    fun removeGlobalContextFile(relativePath: String) {
        val normalized = relativePath.replace('\\', '/')
        repository.setGlobalContextFiles(repository.getGlobalContextFiles() - normalized)
    }

    // ==================== Private Helpers ====================

    private fun collectDescendantIds(sessionId: String): Set<String> {
        val result = mutableSetOf<String>()
        val children = getChildren(sessionId)
        for (child in children) {
            result.add(child.id)
            result.addAll(collectDescendantIds(child.id))
        }
        return result
    }

    private fun recalculateChildDepths(sessionId: String, parentDepth: Int) {
        val children = getChildren(sessionId)
        for (child in children) {
            val updated = child.withDepth(parentDepth + 1)
            repository.saveSession(updated)
            recalculateChildDepths(child.id, parentDepth + 1)
        }
    }

    private fun trimOldSessions() {
        val all = repository.getAllSessions()
        if (all.size <= MAX_SESSIONS) return
        val leaves = all
            .filter { s -> all.none { it.parentId == s.id } }
            .sortedBy { it.updatedAt }
        val toRemove = leaves.take(all.size - MAX_SESSIONS)
        toRemove.forEach { repository.deleteSession(it.id) }
    }
}
