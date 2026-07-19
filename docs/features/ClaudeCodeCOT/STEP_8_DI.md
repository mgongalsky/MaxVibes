# STEP 8 — Register ClaudeCodeActivityTracker in MaxVibesService

## Цель

Зарегистрировать `ClaudeCodeActivityTracker` как singleton в DI -точке проекта
        (`MaxVibesService`) и прокинуть в конструктор `ClaudeCodeInteractionService` .

## Файл

**Редактировать:**
`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/service/MaxVibesService.kt`

## Изменения

### 1.Импорт

```kotlin
import com . maxvibes . application . service . ClaudeCodeActivityTracker
```

### 2.Добавить публичный singleton

В классе `MaxVibesService` добавить поле рядом с другими сервисами:

```kotlin
/**
 * Transient live-activity tracker for Claude Code mode. Single instance per project.
 * Used by [ClaudeCodeInteractionService] (writer) and [ChatPanel] (reader/listener).
 */
val claudeCodeActivityTracker: ClaudeCodeActivityTracker = ClaudeCodeActivityTracker()
```

Если в проекте используется lazy - init для сервисов(см.memory: "MaxVibesService
— Service Locator / DI "), оставляем eager — конструктор тривиальный, нет смысла lazy.

### 3.Прокинуть в `ClaudeCodeInteractionService`

Найти место, где конструируется `ClaudeCodeInteractionService`(
    lazy property
            или factory -метод
).Добавить аргумент :

```kotlin
ClaudeCodeInteractionService(
    contextProvider = ...,
claudeCodePort = ...,
codeRepository = ...,
notificationPort = ...,
promptPort = ...,
logger = ...,
sessionManager = ...,
chatSessionRepository = ...,
activityTracker = claudeCodeActivityTracker  // ← новый
)
```

## Проверка

```bash
    ./ gradlew : maxvibes -plugin:compileKotlin
    ./ gradlew : maxvibes -plugin:build
```

## Backward compatibility

        -Чистая аддитивная регистрация singleton 'а.
-`ChatPanel` ссылается на `service.claudeCodeActivityTracker` — доступен с момента
        старта проекта .

## Commit

```
feat: register ClaudeCodeActivityTracker in MaxVibesService
```
