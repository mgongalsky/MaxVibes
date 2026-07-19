# Step 7 — Dependency Injection : wiring в MaxVibesService

## Цель

Связать всё, что создано в Steps 1–6, в `MaxVibesService` (Service Locator).Создать singleton `ClaudeCodeProcessAdapter` per -project, реализующий `ClaudeCodePort` . Создать `ClaudeCodeInteractionService` с правильными зависимостями . Зарегистрировать dispose - хук для shutdown процесса .

## Затрагиваемые файлы

| Файл | Действие |
|------|----------|
| `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/service/MaxVibesService.kt` | MODIFY |

Зависит от Step 3(adapter), Step 5(service), Step 6(session manager).

## Контекст

`MaxVibesService` — project - level service в IntelliJ . Создаётся при открытии проекта, освобождается при закрытии.Уже создаёт `ClipboardInteractionService`, `ClipboardSessionManager`, `JsonClipboardProtocolCodec` и т.д.Задача — добавить параллельный набор bean 'ов для Claude Code, переиспользуя существующие зависимости:
-`contextProvider`(project context port)
-`codeRepository`(PSI)
-`notificationPort`
-`promptPort`(расширенный в Step 4)
-`logger`
-`sessionManager`(расширенный в Step 6)
-`chatSessionRepository`

## Изменения

### 7.1 `MaxVibesService.kt` — прочитать `FULL`, добавить:

#### a) Поле для adapter'а и сервиса

```kotlin
private val claudeCodeAdapter: ClaudeCodeProcessAdapter by lazy {
    ClaudeCodeProcessAdapter(
        settings = MaxVibesSettings.getInstance(),
        codec = jsonClipboardProtocolCodec,  // переиспользуем существующий codec
        scope = serviceScope                  // CoroutineScope, привязанный к жизненному циклу проекта
    )
}

val claudeCodeInteractionService: ClaudeCodeInteractionService by lazy {
    ClaudeCodeInteractionService(
        contextProvider = projectContextProvider,
        claudeCodePort = claudeCodeAdapter,
        codeRepository = codeRepository,
        notificationPort = notificationService,
        promptPort = promptService,
        logger = maxVibesLogger,
        sessionManager = clipboardSessionManager,  // тот же! переиспользуем
        chatSessionRepository = chatHistoryService
    )
}
```

Имена существующих полей могут отличаться — посмотреть как названы рядом и подстроиться .

#### b) Dispose - хук

`MaxVibesService` обычно реализует `Disposable` или имеет метод `dispose()` . Добавить :

```kotlin
override fun dispose() {
    runCatching { claudeCodeAdapter.shutdown() }
    // ... existing disposal logic
}
```

Если `dispose()` уже есть — добавить shutdown в начало(до dispose других ресурсов).Если `MaxVibesService` не реализует `Disposable` — реализовать.IntelliJ вызовет `dispose()` при закрытии проекта автоматически, если сервис зарегистрирован как `@Service(Service.Level.PROJECT)`.

#### c) `serviceScope`(если ещё нет)

Для корутин внутри adapter 'а нужен `CoroutineScope`. Если в проекте уже есть — использовать. Если нет:

```kotlin
private val serviceScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default + CoroutineName("MaxVibesService")
)

override fun dispose() {
    serviceScope.cancel()
    runCatching { claudeCodeAdapter.shutdown() }
}
```

## Что НЕ делать

-Не создавать adapter eagerly — `by lazy` важен, чтобы не запускать процесс при открытии проекта(юзер может никогда не использовать Claude Code режим).
-Не делать adapter `@Service` сам по себе — он часть `MaxVibesService` и его lifecycle привязан к нему.
-Не подключать UI на этом шаге — это в Step 8.

## Тесты

DI - wiring обычно не покрывается unit - тестами(требует IntelliJ environment).Smoke - test после Step 8: запустить плагин, открыть проект, проверить что нет ошибок при инициализации .

Если в проекте есть `MaxVibesServiceTest` или подобное — пройтись по нему и убедиться, что не сломалось.

## Acceptance criteria

        -[] `./gradlew :maxvibes-plugin:build` зелёный
-[] При открытии проекта в run -IDE нет ERROR в `idea.log` от MaxVibes
-[] При закрытии проекта `claude` процесс не остаётся в системе (если был запущен)
