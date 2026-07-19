# Шаг 5: Перенести `SessionTreeNode` в domain

## Контекст

`SessionTreeNode` — это view -model для дерева сессий . Сейчас он живёт в `ChatHistoryService.kt` как `data class` . Это небольшой шаг — переносим его в domain, где ему и место .

**Предварительные условия : * * Шаги 1–4 выполнены .

## Что нужно сделать

### 1.Создать доменный `SessionTreeNode`

**Путь:** `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/chat/SessionTreeNode.kt`

```kotlin
package com.maxvibes.domain.model.chat

data class SessionTreeNode(
    val session: ChatSession,
    val children: List<SessionTreeNode> = emptyList()
) {
    val id: String get() = session.id
    val title: String get() = session.title
    val depth: Int get() = session.depth
    val hasChildren: Boolean get() = children.isNotEmpty()

    fun withChildren(newChildren: List<SessionTreeNode>): SessionTreeNode =
        copy(children = newChildren)
}
```

**Важно:** В отличие от старого, новый `SessionTreeNode` использует `List` (immutable) вместо `MutableList`.Это потребует небольшой правки в `buildTree()` .

### 2.Обновить `buildTree()` в `ChatHistoryService`

        Метод `buildTree()` теперь должен возвращать `List<domain.SessionTreeNode>` . Алгоритм тот же, но с immutable списками :

```kotlin
fun buildTree(): List<SessionTreeNode> {
    val domainSessions = state.sessions.map { it.toDomain() }
    val childrenMap = mutableMapOf<String, MutableList<SessionTreeNode>>()

    // Строим карту детей
    for (session in domainSessions) {
        val parentId = session.parentId ?: continue
        childrenMap.getOrPut(parentId) { mutableListOf() }
    }

    // Рекурсивно строим дерево (от листьев к корням)
    fun buildNode(session: ChatSession): SessionTreeNode {
        val children = domainSessions
            .filter { it.parentId == session.id }
            .sortedByDescending { it.updatedAt }
            .map { buildNode(it) }
        return SessionTreeNode(session, children)
    }

    return domainSessions
        .filter { it.isRoot }
        .sortedByDescending { it.updatedAt }
        .map { buildNode(it) }
}
```

### 3.Удалить старый `SessionTreeNode` из `ChatHistoryService.kt`

Удалить `data class SessionTreeNode(...)` из `ChatHistoryService.kt` . Добавить импорт:
```kotlin
import com . maxvibes . domain . model . chat . SessionTreeNode
```

### 4.Обновить `SessionTreePanel.kt`

        `SessionTreePanel` использует `SessionTreeNode` — обнови импорт на доменный .

## Что НЕ нужно делать

        -Не переносить логику дерева в domain — `buildTree()` остаётся в `ChatHistoryService`(пока)
-Не трогать `SessionTreePanel` логику — только импорт

## Проверка после реализации

### Компиляция
```
./gradlew compileKotlin
```

### Unit тесты

        Создать: `maxvibes-domain/src/test/kotlin/com/maxvibes/domain/model/chat/SessionTreeNodeTest.kt`

```kotlin
class SessionTreeNodeTest {

    private fun session(id: String, title: String = "Session $id", depth: Int = 0) =
        ChatSession(id = id, title = title, depth = depth)

    @Test
    fun `leaf node has no children`() {
        val node = SessionTreeNode(session("1"))
        assertFalse(node.hasChildren)
        assertTrue(node.children.isEmpty())
    }

    @Test
    fun `node with children reports hasChildren`() {
        val child = SessionTreeNode(session("2"))
        val parent = SessionTreeNode(session("1"), listOf(child))
        assertTrue(parent.hasChildren)
        assertEquals(1, parent.children.size)
    }

    @Test
    fun `id delegates to session id`() {
        val node = SessionTreeNode(session("abc"))
        assertEquals("abc", node.id)
    }

    @Test
    fun `title delegates to session title`() {
        val node = SessionTreeNode(session("1", title = "My Chat"))
        assertEquals("My Chat", node.title)
    }

    @Test
    fun `depth delegates to session depth`() {
        val node = SessionTreeNode(session("1", depth = 3))
        assertEquals(3, node.depth)
    }

    @Test
    fun `withChildren replaces children`() {
        val original = SessionTreeNode(session("1"))
        val child = SessionTreeNode(session("2"))
        val updated = original.withChildren(listOf(child))
        assertTrue(original.children.isEmpty())  // original unchanged
        assertEquals(1, updated.children.size)
    }

    @Test
    fun `nested tree structure`() {
        val grandchild = SessionTreeNode(session("3", depth = 2))
        val child = SessionTreeNode(session("2", depth = 1), listOf(grandchild))
        val root = SessionTreeNode(session("1", depth = 0), listOf(child))

        assertTrue(root.hasChildren)
        assertTrue(root.children[0].hasChildren)
        assertFalse(root.children[0].children[0].hasChildren)
        assertEquals(0, root.depth)
        assertEquals(1, root.children[0].depth)
        assertEquals(2, root.children[0].children[0].depth)
    }
}
```

### Ручное тестирование
        1.Запустить плагин
        2.Убедиться что дерево сессий отображается в `SessionTreePanel`
3.Expand / collapse веток работает
4.Создать новую ветку — появляется в дереве

## Коммит

```
refactor: move SessionTreeNode to domain
```
