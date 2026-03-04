# План рефакторинга : Chat Domain Layer

**Статус:** В планировании
**Приоритет:** Высокий
**Сложность:** Высокая(~20 + мест затронуто)

---

## Проблема

Сейчас `ChatSession`, `ChatMessage`, `MessageRole` и вся логика дерева сессий живут в `maxvibes-plugin/chat/ChatHistoryService.kt` — в самом верхнем, инфраструктурном слое . Это нарушает Clean Architecture:

-Доменные объекты несут IntelliJ XML - аннотации(`@Tag`, `@Attribute`, `@XCollection`)
-Бизнес - логика(branching, tree navigation, token tracking) не тестируется без IntelliJ
-Нет единого источника правды : сессии в plugin, история в ChatMessageDTO в application, фазы в ClipboardInteractionService
-`ChatSession` мутабельна (`var`) из -за требований XML - сериализатора

---

## Цель

Перенести чат -модель и бизнес - логику в правильные архитектурные слои:

```
domain     — чистые модели (ChatSession, ChatMessage, MessageRole, TokenUsage)
application — порт ChatSessionRepository +логика дерева в use case или service
plugin      — только persistence -адаптер(XML сериализация через IntelliJ)
```

---

## Шаг 1: Доменные модели в `maxvibes-domain`

        Создать пакет `domain/model/chat/`:

```kotlin
// ChatMessage.kt
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long
)

// MessageRole.kt
enum class MessageRole { USER, ASSISTANT, SYSTEM }

// TokenUsage.kt
data class TokenUsage(
    val planningInput: Int = 0,
    val planningOutput: Int = 0,
    val chatInput: Int = 0,
    val chatOutput: Int = 0
) {
    val totalInput get() = planningInput + chatInput
    val totalOutput get() = planningOutput + chatOutput
    val total get() = totalInput + totalOutput
    fun addPlanning(input: Int, output: Int) =
        copy(planningInput = planningInput + input, planningOutput = planningOutput + output)

    fun addChat(input: Int, output: Int) = copy(chatInput = chatInput + input, chatOutput = chatOutput + output)
}

// ChatSession.kt
data class ChatSession(
    val id: String,
    val title: String,
    val parentId: String?,
    val depth: Int,
    val messages: List<ChatMessage>,
    val tokenUsage: TokenUsage,
    val createdAt: Long,
    val updatedAt: Long
) {
    val isRoot get() = parentId == null
    fun withMessage(message: ChatMessage): ChatSession
    fun withTitle(newTitle: String): ChatSession
    fun withTokenUsage(usage: TokenUsage): ChatSession
}
```

**Важно:** Всё immutable, никаких IntelliJ аннотаций, никаких no -arg конструкторов .

---

## Шаг 2: Порт `ChatSessionRepository` в `maxvibes-application`

        Создать `port/output/ChatSessionRepository.kt` :

```kotlin
interface ChatSessionRepository {
    fun getAllSessions(): List<ChatSession>
    fun getSessionById(id: String): ChatSession?
    fun getActiveSession(): ChatSession
    fun setActiveSession(sessionId: String)
    fun saveSession(session: ChatSession)
    fun deleteSession(sessionId: String)
    fun getGlobalContextFiles(): List<String>
    fun setGlobalContextFiles(files: List<String>)
}
```

---

## Шаг 3: Сервис дерева в `maxvibes-application`

        Создать `service/ChatTreeService.kt` — сюда переезжает вся логика из `ChatHistoryService`:

```kotlin
class ChatTreeService(private val repository: ChatSessionRepository) {
    fun getChildren(sessionId: String): List<ChatSession>
    fun getParent(sessionId: String): ChatSession?
    fun getSessionPath(sessionId: String): List<ChatSession>
    fun buildTree(): List<SessionTreeNode>  // SessionTreeNode тоже в domain
    fun createNewSession(): ChatSession
    fun createBranch(parentId: String, title: String?): ChatSession?
    fun deleteSession(sessionId: String)     // с переподвешиванием детей
    fun deleteSessionCascade(sessionId: String)
    fun renameSession(sessionId: String, newTitle: String): ChatSession?
    fun addMessage(sessionId: String, role: MessageRole, content: String): ChatSession
    fun addTokens(sessionId: String, usage: TokenUsage): ChatSession
}
```

---

## Шаг 4: Persistence - адаптер в `maxvibes-plugin`

Переписать `ChatHistoryService` — оставить только XML -сериализацию:

-`XmlChatMessage`, `XmlChatSession` — внутренние классы с `@Tag` / `@Attribute`
        -Маппинг `XmlChatSession ↔ ChatSession`
        -`ChatHistoryService` реализует `ChatSessionRepository`
-Из него убирается вся бизнес - логика дерева

        ---

## Шаг 5: Обновить MaxVibesService

        Добавить `ChatTreeService` в DI :

```kotlin
val chatSessionRepository: ChatSessionRepository by lazy {
    ChatHistoryService.getInstance(project)
}

val chatTreeService: ChatTreeService by lazy {
    ChatTreeService(chatSessionRepository)
}
```

---

## Шаг 6: Обновить UI -слой

Все места где сейчас используется `ChatHistoryService` напрямую — переключить на `ChatTreeService`:

-`ChatPanel` — работа с сессиями через `chatTreeService`
-`ChatMessageController` — добавление сообщений и токенов
        -`SessionTreePanel` — получение дерева
        -`ConversationPanel` — загрузка сообщений
        -`ChatDialogsHelper` — context files

        ---

## Затронутые файлы

### Новые файлы
| Файл | Модуль |
|------|--------|
| `domain/model/chat/ChatMessage.kt` | maxvibes - domain |
| `domain/model/chat/ChatSession.kt` | maxvibes - domain |
| `domain/model/chat/MessageRole.kt` | maxvibes - domain |
| `domain/model/chat/TokenUsage.kt` | maxvibes - domain |
| `domain/model/chat/SessionTreeNode.kt` | maxvibes - domain |
| `application/port/output/ChatSessionRepository.kt` | maxvibes - application |
| `application/service/ChatTreeService.kt` | maxvibes - application |

### Изменяемые файлы
| Файл | Изменение |
|------|----------|
| `plugin/chat/ChatHistoryService.kt` | Переписать — только persistence, реализует ChatSessionRepository |
| `plugin/service/MaxVibesService.kt` | Добавить chatSessionRepository +chatTreeService |
| `plugin/ui/ChatPanel.kt` | Переключить на ChatTreeService |
| `plugin/ui/ChatMessageController.kt` | Переключить на ChatTreeService |
| `plugin/ui/SessionTreePanel.kt` | Переключить на ChatTreeService |
| `plugin/ui/ConversationPanel.kt` | Переключить на ChatTreeService |
| `plugin/ui/ChatDialogsHelper.kt` | Переключить на ChatTreeService |

---

## Риски и ограничения

1.* * IntelliJ persistence требует mutable классы.* * XML - адаптер(`XmlChatSession`) останется мутабельным — это нормально, это инфраструктурный слой.2.* * Существующие данные . * * При смене структуры XML - файла(
    `maxvibes-chat-history.xml`
) нужна миграция или backward - compatible имена тегов.3.* * toChatMessageDTO() * * — в `ChatMessage` есть метод `toChatMessageDTO()` который использует `ChatMessageController` . Нужно либо перенести этот маппинг, либо добавить extension function в plugin -модуле.4.* * Scope.* * Изменение затрагивает ~20 мест . Рекомендуется делать в отдельной ветке и по шагам: сначала создать домен(
    шаги
    1 - 2
), потом сервис (шаг 3), потом адаптер (шаг 4), потом UI (шаги 5 - 6).

---

## Порядок реализации

        1.Создать доменные модели(шаг 1) — не трогает ничего существующего
        2.Создать порт `ChatSessionRepository`(шаг 2)
3.Создать `ChatTreeService` (шаг 3) с тестами
        4.Переписать `ChatHistoryService` как адаптер (шаг 4)
5.Подключить через `MaxVibesService`(шаг 5)
6.Обновить UI (шаг 6) — по одному файлу
7.Удалить старые импорты и убедиться что всё компилируется
