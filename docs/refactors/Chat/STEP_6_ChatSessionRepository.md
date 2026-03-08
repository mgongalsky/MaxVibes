# Шаг 6: Добавить порт `ChatSessionRepository`

## Контекст

На этом шаге мы создаём интерфейс -порт `ChatSessionRepository` в application layer и заставляем `ChatHistoryService` реализовывать его . UI -слой пока не трогаем — он продолжает обращаться к `ChatHistoryService` напрямую.Это чисто структурный шаг .

**Предварительные условия : * * Шаги 1–5 выполнены .

## Что нужно сделать

### 1.Создать порт

**Путь:** `maxvibes-application/src/main/kotlin/com/maxvibes/application/port/output/ChatSessionRepository.kt`

```kotlin
package com.maxvibes.application.port.output

import com . maxvibes . domain . model . chat . ChatSession

/**
 * Порт для хранения и загрузки чат-сессий.
 * Реализуется инфраструктурным слоем (IntelliJ persistence).
 */
interface ChatSessionRepository {

    /** Возвращает все сессии (без сортировки) */
    fun getAllSessions(): List<ChatSession>

    /** Возвращает сессию по ID или null */
    fun getSessionById(id: String): ChatSession?

    /** Возвращает ID активной сессии или null */
    fun getActiveSessionId(): String?

    /** Устанавливает активную сессию */
    fun setActiveSessionId(sessionId: String)

    /** Сохраняет или обновляет сессию */
    fun saveSession(session: ChatSession)

    /** Удаляет сессию по ID (только эту, без потомков) */
    fun deleteSession(sessionId: String)

    /** Возвращает список глобальных контекстных файлов */
    fun getGlobalContextFiles(): List<String>

    /** Устанавливает список глобальных контекстных файлов */
    fun setGlobalContextFiles(files: List<String>)
}
```

### 2.`ChatHistoryService` реализует `ChatSessionRepository`

В `ChatHistoryService.kt` добавить реализацию интерфейса:

```kotlin
@Service(Service.Level.PROJECT)
@State(...)
class ChatHistoryService : PersistentStateComponent<ChatHistoryState>, ChatSessionRepository {
    // ...

    // Реализация ChatSessionRepository:

    override fun getAllSessions(): List<ChatSession> =
        state.sessions.map { it.toDomain() }

    override fun getSessionById(id: String): ChatSession? =
        state.sessions.find { it.id == id }?.toDomain()

    override fun getActiveSessionId(): String? = state.activeSessionId

    override fun setActiveSessionId(sessionId: String) {
        state.activeSessionId = sessionId
    }

    override fun saveSession(session: ChatSession) {
        val index = state.sessions.indexOfFirst { it.id == session.id }
        val xmlSession = XmlChatSession.fromDomain(session)
        if (index >= 0) state.sessions[index] = xmlSession
        else state.sessions.add(0, xmlSession)
    }

    override fun deleteSession(sessionId: String) {
        state.sessions.removeIf { it.id == sessionId }
        if (state.activeSessionId == sessionId) {
            state.activeSessionId = state.sessions.firstOrNull()?.id
        }
    }

    override fun getGlobalContextFiles(): List<String> =
        state.globalContextFiles.toList()

    override fun setGlobalContextFiles(files: List<String>) {
        state.globalContextFiles = files.distinct().toMutableList()
    }
}
```

**Важно:** Старые публичные методы в `ChatHistoryService` не удалять — они ещё используются UI напрямую.Просто добавляем реализацию интерфейса поверх.

### 3.Посмотри на аналогичные порты для понимания стиля

Посмотри как устроены существующие output - порты:
-`maxvibes-application/src/main/kotlin/com/maxvibes/application/port/output/CodeRepository.kt`
-`maxvibes-application/src/main/kotlin/com/maxvibes/application/port/output/LLMService.kt`

Твой интерфейс должен быть в том же стиле — только доменные типы, никаких IntelliJ зависимостей.

## Что НЕ нужно делать

        -Не переключать `ChatPanel` или другой UI на новый интерфейс
-Не добавлять `ChatSessionRepository` в `MaxVibesService` — это шаг 7
-Не удалять старые методы из `ChatHistoryService`

## Проверка после реализации

### Компиляция
```
./gradlew compileKotlin
```

### Unit тест — mock реализация

Создать: `maxvibes-application/src/test/kotlin/com/maxvibes/application/port/output/ChatSessionRepositoryTest.kt`

```kotlin
/**
 * Проверяем что интерфейс корректен через in-memory реализацию.
 * Это также служит примером как тестировать ChatTreeService на шаге 7.
 */
class InMemoryChatSessionRepository : ChatSessionRepository {
    private val sessions = mutableMapOf<String, ChatSession>()
    private var activeSessionId: String? = null
    private var contextFiles = mutableListOf<String>()

    override fun getAllSessions(): List<ChatSession> = sessions.values.toList()
    override fun getSessionById(id: String): ChatSession? = sessions[id]
    override fun getActiveSessionId(): String? = activeSessionId
    override fun setActiveSessionId(sessionId: String) {
        activeSessionId = sessionId
    }

    override fun saveSession(session: ChatSession) {
        sessions[session.id] = session
    }

    override fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
        if (activeSessionId == sessionId) activeSessionId = sessions.keys.firstOrNull()
    }

    override fun getGlobalContextFiles(): List<String> = contextFiles.toList()
    override fun setGlobalContextFiles(files: List<String>) {
        contextFiles = files.distinct().toMutableList()
    }
}

class ChatSessionRepositoryContractTest {
    private lateinit var repo: ChatSessionRepository

    @BeforeEach
    fun setup() {
        repo = InMemoryChatSessionRepository()
    }

    @Test
    fun `getAllSessions returns empty initially`() {
        assertTrue(repo.getAllSessions().isEmpty())
    }

    @Test
    fun `saveSession and getSessionById`() {
        val session = ChatSession(id = "s1", title = "Test")
        repo.saveSession(session)
        val loaded = repo.getSessionById("s1")
        assertNotNull(loaded)
        assertEquals("Test", loaded!!.title)
    }

    @Test
    fun `saveSession updates existing session`() {
        val session = ChatSession(id = "s1", title = "Original")
        repo.saveSession(session)
        repo.saveSession(session.withTitle("Updated"))
        assertEquals("Updated", repo.getSessionById("s1")?.title)
        assertEquals(1, repo.getAllSessions().size)  // not duplicated
    }

    @Test
    fun `deleteSession removes session`() {
        val session = ChatSession(id = "s1")
        repo.saveSession(session)
        repo.deleteSession("s1")
        assertNull(repo.getSessionById("s1"))
        assertTrue(repo.getAllSessions().isEmpty())
    }

    @Test
    fun `activeSessionId persists`() {
        assertNull(repo.getActiveSessionId())
        repo.setActiveSessionId("s1")
        assertEquals("s1", repo.getActiveSessionId())
    }

    @Test
    fun `globalContextFiles persist`() {
        assertTrue(repo.getGlobalContextFiles().isEmpty())
        repo.setGlobalContextFiles(listOf("src/Main.kt", "README.md"))
        assertEquals(listOf("src/Main.kt", "README.md"), repo.getGlobalContextFiles())
    }

    @Test
    fun `globalContextFiles deduplicates`() {
        repo.setGlobalContextFiles(listOf("a.kt", "a.kt", "b.kt"))
        assertEquals(2, repo.getGlobalContextFiles().size)
    }
}
```

**Сохрани `InMemoryChatSessionRepository` * * — он понадобится на шаге 7 для тестирования `ChatTreeService` .

### Ручное тестирование
        Компиляция успешна, плагин запускается, всё работает как раньше .

## Коммит

```
refactor: add ChatSessionRepository port, ChatHistoryService implements it
```
