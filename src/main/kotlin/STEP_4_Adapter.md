# STEP 4 — Emit live activity from ClaudeCodeProcessAdapter

## Цель

Реализовать новую сигнатуру `send(request, onActivity)` в адаптере . Эмитить
        activity - события прямо из существующего stdout - loop, без новых потоков и таймеров.

## Файл

**Редактировать:**
`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/claudecode/ClaudeCodeProcessAdapter.kt`

## Изменения

### 1.Добавить импорт

```kotlin
import com . maxvibes . domain . model . interaction . ClaudeCodeActivity
```

### 2.Изменить сигнатуру override `send`

        Заменить:

```kotlin
override suspend fun send(request: ClipboardRequest): Result<ClaudeCodeSendResult, ClaudeCodeError> =
```

на:

```kotlin
override suspend fun send(
    request: ClipboardRequest,
    onActivity: (ClaudeCodeActivity) -> Unit
): Result<ClaudeCodeSendResult, ClaudeCodeError> =
```

### 3.Внутри `sendMutex.withLock { ... }`, после строки

```kotlin
val sendStartedAt = System.currentTimeMillis()
```

добавить безопасный wrapper для callback'а — НЕ хотим, чтобы исключение
из UI -слоя порушило транспорт:

```kotlin
fun emit(activity: ClaudeCodeActivity) {
    try {
        onActivity(activity)
    } catch (e: Exception) {
        MaxVibesLogger.warn(TAG, "onActivity callback threw — ignoring", ex = e)
    }
}
```

### 4.В stdout -loop

Локализовать существующий блок:

```kotlin
StreamJsonProtocol.extractSessionId(line)?.let {
    observedSessionId = it
    MaxVibesLogger.info(TAG, "session id observed", mapOf("sessionId" to it))
}
StreamJsonProtocol.extractAssistantText(line)?.let { txt ->
    accumulated.append(txt)
    MaxVibesLogger.debug(
        TAG, "assistant chunk",
        mapOf("chunkLen" to txt.length, "totalLen" to accumulated.length)
    )
}
```

и дополнить эмитом:

```kotlin
StreamJsonProtocol.extractSessionId(line)?.let {
    observedSessionId = it
    MaxVibesLogger.info(TAG, "session id observed", mapOf("sessionId" to it))
    emit(ClaudeCodeActivity.Started(sendStartedAt, it))
}
StreamJsonProtocol.extractAssistantText(line)?.let { txt ->
    accumulated.append(txt)
    MaxVibesLogger.debug(
        TAG, "assistant chunk",
        mapOf("chunkLen" to txt.length, "totalLen" to accumulated.length)
    )
    emit(ClaudeCodeActivity.Thinking(sendStartedAt, txt))
}
StreamJsonProtocol.extractRateLimitInfo(line)?.let { info ->
    MaxVibesLogger.info(TAG, "rate limit", mapOf("info" to info))
    emit(ClaudeCodeActivity.RateLimit(sendStartedAt, info))
}
```

**Важно:** порядок проверок не меняется — каждый extractor проверяет свой `type`
        самостоятельно, конфликтов нет .

### 5.Edge case : `Started` без session_id на `--resume`

        Claude на resume может не повторять `system/init` событие . В этом случае
        `Started` не будет эмититься, но `Thinking` всё равно придёт при первом
assistant chunk — UI это покрывает : live bubble активируется по любому событию.

## Проверка

```bash
    ./ gradlew : maxvibes -plugin:compileKotlin
    ./ gradlew : maxvibes -plugin:test
```

## Backward compatibility

        -Дефолт `onActivity = {}` на уровне порта(STEP 2) означает, что если кто - то
вызывает `port.send(req)` без второго аргумента — всё работает как раньше .
-Существующее логирование (`MaxVibesLogger.debug "assistant chunk"`) сохранено .

## Commit

```
feat: emit live activity events from ClaudeCodeProcessAdapter
```
