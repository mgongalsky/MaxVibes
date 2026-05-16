# STEP 6 — Wire live activity in ClaudeCodeInteractionService

## Цель

Прокинуть callback из `doSend` в `port.send`, обновляя `ClaudeCodeActivityTracker`
        для текущей сессии.Гарантировать `clear` в finally — без него зависший bubble
остаётся навсегда при ошибке .

## Файл

**Редактировать:**
`maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClaudeCodeInteractionService.kt`

## Изменения

### 1.Добавить параметр конструктора

Добавить в primary - конструктор последним параметром:

```kotlin
private val activityTracker: ClaudeCodeActivityTracker
```

Импорт уже в том же пакете — не нужен.

### 2.Добавить импорт

```kotlin
import com . maxvibes . domain . model . interaction . ClaudeCodeActivity
```

### 3.Изменить вызов `claudeCodePort.send` в `doSend`

Заменить:

```kotlin
val sendResult = claudeCodePort.send(request)
```

на блок с callback и гарантированным clear:

```kotlin
val sendResult = try {
    claudeCodePort.send(request) { activity ->
        // Callback fires from the transport IO thread — Tracker is thread-safe,
        // listeners (UI) dispatch to EDT themselves.
        activityTracker.update(sessionId, activity)
    }
} finally {
    activityTracker.clear(sessionId)
}
```

**Важно:** `clear` в `finally` ловит ВСЕ пути выхода — успех, transport error,
неведомая ошибка . Bubble всегда уходит .

### 4.Очистка при `reset`

В методе `reset(sessionId: String)`, после строки

```kotlin
sessionState = null
```

добавить:

```kotlin
activityTracker.clear(sessionId)
```

Это на случай, если пользователь нажал Reset во время очень редкого
        рассинхрона между transport thread и main flow.

## Проверка

```bash
    ./ gradlew : maxvibes -application:compileKotlin
    ./ gradlew : maxvibes -application:test
```

Существующие тесты сервиса должны быть обновлены : в их фикстурах нужно создать
        и передать `ClaudeCodeActivityTracker()`(это чистая реализация, не моккается).

## Backward compatibility

        -Новый параметр конструктора → DI - сайт(`MaxVibesService`) обновляется в STEP 8.
-Существующие unit -тесты сервиса : добавить `ClaudeCodeActivityTracker()` в
        конструктор.См.STEP 9 для деталей тестов .

## Commit

```
feat: wire live activity callback in ClaudeCodeInteractionService
```
