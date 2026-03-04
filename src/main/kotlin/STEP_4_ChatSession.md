# Шаг 4: Перенести `ChatSession` в domain

## Контекст

Аналогично шагу 3, но для `ChatSession`.Это более сложный шаг — `ChatSession` имеет больше полей, методы бизнес -логики, и используется в большем количестве мест .

Стратегия та же: создаём чистый доменный `ChatSession`, старый переименовываем в `XmlChatSession` .

**Предварительные условия : * * Шаги 1, 2, 3 выполнены .

## Что нужно сделать

### 1.Создать доменный `ChatSession`

**Путь:** `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/chat/ChatSession.kt`

```kotlin
package com.maxvibes.domain.model.chat

import java . time . Instant
        import java . util . UUID

        data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val parentId: String? = null,
    val depth: Int = 0,
    val messages: List<ChatMessage> = emptyList(),
    val tokenUsage: TokenUsage = TokenUsage.EMPTY,
    val createdAt: Long = Instant.now().toEpochMilli(),
    val updatedAt: Long = Instant.now().toEpochMilli()
) {
    val isRoot: Boolean get() = parentId == null

    fun withMessage(message: ChatMessage): ChatSession {
        val newTitle = if (title == "New Chat" && message.role == MessageRole.USER)
            message.content.take(40) + if (message.content.length > 40) "..." else ""
        else title
        return copy(
            messages = messages + message,
            title = newTitle,
            updatedAt = Instant.now().toEpochMilli()
        )
    }

    fun withTitle(newTitle: String): ChatSession =
        copy(title = newTitle.trim().ifBlank { "Untitled" }, updatedAt = Instant.now().toEpochMilli())

    fun withDepth(newDepth: Int): ChatSession = copy(depth = newDepth)

    fun withParent(newParentId: String?, newDepth: Int): ChatSession =
        copy(parentId = newParentId, depth = newDepth, updatedAt = Instant.now().toEpochMilli())

    fun addPlanningTokens(input: Int, output: Int): ChatSession =
        copy(tokenUsage = tokenUsage.addPlanning(input, output))

    fun addChatTokens(input: Int, output: Int): ChatSession =
        copy(tokenUsage = tokenUsage.addChat(input, output))

    fun touch(): ChatSession = copy(updatedAt = Instant.now().toEpochMilli())

    fun cleared(): ChatSession = copy(messages = emptyList(), updatedAt = Instant.now().toEpochMilli())
}
```

### 2.В `ChatHistoryService.kt` — переименовать старый класс

        Старый `ChatSession` (с `@Tag`, `@Attribute`) переименовать в `XmlChatSession`.Добавить конвертацию :

```kotlin
@Tag("session")
class XmlChatSession {
    // ... все старые поля с аннотациями остаются ...

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
```

### 3.Обновить `ChatHistoryState`

        В `ChatHistoryState` поле `sessions` теперь хранит `XmlChatSession`:

```kotlin
class ChatHistoryState {
    @XCollection(style = XCollection.Style.v2)
    var sessions: MutableList<XmlChatSession> = mutableListOf()
    // ... остальное без изменений
}
```

### 4.Обновить публичные методы `ChatHistoryService`

        Все методы которые возвращают `ChatSession`(старый) — переключить на возврат `domain.ChatSession` . Внутри сервиса — конвертировать через `.toDomain()` и `fromDomain()`.Пример:
```kotlin
fun getSessionById(id: String): ChatSession? {
    return state.sessions.find { it.id == id }?.toDomain()
}

fun saveSession(session: ChatSession) {
    val index = state.sessions.indexOfFirst { it.id == session.id }
    val xmlSession = XmlChatSession.fromDomain(session)
    if (index >= 0) state.sessions[index] = xmlSession
    else state.sessions.add(0, xmlSession)
}
```

### 5.Исправить все сломанные ссылки

        Поищи `ChatSession` по всему проекту.Обнови импорты на `com.maxvibes.domain.model.chat.ChatSession` .

Основные места :
-`ChatPanel.kt`
-`ChatMessageController.kt`
-`SessionTreePanel.kt`
-`ConversationPanel.kt`
-`ChatNavigationHelper.kt`
-`ChatDialogsHelper.kt`

## XML - совместимость — критично!

Убедись что `@Tag("session")` остался на `XmlChatSession` . Имена тегов в XML не должны измениться, иначе старые чаты не загрузятся.

## Что НЕ нужно делать

        -Не переносить логику дерева (getChildren, buildTree и т.п.) — это шаг 7
-Не удалять `XmlChatSession`
-Не менять логику методов — только маппинг типов

## Проверка после реализации

### Компиляция
```
./gradlew compileKotlin
```

### Unit тесты

        Создать: `maxvibes-domain/src/test/kotlin/com/maxvibes/domain/model/chat/ChatSessionTest.kt`

```kotlin
class ChatSessionTest {

    @Test
    fun `new ChatSession has default values`() {
        val session = ChatSession()
        assertEquals("New Chat", session.title)
        assertNull(session.parentId)
        assertEquals(0, session.depth)
        assertTrue(session.messages.isEmpty())
        assertTrue(session.isRoot)
        assertTrue(session.tokenUsage.isEmpty())
    }

    @Test
    fun `withMessage adds message to session`() {
        val session = ChatSession()
        val msg = ChatMessage(role = MessageRole.USER, content = "Hello")
        val updated = session.withMessage(msg)
        assertEquals(1, updated.messages.size)
        assertEquals(msg, updated.messages[0])
    }

    @Test
    fun `withMessage auto-sets title from first USER message`() {
        val session = ChatSession()
        val msg = ChatMessage(role = MessageRole.USER, content = "Fix the login bug")
        val updated = session.withMessage(msg)
        assertEquals("Fix the login bug", updated.title)
    }

    @Test
    fun `withMessage does not change title after it is set`() {
        val session = ChatSession()
        val first = session.withMessage(ChatMessage(role = MessageRole.USER, content = "First message"))
        val second = first.withMessage(ChatMessage(role = MessageRole.USER, content = "Second message"))
        assertEquals("First message", second.title)
    }

    @Test
    fun `withMessage truncates long title to 40 chars with ellipsis`() {
        val session = ChatSession()
        val longContent = "A".repeat(50)
        val updated = session.withMessage(ChatMessage(role = MessageRole.USER, content = longContent))
        assertEquals(43, updated.title.length)  // 40 + "..."
        assertTrue(updated.title.endsWith("..."))
    }

    @Test
    fun `withMessage does not auto-title for ASSISTANT messages`() {
        val session = ChatSession()
        val msg = ChatMessage(role = MessageRole.ASSISTANT, content = "I can help with that")
        val updated = session.withMessage(msg)
        assertEquals("New Chat", updated.title)  // unchanged
    }

    @Test
    fun `withTitle renames session`() {
        val session = ChatSession(title = "Old")
        val updated = session.withTitle("New Name")
        assertEquals("New Name", updated.title)
    }

    @Test
    fun `withTitle blank becomes Untitled`() {
        val session = ChatSession(title = "Old")
        val updated = session.withTitle("   ")
        assertEquals("Untitled", updated.title)
    }

    @Test
    fun `isRoot is true when parentId is null`() {
        val root = ChatSession(parentId = null)
        val child = ChatSession(parentId = "parent-id")
        assertTrue(root.isRoot)
        assertFalse(child.isRoot)
    }

    @Test
    fun `addPlanningTokens accumulates in tokenUsage`() {
        val session = ChatSession()
        val updated = session.addPlanningTokens(100, 50)
        assertEquals(100, updated.tokenUsage.planningInput)
        assertEquals(50, updated.tokenUsage.planningOutput)
    }

    @Test
    fun `addChatTokens accumulates in tokenUsage`() {
        val session = ChatSession()
        val updated = session.addChatTokens(200, 80)
        assertEquals(200, updated.tokenUsage.chatInput)
        assertEquals(80, updated.tokenUsage.chatOutput)
    }

    @Test
    fun `cleared removes all messages`() {
        val session = ChatSession().withMessage(ChatMessage(role = MessageRole.USER, content = "test"))
        assertEquals(1, session.messages.size)
        val cleared = session.cleared()
        assertTrue(cleared.messages.isEmpty())
    }

    @Test
    fun `ChatSession is immutable - withMessage returns new instance`() {
        val original = ChatSession()
        val updated = original.withMessage(ChatMessage(role = MessageRole.USER, content = "test"))
        assertTrue(original.messages.isEmpty())  // original unchanged
        assertEquals(1, updated.messages.size)
    }

    @Test
    fun `withParent updates parentId and depth`() {
        val session = ChatSession()
        val updated = session.withParent("parent-123", 2)
        assertEquals("parent-123", updated.parentId)
        assertEquals(2, updated.depth)
        assertFalse(updated.isRoot)
    }
}
```

### Ручное тестирование
        1.Запустить плагин
        2.Создать новую сессию — убедиться что создаётся
3.Создать ветку от сессии — убедиться что дерево отображается
4.* * Критично:** Перезапустить IDE — все сессии загружаются из XML
        5.Переименовать сессию — работает
        6.Удалить сессию — работает

## Коммит

```
refactor: move ChatSession to domain, XmlChatSession as persistence DTO
```
