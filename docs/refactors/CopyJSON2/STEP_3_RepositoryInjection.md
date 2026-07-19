# Step 3: Инжектировать ChatSessionRepository в ClipboardInteractionService

## Контекст

`redoLastRequest` должен читать `ChatSession` из репозитория чтобы получить
        `gatheredFilePaths` и последнее сообщение пользователя.Сейчас
`ClipboardInteractionService` не имеет доступа к `ChatSessionRepository` .

## Файлы для изменения

### 1.`ClipboardInteractionService.kt`

**Путь:** `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClipboardInteractionService.kt`

Добавить параметр в конструктор :

```kotlin
class ClipboardInteractionService(
    private val contextProvider: ProjectContextPort,
    private val clipboardPort: ClipboardPort,
    private val codeRepository: CodeRepository,
    private val notificationPort: NotificationPort,
    private val promptPort: PromptPort? = null,
    private val logger: LoggerPort? = null,
    private val sessionManager: ClipboardSessionManager,
    /** Repository for reading per-session domain state (gatheredFilePaths, messages). */
    private val chatSessionRepository: ChatSessionRepository
)
```

### 2.`MaxVibesService.kt`

**Путь:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/service/MaxVibesService.kt`

Найти место где создаётся `ClipboardInteractionService` и передать `chatHistoryService`
        (который уже реализует `ChatSessionRepository`) как новый параметр .

Пример:
```kotlin
val clipboardService = ClipboardInteractionService(
    contextProvider = ...,
clipboardPort = ...,
codeRepository = ...,
notificationPort = ...,
promptPort = ...,
logger = ...,
sessionManager = clipboardSessionManager,
chatSessionRepository = chatHistoryService  // <- новый параметр
)
```

### 3.`ClipboardInteractionServiceTest.kt`

**Путь:** `maxvibes-application/src/test/kotlin/com/maxvibes/application/service/ClipboardInteractionServiceTest.kt`

Добавить мок в setUp :
```kotlin
private val chatSessionRepository = mockk<ChatSessionRepository>(relaxed = true)
```

И передать в конструктор сервиса в `setUp()`:
```kotlin
service = ClipboardInteractionService(
    ...,
chatSessionRepository = chatSessionRepository
)
```

## Проверка

```bash
    ./ gradlew : maxvibes -application:compileKotlin
    ./ gradlew : maxvibes -plugin:compileKotlin
    ./ gradlew : maxvibes -application:test
```

Тесты должны оставаться зелёными — новый параметр только добавлен, логика не менялась.

## Коммит

```
feat(application): inject ChatSessionRepository into ClipboardInteractionService
```
