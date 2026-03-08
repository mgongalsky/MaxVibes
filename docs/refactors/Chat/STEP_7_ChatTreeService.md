# Шаг 7: Создать `ChatTreeService` в application layer

## Контекст

Это главный шаг всей миграции.Вся бизнес -логика дерева сессий(
    создание,
    ветвление,
    навигация,
    удаление
) переезжает из `ChatHistoryService` в `ChatTreeService` — чистый сервис в application layer без каких -либо зависимостей на IntelliJ .

После этого шага логику чатов можно тестировать без запуска IntelliJ .

**Предварительные условия : * * Шаги 1–6 выполнены . `InMemoryChatSessionRepository` из шага 6 доступен для тестов .

## Что нужно сделать

### 1.Создать `ChatTreeService`

**Путь:** `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ChatTreeService.kt`

```kotlin
package com.maxvibes.application.service

import com . maxvibes . application . port . output . ChatSessionRepository
        import com . maxvibes . domain . model . chat . *
        import java . time . Instant
        import java . util . UUID

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
        repository.saveSession(parent.touch())  // поднимаем родителя в списке
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

        // Переподвешиваем детей
        val children = getChildren(sessionId)
        for (child in children) {
            val updatedChild = child.withParent(parentId, parentDepth + 1)
            repository.saveSession(updatedChild)
            recalculateChildDepths(child.id, parentDepth + 1)
        }

        repository.deleteSession(sessionId)

        // Если удалили активную — переключаемся
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
```

### 2.Добавить в `MaxVibesService`

В `MaxVibesService.kt` добавить:

```kotlin
val chatSessionRepository: ChatSessionRepository by lazy {
    ChatHistoryService.getInstance(project)
}

val chatTreeService: ChatTreeService by lazy {
    ChatTreeService(chatSessionRepository)
}
```

### 3.НЕ переключать UI пока

        UI(
            `ChatPanel`,
            `SessionTreePanel` и т.д.
        ) продолжают использовать `ChatHistoryService` напрямую.Это произойдёт на шаге 8.

## Проверка после реализации

### Компиляция
```
./gradlew compileKotlin
```

### Unit тесты — самые важные во всей миграции !

Создать: `maxvibes-application/src/test/kotlin/com/maxvibes/application/service/ChatTreeServiceTest.kt`

Используй `InMemoryChatSessionRepository` из шага 6.

```kotlin
class ChatTreeServiceTest {
    private lateinit var repo: InMemoryChatSessionRepository
    private lateinit var service: ChatTreeService

    @BeforeEach
    fun setup() {
        repo = InMemoryChatSessionRepository()
        service = ChatTreeService(repo)
    }

    // --- createNewSession ---

    @Test
    fun `createNewSession creates root session`() {
        val session = service.createNewSession()
        assertTrue(session.isRoot)
        assertEquals(0, session.depth)
        assertNotNull(repo.getSessionById(session.id))
    }

    @Test
    fun `createNewSession sets it as active`() {
        val session = service.createNewSession()
        assertEquals(session.id, repo.getActiveSessionId())
    }

    @Test
    fun `getActiveSession creates new session when none exists`() {
        val session = service.getActiveSession()
        assertNotNull(session)
    }

    @Test
    fun `getActiveSession returns existing active session`() {
        val created = service.createNewSession()
        val active = service.getActiveSession()
        assertEquals(created.id, active.id)
    }

    // --- createBranch ---

    @Test
    fun `createBranch creates child with correct parentId and depth`() {
        val parent = service.createNewSession()
        val branch = service.createBranch(parent.id)
        assertNotNull(branch)
        assertEquals(parent.id, branch!!.parentId)
        assertEquals(1, branch.depth)
        assertFalse(branch.isRoot)
    }

    @Test
    fun `createBranch sets branch as active`() {
        val parent = service.createNewSession()
        val branch = service.createBranch(parent.id)
        assertEquals(branch!!.id, repo.getActiveSessionId())
    }

    @Test
    fun `createBranch with custom title uses it`() {
        val parent = service.createNewSession()
        val branch = service.createBranch(parent.id, "My Branch")
        assertEquals("My Branch", branch!!.title)
    }

    @Test
    fun `createBranch returns null for nonexistent parent`() {
        assertNull(service.createBranch("nonexistent"))
    }

    @Test
    fun `nested branch has depth 2`() {
        val root = service.createNewSession()
        val child = service.createBranch(root.id)!!
        val grandchild = service.createBranch(child.id)!!
        assertEquals(0, root.depth)
        assertEquals(1, child.depth)
        assertEquals(2, grandchild.depth)
    }

    // --- getChildren ---

    @Test
    fun `getChildren returns empty for leaf session`() {
        val session = service.createNewSession()
        assertTrue(service.getChildren(session.id).isEmpty())
    }

    @Test
    fun `getChildren returns direct children only`() {
        val root = service.createNewSession()
        val child1 = service.createBranch(root.id)!!
        val child2 = service.createBranch(root.id)!!
        service.createBranch(child1.id)!!  // grandchild

        val children = service.getChildren(root.id)
        assertEquals(2, children.size)
        assertTrue(children.any { it.id == child1.id })
        assertTrue(children.any { it.id == child2.id })
    }

    // --- getSessionPath ---

    @Test
    fun `getSessionPath for root returns single element`() {
        val root = service.createNewSession()
        val path = service.getSessionPath(root.id)
        assertEquals(1, path.size)
        assertEquals(root.id, path[0].id)
    }

    @Test
    fun `getSessionPath for child returns root then child`() {
        val root = service.createNewSession()
        val child = service.createBranch(root.id)!!
        val path = service.getSessionPath(child.id)
        assertEquals(2, path.size)
        assertEquals(root.id, path[0].id)
        assertEquals(child.id, path[1].id)
    }

    @Test
    fun `getSessionPath for grandchild returns full path`() {
        val root = service.createNewSession()
        val child = service.createBranch(root.id)!!
        val grandchild = service.createBranch(child.id)!!
        val path = service.getSessionPath(grandchild.id)
        assertEquals(3, path.size)
        assertEquals(root.id, path[0].id)
        assertEquals(child.id, path[1].id)
        assertEquals(grandchild.id, path[2].id)
    }

    // --- deleteSession ---

    @Test
    fun `deleteSession removes session`() {
        val session = service.createNewSession()
        service.deleteSession(session.id)
        assertNull(repo.getSessionById(session.id))
    }

    @Test
    fun `deleteSession re-parents children to grandparent`() {
        val root = service.createNewSession()
        val child = service.createBranch(root.id)!!
        val grandchild = service.createBranch(child.id)!!

        service.deleteSession(child.id)

        val updatedGrandchild = repo.getSessionById(grandchild.id)!!
        assertEquals(root.id, updatedGrandchild.parentId)
        assertEquals(1, updatedGrandchild.depth)
    }

    @Test
    fun `deleteSession makes children root when deleting root`() {
        val root = service.createNewSession()
        val child = service.createBranch(root.id)!!

        service.deleteSession(root.id)

        val updatedChild = repo.getSessionById(child.id)!!
        assertNull(updatedChild.parentId)
        assertEquals(0, updatedChild.depth)
        assertTrue(updatedChild.isRoot)
    }

    // --- deleteSessionCascade ---

    @Test
    fun `deleteSessionCascade removes session and all descendants`() {
        val root = service.createNewSession()
        val child = service.createBranch(root.id)!!
        val grandchild = service.createBranch(child.id)!!

        service.deleteSessionCascade(child.id)

        assertNotNull(repo.getSessionById(root.id))  // root survives
        assertNull(repo.getSessionById(child.id))
        assertNull(repo.getSessionById(grandchild.id))
    }

    // --- addMessage ---

    @Test
    fun `addMessage adds message to session`() {
        val session = service.createNewSession()
        val updated = service.addMessage(session.id, MessageRole.USER, "Hello")
        assertEquals(1, updated.messages.size)
        assertEquals("Hello", updated.messages[0].content)
    }

    @Test
    fun `addMessage persists to repository`() {
        val session = service.createNewSession()
        service.addMessage(session.id, MessageRole.USER, "Hello")
        val loaded = repo.getSessionById(session.id)!!
        assertEquals(1, loaded.messages.size)
    }

    // --- renameSession ---

    @Test
    fun `renameSession updates title`() {
        val session = service.createNewSession()
        val updated = service.renameSession(session.id, "New Title")
        assertEquals("New Title", updated?.title)
        assertEquals("New Title", repo.getSessionById(session.id)?.title)
    }

    @Test
    fun `renameSession returns null for nonexistent session`() {
        assertNull(service.renameSession("nonexistent", "title"))
    }

    // --- buildTree ---

    @Test
    fun `buildTree returns empty for no sessions`() {
        assertTrue(service.buildTree().isEmpty())
    }

    @Test
    fun `buildTree returns root sessions at top level`() {
        val s1 = service.createNewSession()
        val s2 = service.createNewSession()
        val tree = service.buildTree()
        assertEquals(2, tree.size)
        assertTrue(tree.any { it.id == s1.id })
        assertTrue(tree.any { it.id == s2.id })
    }

    @Test
    fun `buildTree nests children correctly`() {
        val root = service.createNewSession()
        val child = service.createBranch(root.id)!!
        val tree = service.buildTree()
        assertEquals(1, tree.size)  // only root at top level
        assertEquals(1, tree[0].children.size)
        assertEquals(child.id, tree[0].children[0].id)
    }

    // --- token tracking ---

    @Test
    fun `addPlanningTokens accumulates correctly`() {
        val session = service.createNewSession()
        service.addPlanningTokens(session.id, 100, 50)
        service.addPlanningTokens(session.id, 200, 80)
        val loaded = repo.getSessionById(session.id)!!
        assertEquals(300, loaded.tokenUsage.planningInput)
        assertEquals(130, loaded.tokenUsage.planningOutput)
    }

    @Test
    fun `addChatTokens accumulates correctly`() {
        val session = service.createNewSession()
        service.addChatTokens(session.id, 500, 200)
        val loaded = repo.getSessionById(session.id)!!
        assertEquals(500, loaded.tokenUsage.chatInput)
        assertEquals(200, loaded.tokenUsage.chatOutput)
    }

    // --- context files ---

    @Test
    fun `addGlobalContextFile adds file`() {
        service.addGlobalContextFile("src/Main.kt")
        assertTrue(service.getGlobalContextFiles().contains("src/Main.kt"))
    }

    @Test
    fun `addGlobalContextFile does not duplicate`() {
        service.addGlobalContextFile("src/Main.kt")
        service.addGlobalContextFile("src/Main.kt")
        assertEquals(1, service.getGlobalContextFiles().size)
    }

    @Test
    fun `removeGlobalContextFile removes file`() {
        service.addGlobalContextFile("src/Main.kt")
        service.removeGlobalContextFile("src/Main.kt")
        assertFalse(service.getGlobalContextFiles().contains("src/Main.kt"))
    }

    @Test
    fun `addGlobalContextFile normalizes backslashes`() {
        service.addGlobalContextFile("src\\Main.kt")
        assertTrue(service.getGlobalContextFiles().contains("src/Main.kt"))
    }
}
```

### Ручное тестирование
        Плагин запускается, всё работает как раньше (UI ещё не переключён).

## Коммит

```
refactor: add ChatTreeService in application layer with unit tests
```
