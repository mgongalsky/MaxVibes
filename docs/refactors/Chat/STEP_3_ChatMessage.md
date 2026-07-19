# Шаг 3: Перенести `ChatMessage` в domain

## Контекст

Сейчас `ChatMessage` в `ChatHistoryService.kt` несёт XML -аннотации IntelliJ (`@Tag`, `@Attribute`) и является мутабельным(
    `var`
).Это инфраструктурный артефакт, который мешает использовать `ChatMessage` в чистом application / domain слое .

На этом шаге мы создаём чистый доменный `ChatMessage`, а старый переименовываем в `XmlChatMessage`(только для persistence).

**Предварительные условия : * * Шаги 1 и 2 выполнены .

## Что нужно сделать

### 1.Создать доменный `ChatMessage`

**Путь:** `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/chat/ChatMessage.kt`

```kotlin
package com.maxvibes.domain.model.chat

import java . time . Instant
        import java . util . UUID

        data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = Instant.now().toEpochMilli()
)
```

### 2.В `ChatHistoryService.kt` — переименовать старый класс и добавить маппинг

Старый `ChatMessage` (с `@Tag`, `@Attribute`) переименовать в `XmlChatMessage`.Добавить методы конвертации:

```kotlin
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
        this.id = id; this.role = role; this.content = content; this.timestamp = timestamp
    }

    fun toDomain(): ChatMessage = ChatMessage(
        id = id, role = role, content = content, timestamp = timestamp
    )

    companion object {
        fun fromDomain(msg: ChatMessage) = XmlChatMessage(msg.id, msg.role, msg.content, msg.timestamp)
    }
}
```

### 3.Обновить `XmlChatSession` (бывший `ChatSession`)

В `ChatHistoryService.kt` поле `messages` в `XmlChatSession` теперь хранит `XmlChatMessage`:

```kotlin
@XCollection(style = XCollection.Style.v2)
var messages: MutableList<XmlChatMessage> = mutableListOf()
```

Добавить метод конвертации всех сообщений:
```kotlin
fun messagesToDomain(): List<ChatMessage> = messages.map { it.toDomain() }
```

### 4.Обновить метод `addMessage` в `XmlChatSession`

```kotlin
fun addMessage(role: MessageRole, content: String): ChatMessage {
    val xmlMsg = XmlChatMessage(UUID.randomUUID().toString(), role, content, Instant.now().toEpochMilli())
    messages.add(xmlMsg)
    updatedAt = Instant.now().toEpochMilli()
    if (title == "New Chat" && role == MessageRole.USER) {
        title = content.take(40) + if (content.length > 40) "..." else ""
    }
    return xmlMsg.toDomain()  // возвращаем доменный объект
}
```

### 5.Исправить все сломанные ссылки

        Поищи `ChatMessage` по всему проекту.Файлы которые используют `ChatMessage` из plugin — обнови импорты.Основные места :
-`ConversationPanel.kt` — рендеринг сообщений
        -`ChatMessageController.kt` — добавление сообщений
        -`ClipboardInteractionService.kt` — история для clipboard
-`ContextAwareModifyService.kt` — history параметр

        В каждом месте где используется `ChatMessage` — теперь он должен быть `com.maxvibes.domain.model.chat.ChatMessage` .

## Важное про XML - совместимость

XML - тег в файле был `<message>`(из `@Tag("message")` на старом `ChatMessage`).После переименования в `XmlChatMessage` — убедись что аннотация `@Tag("message")` * * сохранена * * . Иначе старые сохранённые чаты не загрузятся.

## Аналогичный паттерн в проекте

        Посмотри на `PsiToDomainMapper.kt` в `maxvibes-adapter-psi` — там используется похожий паттерн маппинга из инфраструктурных объектов в доменные .

## Что НЕ нужно делать

        -Не переносить `ChatSession` — это следующий шаг
-Не удалять `XmlChatMessage` — он нужен для сериализации
        -Не трогать структуру XML -файла `maxvibes-chat-history.xml`

## Проверка после реализации

### Компиляция
```
./gradlew compileKotlin
```
Должно компилироваться без ошибок во всех модулях.

### Unit тесты

        Создать: `maxvibes-domain/src/test/kotlin/com/maxvibes/domain/model/chat/ChatMessageTest.kt`

```kotlin
class ChatMessageTest {

    @Test
    fun `ChatMessage created with correct fields`() {
        val msg = ChatMessage(id = "123", role = MessageRole.USER, content = "Hello", timestamp = 1000L)
        assertEquals("123", msg.id)
        assertEquals(MessageRole.USER, msg.role)
        assertEquals("Hello", msg.content)
        assertEquals(1000L, msg.timestamp)
    }

    @Test
    fun `ChatMessage default id is non-blank UUID`() {
        val msg = ChatMessage(role = MessageRole.USER, content = "test")
        assertTrue(msg.id.isNotBlank())
        // UUID format
        assertEquals(36, msg.id.length)
    }

    @Test
    fun `ChatMessage default timestamp is recent`() {
        val before = System.currentTimeMillis()
        val msg = ChatMessage(role = MessageRole.ASSISTANT, content = "response")
        val after = System.currentTimeMillis()
        assertTrue(msg.timestamp in before..after)
    }

    @Test
    fun `ChatMessage is immutable data class`() {
        val msg1 = ChatMessage(id = "1", role = MessageRole.USER, content = "Hello", timestamp = 1000L)
        val msg2 = msg1.copy(content = "World")
        assertEquals("Hello", msg1.content)  // original unchanged
        assertEquals("World", msg2.content)
        assertEquals(msg1.id, msg2.id)
    }

    @Test
    fun `two ChatMessages with same fields are equal`() {
        val msg1 = ChatMessage(id = "1", role = MessageRole.USER, content = "Hello", timestamp = 1000L)
        val msg2 = ChatMessage(id = "1", role = MessageRole.USER, content = "Hello", timestamp = 1000L)
        assertEquals(msg1, msg2)
    }

    @Test
    fun `ChatMessage supports all roles`() {
        val userMsg = ChatMessage(role = MessageRole.USER, content = "q")
        val assistantMsg = ChatMessage(role = MessageRole.ASSISTANT, content = "a")
        val systemMsg = ChatMessage(role = MessageRole.SYSTEM, content = "s")
        assertEquals(MessageRole.USER, userMsg.role)
        assertEquals(MessageRole.ASSISTANT, assistantMsg.role)
        assertEquals(MessageRole.SYSTEM, systemMsg.role)
    }
}
```

### Ручное тестирование
        1.Запустить плагин
        2.Отправить сообщение — убедиться что оно появляется
3.* * Критично:** Перезапустить плагин — убедиться что старая история загружается (XML - совместимость)
4.Открыть сессию с историей — все сообщения отображаются правильно

## Коммит

```
refactor: move ChatMessage to domain, XmlChatMessage as persistence DTO
```
