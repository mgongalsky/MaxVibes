# План рефакторинга: Chat Domain Layer

**Статус:** В работе
**Ветка:** `refactor/chat-domain-layer`
**Принцип:** Каждый шаг = отдельный коммит, проект компилируется и запускается после каждого шага.

---

## Стратегия

Мигрируем по одной сущности за раз. Старый код в plugin не трогаем до последнего шага — новые доменные классы сначала существуют параллельно со старыми, потом старые удаляются.

Порядок продиктован зависимостями: `MessageRole` → `TokenUsage` → `ChatMessage` → `ChatSession` → `SessionTreeNode` → `ChatSessionRepository` порт → `ChatTreeService` → переключение UI → удаление старого кода.

---

## Шаг 1: `MessageRole` в домен

**Что делаем:**
- Создать `maxvibes-domain/.../domain/model/chat/MessageRole.kt` — чистый enum без аннотаций
- В `ChatHistoryService.kt` заменить локальный `MessageRole` на импорт из домена
- Старый `enum class MessageRole` в plugin удалить

**Почему безопасно:** `MessageRole` — простой enum без зависимостей. Замена — механическая.

**Тест после шага:** Запустить плагин, открыть чат, отправить сообщение — убедиться что роли отображаются корректно.

**Коммит:** `refactor: move MessageRole to domain layer`

---

## Шаг 2: `TokenUsage` в домен

**Что делаем:**
- Создать `domain/model/chat/TokenUsage.kt` — immutable data class:
```kotlin
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
fun addChat(input: Int, output: Int) =
copy(chatInput = chatInput + input, chatOutput = chatOutput + output)
fun formatDisplay(inputCostPerM: Double = 3.0, outputCostPerM: Double = 15.0): String
}
```
- `TokenUsageAccumulator` в plugin — оставить, пока он ещё используется
- В `ChatSession` (старом) добавить вспомогательный метод `toTokenUsage(): TokenUsage` для постепенного перехода

**Тест после шага:** Проверить что token display в чате работает, цифры правильные.

**Коммит:** `refactor: add TokenUsage domain model`

---

## Шаг 3: `ChatMessage` в домен

**Что делаем:**
- Создать `domain/model/chat/ChatMessage.kt`:
```kotlin
data class ChatMessage(
val id: String = UUID.randomUUID().toString(),
val role: MessageRole,
val content: String,
val timestamp: Long = Instant.now().toEpochMilli()
)
```
- В `ChatHistoryService` добавить маппинг: старый `ChatMessage` (с XML-аннотациями, назвать `XmlChatMessage`) ↔ доменный `ChatMessage`
- Все методы `ChatHistoryService` которые возвращают сообщения — переключить на возврат `domain.ChatMessage`
- Старый класс `ChatMessage` переименовать в `XmlChatMessage` (только внутри файла, для сериализации)

**Тест после шага:** Открыть старую сессию из XML — сообщения загружаются, история отображается.

**Коммит:** `refactor: move ChatMessage to domain, XmlChatMessage as persistence DTO`

---

## Шаг 4: `ChatSession` в домен

**Что делаем:**
- Создать `domain/model/chat/ChatSession.kt`:
```kotlin
data class ChatSession(
val id: String = UUID.randomUUID().toString(),
val title: String = "New Chat",
val parentId: String? = null,
val depth: Int = 0,
val messages: List<ChatMessage> = emptyList(),
val tokenUsage: TokenUsage = TokenUsage(),
val createdAt: Long = Instant.now().toEpochMilli(),
val updatedAt: Long = Instant.now().toEpochMilli()
) {
val isRoot get() = parentId == null
fun withMessage(message: ChatMessage): ChatSession
fun withTitle(newTitle: String): ChatSession
fun addTokens(usage: TokenUsage): ChatSession
fun autoTitle(): ChatSession  // title из первого USER сообщения
}
```
- Старый `ChatSession` переименовать в `XmlChatSession` (только для сериализации)
- `ChatHistoryService` — все публичные методы переключить на возврат/приём `domain.ChatSession`
- Маппинг `XmlChatSession ↔ domain.ChatSession` внутри `ChatHistoryService`

**Тест после шага:** Создать новую сессию, добавить ветку, удалить — всё работает. Перезапустить IDE — история сохранилась.

**Коммит:** `refactor: move ChatSession to domain, XmlChatSession as persistence DTO`

---

## Шаг 5: `SessionTreeNode` в домен

**Что делаем:**
- Создать `domain/model/chat/SessionTreeNode.kt`:
```kotlin
data class SessionTreeNode(
val session: ChatSession,
val children: List<SessionTreeNode> = emptyList()
) {
val id get() = session.id
val title get() = session.title
val depth get() = session.depth
val hasChildren get() = children.isNotEmpty()
}
```
- Старый `SessionTreeNode` в `ChatHistoryService.kt` удалить
- `buildTree()` в `ChatHistoryService` переключить на новый тип

**Тест после шага:** Дерево сессий в UI отображается корректно, expand/collapse работает.

**Коммит:** `refactor: move SessionTreeNode to domain`

---

## Шаг 6: Порт `ChatSessionRepository` в application

**Что делаем:**
- Создать `application/port/output/ChatSessionRepository.kt`:
```kotlin
interface ChatSessionRepository {
fun getAllSessions(): List<ChatSession>
fun getSessionById(id: String): ChatSession?
fun getActiveSessionId(): String?
fun setActiveSessionId(sessionId: String)
fun saveSession(session: ChatSession)
fun deleteSession(sessionId: String)
fun getGlobalContextFiles(): List<String>
fun setGlobalContextFiles(files: List<String>)
}
```
- `ChatHistoryService` реализует `ChatSessionRepository` (добавить `: ChatSessionRepository`)
- Пока `ChatHistoryService` реализует интерфейс но UI ещё обращается к нему напрямую — это нормально

**Тест после шага:** Компиляция без ошибок, всё работает как раньше.

**Коммит:** `refactor: add ChatSessionRepository port, ChatHistoryService implements it`

---

## Шаг 7: `ChatTreeService` в application

**Что делаем:**
- Создать `application/service/ChatTreeService.kt` — сюда переезжает вся логика дерева из `ChatHistoryService`:
```kotlin
class ChatTreeService(private val repository: ChatSessionRepository) {
fun getActiveSession(): ChatSession
fun setActiveSession(sessionId: String)
fun getChildren(sessionId: String): List<ChatSession>
fun getParent(sessionId: String): ChatSession?
fun getSessionPath(sessionId: String): List<ChatSession>
fun buildTree(): List<SessionTreeNode>
fun createNewSession(): ChatSession
fun createBranch(parentId: String, title: String? = null): ChatSession
fun deleteSession(sessionId: String)   // переподвешивает детей
fun deleteSessionCascade(sessionId: String)
fun renameSession(sessionId: String, newTitle: String): ChatSession?
fun addMessage(sessionId: String, role: MessageRole, content: String): ChatSession
fun addTokens(sessionId: String, usage: TokenUsage): ChatSession
fun getAllSessions(): List<ChatSession>
fun getGlobalContextFiles(): List<String>
fun setGlobalContextFiles(files: List<String>)
}
```
- Из `ChatHistoryService` соответствующие методы НЕ удалять пока — `ChatTreeService` дублирует логику до шага 8
- Подключить `ChatTreeService` в `MaxVibesService`

**Тест после шага:** Написать unit-тесты на `ChatTreeService` (без IntelliJ!) — создание, ветвление, удаление, breadcrumb. Это первый раз когда логика чатов тестируется в изоляции.

**Коммит:** `refactor: add ChatTreeService in application layer with unit tests`

---

## Шаг 8: Переключить UI на `ChatTreeService`

**Что делаем:** По одному файлу переключить с `ChatHistoryService` на `ChatTreeService`:

1. `ChatMessageController` — `addMessage()`, `addTokens()`
2. `SessionTreePanel` — `buildTree()`, `getActiveSession()`
3. `ConversationPanel` — загрузка сообщений
4. `ChatPanel` — навигация, сессии, breadcrumb, context files
5. `ChatDialogsHelper` — диалоги переименования/удаления
6. `ChatNavigationHelper` — переходы между сессиями

Каждый файл = отдельный мини-коммит.

**Тест после каждого файла:** Запустить, проверить что затронутая функциональность работает.

**Коммиты:** `refactor: switch ChatMessageController to ChatTreeService`, и т.д.

---

## Шаг 9: Удалить старый код из `ChatHistoryService`

**Что делаем:**
- Удалить из `ChatHistoryService` все методы которые теперь живут в `ChatTreeService`
- `ChatHistoryService` остаётся только как persistence-адаптер: load/save XML, реализация `ChatSessionRepository`
- Удалить `TokenUsageAccumulator` если он больше не используется

**Тест после шага:** Полная регрессия — все функции чата работают, данные сохраняются между перезапусками IDE.

**Коммит:** `refactor: ChatHistoryService is now pure persistence adapter`

---

## Итоговая структура

```
maxvibes-domain/
model/chat/
MessageRole.kt
TokenUsage.kt
ChatMessage.kt
ChatSession.kt
SessionTreeNode.kt

maxvibes-application/
port/output/
ChatSessionRepository.kt   ← новый порт
service/
ChatTreeService.kt         ← вся бизнес-логика дерева
ChatTreeServiceTest.kt     ← unit тесты

maxvibes-plugin/
chat/
ChatHistoryService.kt      ← только XML persistence + implements ChatSessionRepository
```

---

## Правила для каждого шага

- Проект **компилируется** после шага
- Плагин **запускается** после шага
- Данные **не теряются** (XML-совместимость сохраняется)
- Каждый шаг — **отдельный коммит** с понятным сообщением
- Шаги 1–6 можно делать в любой день, они маленькие (~30 мин каждый)
- Шаг 7 — самый важный (появляются тесты), ~2-3 часа
- Шаг 8 — самый долгий (много мест), лучше делать подшагами
- Шаг 9 — финальная чистка, ~30 мин
