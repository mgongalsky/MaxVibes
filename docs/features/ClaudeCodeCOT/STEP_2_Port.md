# STEP 2 — Extend ClaudeCodePort . send with onActivity callback

## Цель

Расширить контракт порта возможностью эмитить live -activity события . * * Обратная
        совместимость обязательна * * — существующие call - сайты и тесты не должны меняться .

## Файл

**Редактировать:**
`maxvibes-application/src/main/kotlin/com/maxvibes/application/port/output/ClaudeCodePort.kt`

## Изменение

Добавить параметр `onActivity: (ClaudeCodeActivity) -> Unit = {}` в метод `send` .

### Было

```kotlin
suspend fun send(request: ClipboardRequest): Result<ClaudeCodeSendResult, ClaudeCodeError>
```

### Станет

```kotlin
/**
 * Sends a single [ClipboardRequest] to the running process and waits
 * for the corresponding response turn to complete.
 *
 * Caller is responsible for calling [ensureStarted] first.
 *
 * @param onActivity optional callback invoked from the transport thread when
 *        intermediate stream-JSON events arrive ([ClaudeCodeActivity.Started],
 *        [ClaudeCodeActivity.Thinking], [ClaudeCodeActivity.RateLimit]).
 *        Implementations MUST treat the callback as fast/non-blocking — UI work
 *        belongs in the caller's listener, not in the callback itself.
 *        Defaults to no-op for backward compatibility with existing call-sites
 *        and tests that do not care about live progress.
 */
suspend fun send(
    request: ClipboardRequest,
    onActivity: (ClaudeCodeActivity) -> Unit = {}
): Result<ClaudeCodeSendResult, ClaudeCodeError>
```

Добавить импорт `com.maxvibes.domain.model.interaction.ClaudeCodeActivity`.

## Проверка

```bash
    ./ gradlew : maxvibes -application:compileKotlin
    ./ gradlew : maxvibes -application:test
```

## Backward compatibility

        -Параметр имеет default - значение → существующие вызовы (`port.send(request)`)
компилируются без изменений.
-Существующие mock -реализации в тестах продолжают работать — MockK сматчит
        вызов по одному аргументу или с любым лямбда -аргументом.
-Адаптер реализует новую сигнатуру в STEP 4.

## Commit

```
feat: extend ClaudeCodePort with onActivity callback
```
