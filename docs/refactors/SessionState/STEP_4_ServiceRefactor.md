# STEP 4: Application — рефакторинг ClipboardInteractionService

## Контекст

После STEP 3 у нас есть готовый `ClipboardSessionManager` . Теперь подключаем его к `ClipboardInteractionService` : убираем `waitingForPaste: Boolean`, заменяем роутинг, добавляем единую точку входа `handleUserInput()`.Это самый рискованный шаг — затрагивает core логику clipboard - режима.Важно: на этом шаге `MaxVibesService` ещё не передаёт `sessionManager` в сервис . Чтобы не сломать компиляцию плагина, используем временный подход(
    см.ниже
).См.общий план : `docs/refactors/SessionState/PLAN.md`

## Что делаем

### 1.Добавить зависимость `ClipboardSessionManager` в конструктор

**Путь:** `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClipboardInteractionService.kt`

Добавить параметр :
```kotlin
private val sessionManager: ClipboardSessionManager
```

Чтобы не сломать `MaxVibesService` (который создаёт сервис без менеджера — до STEP 5), сделать параметр с дефолтом :
```kotlin
private val sessionManager: ClipboardSessionManager? = null
```

В STEP 5 дефолт будет убран и менеджер станет обязательным .

### 2.Убрать `waitingForPaste: Boolean`

        Удалить:
```kotlin
private var waitingForPaste: Boolean = false
```

Вместо прямого чтения этого флага использовать :
```kotlin
private fun currentStatus(sessionId: String): ClipboardSessionStatus =
    sessionManager?.statusFor(sessionId) ?: ClipboardSessionStatus.IDLE
```

### 3.Заменить вызовы `waitingForPaste = ...` на `sessionManager.transition()`

В каждом методе где статус менялся вручную:

-`startTask()` в начале: `sessionManager?.transition(sessionId, ClipboardEvent.StartSession)`
-`generateAndCopyJson()` перед возвратом `WaitingForResponse` : `sessionManager?.transition(sessionId, ClipboardEvent.JsonCopied)`
        -`handlePastedResponseInternal()` в начале: вместо `waitingForPaste = false` вызвать `sessionManager?.transition(sessionId, ClipboardEvent.ResponsePasted)`; если возвращает `false`(
    невалидный переход
) — вернуть `ClipboardStepResult.Error`
        -`reset()`: `sessionManager?.transition(sessionId, ClipboardEvent.Reset)`

### 4.Все публичные методы получают `sessionId: String`

Обновить сигнатуры :
```kotlin
suspend fun startTask(sessionId: String, currentMessage: String, ...): ClipboardStepResult
suspend fun continueDialog(sessionId: String, message: String, ...): ClipboardStepResult
suspend fun handlePastedResponse(sessionId: String, rawText: String): ClipboardStepResult
fun reset(sessionId: String)
fun status(sessionId: String): ClipboardSessionStatus
```

Методы `isWaitingForResponse()` и `hasActiveSession()` помечаем `@Deprecated` но пока не удаляем — для плавного перехода :
```kotlin
@Deprecated("Use status(sessionId) instead")
fun isWaitingForResponse(): Boolean = false  // заглушка

@Deprecated("Use status(sessionId) instead")
fun hasActiveSession(): Boolean = false  // заглушка
```

_Примечание:_ заглушки возвращают безопасные дефолты.Они будут удалены в STEP 8.

### 5.Добавить единую точку входа `handleUserInput()`

```kotlin
/**
 * Единая точка входа из UI для clipboard-режима.
 * Самостоятельно определяет нужное действие на основе текущего статуса сессии.
 * UI не должен знать о внутренних состояниях — только вызывает этот метод.
 */
suspend fun handleUserInput(
    sessionId: String,
    userInput: String,
    history: List<ChatMessageDTO> = emptyList(),
    attachedContext: String? = null,
    planOnly: Boolean = false,
    ideErrors: String? = null,
    globalContextFiles: List<String> = emptyList(),
    addHistory: Boolean = false
): ClipboardStepResult = when (currentStatus(sessionId)) {
    ClipboardSessionStatus.AWAITING_PASTE -> handlePastedResponse(sessionId, userInput)
    ClipboardSessionStatus.SESSION_ACTIVE -> continueDialog(
        sessionId,
        userInput,
        attachedContext,
        planOnly,
        ideErrors,
        globalContextFiles,
        addHistory
    )

    ClipboardSessionStatus.IDLE -> startTask(
        sessionId,
        userInput,
        history,
        attachedContext,
        planOnly,
        ideErrors,
        globalContextFiles,
        addHistory
    )
}
```

### 6.Метод `recopyLastRequest()` — без изменений

Не зависит от статуса, оставить как есть.

## Тесты

Добавить в `maxvibes-application` тест на `handleUserInput()` диспетчеризацию(используя моки `ClipboardSessionManager` и stub - реализации портов):

1.При статусе `IDLE` → вызывается `startTask` -ветка(проверить по результату `WaitingForResponse` с новым JSON)
2.При статусе `AWAITING_PASTE` → вызывается `handlePastedResponse` -ветка
3.При статусе `SESSION_ACTIVE` → вызывается `continueDialog` -ветка
4.`handlePastedResponse()` при невалидном переходе (`ResponsePasted` из `IDLE`) → возвращает `ClipboardStepResult.Error`

        _Примечание:_ полноценное тестирование `startTask` требует стабов для `ProjectContextPort`, `ClipboardPort` и т.д.Если инфраструктура стабов отсутствует, покрыть только диспетчеризацию через `currentStatus()` с мок - менеджером.

## Проверка

```bash
    ./ gradlew : maxvibes -application:compileKotlin
    ./ gradlew : maxvibes -application:test
    ./ gradlew : maxvibes -plugin:compileKotlin   # не должен сломаться
```

## Коммит

```
refactor(application): route ClipboardInteractionService state through ClipboardSessionManager
```
