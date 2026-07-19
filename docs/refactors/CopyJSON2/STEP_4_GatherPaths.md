# Step 3: Сохранять requestedFiles в домен после каждого ответа ЛЛМ

## Контекст

Когда ЛЛМ возвращает `requestedFiles` в ответе — сохраняем эти пути в домен,
в последнее ASSISTANT-сообщение. Это нужно для Сценария B в `redoLastRequest`.

Для этого `ClipboardInteractionService` нужен доступ к `ChatSessionRepository`.

## Файлы для изменения

### 1. Добавить `ChatSessionRepository` в конструктор сервиса

**Путь:** `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClipboardInteractionService.kt`

```kotlin
class ClipboardInteractionService(
private val contextProvider: ProjectContextPort,
private val clipboardPort: ClipboardPort,
private val codeRepository: CodeRepository,
private val notificationPort: NotificationPort,
private val promptPort: PromptPort? = null,
private val logger: LoggerPort? = null,
private val sessionManager: ClipboardSessionManager,
/** Provides read/write access to persisted chat sessions for domain state updates. */
private val chatSessionRepository: ChatSessionRepository
)
```

### 2. Обновить `handlePastedResponseInternal()`

После блока `if (response.message.isNotBlank()) { addToHistory(...) }` добавить:

```kotlin
// Persist requested file paths into the last ASSISTANT message in the domain.
// Required for redoLastRequest Scenario B (workspace belongs to different session).
if (response.requestedFiles.isNotEmpty()) {
val session = chatSessionRepository.getSessionById(sessionId)
if (session != null) {
val messages = session.messages.toMutableList()
val lastAssistantIdx = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
if (lastAssistantIdx >= 0) {
messages[lastAssistantIdx] = messages[lastAssistantIdx]
.copy(requestedFiles = response.requestedFiles)
chatSessionRepository.saveSession(session.copy(messages = messages))
}
}
}
```

### 3. Обновить `MaxVibesService.kt`

**Путь:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/service/MaxVibesService.kt`

Передать `chatHistoryService` как `chatSessionRepository` при создании `ClipboardInteractionService`.

### 4. Обновить тест (setUp)

**Путь:** `maxvibes-application/src/test/kotlin/.../ClipboardInteractionServiceTest.kt`

```kotlin
private val chatSessionRepository = mockk<ChatSessionRepository>(relaxed = true)

// В setUp():
service = ClipboardInteractionService(
...,
chatSessionRepository = chatSessionRepository
)
```

## Проверка

```bash
./gradlew :maxvibes-application:compileKotlin
./gradlew :maxvibes-plugin:compileKotlin
./gradlew :maxvibes-application:test
```

## Коммит

```
feat(application): persist requestedFiles from LLM response into domain
```
