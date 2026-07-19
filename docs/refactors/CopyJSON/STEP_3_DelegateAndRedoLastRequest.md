# Step 3: Делегировать в builder + добавить redoLastRequest ()

## Контекст

После Step 2 `ClipboardRequestBuilder` создан и протестирован.Сейчас `generateAndCopyJson()` в `ClipboardInteractionService` содержит дублирующую логику сборки `ClipboardRequest` — её нужно заменить вызовом builder'а.

Заодно добавляем :
-`sessionStateOwner: String?` — защита от использования чужого sessionState
-`redoLastRequest()` — suspend - метод для кнопки Copy JSON

## Задача

### 1.Добавить поле `sessionStateOwner`

В `ClipboardInteractionService`, рядом с `private var sessionState`:

```kotlin
/** ID сессии, которой принадлежит текущий [sessionState]. */
private var sessionStateOwner: String? = null
```

### 2.Выставлять `sessionStateOwner` в `startTask()`

        После строки `sessionState = ClipboardSessionState(...)`:

```kotlin
sessionStateOwner = sessionId
```

### 3.Сбрасывать `sessionStateOwner` в `reset()`

        Добавить в `reset()`:

```kotlin
sessionStateOwner = null
```

### 4.Заменить тело `generateAndCopyJson()` — делегировать в builder

Сейчас в `generateAndCopyJson()` есть большой блок, вручную собирающий поля `ClipboardRequest` . Заменить его на вызов builder 'а.

**До(упрощённо):**
```kotlin
private fun generateAndCopyJson(
    sessionId: String,
    freshFiles: Map<String, String>,
    isFirstMessage: Boolean,
    assistantMessage: String? = null,
    llmReasoning: String? = null,
    addHistory: Boolean = false
): ClipboardStepResult {
    val state = sessionState ?: return error("No active session")
    val isMinimal = !isFirstMessage && !addHistory
    val previousPaths = ...
    // ... 30+ строк ручной сборки ...
    val request = ClipboardRequest(
        phase = ...,
    currentMessage = taskContent,
    ...
    )
    lastRequest = request   // <-- удалить это
    val copied = clipboardPort.copyRequestToClipboard(request)
    ...
}
```

**После:**
```kotlin
private fun generateAndCopyJson(
    sessionId: String,
    freshFiles: Map<String, String>,
    isFirstMessage: Boolean,
    assistantMessage: String? = null,
    llmReasoning: String? = null,
    addHistory: Boolean = false
): ClipboardStepResult {
    val state = sessionState ?: return error("No active session")

    // Delegate all assembly logic to the pure builder
    val request = ClipboardRequestBuilder.build(
        state = state,
        freshFiles = freshFiles,
        isFirstMessage = isFirstMessage,
        addHistory = addHistory,
        planOnlySuffix = PLAN_ONLY_SUFFIX
    )

    val copied = clipboardPort.copyRequestToClipboard(request)
    val copyStatus = if (copied) "copied to clipboard" else "generated (copy manually)"

    val totalTokens = estimateTokens(request)
    state.lastInputTokens = totalTokens

    log("JSON ready: $copyStatus, ~$totalTokens tokens")

    sessionManager.transition(sessionId, ClipboardEvent.JsonCopied)

    return ClipboardStepResult.WaitingForResponse(
        phase = request.phase,
        statusMessage = "JSON $copyStatus. Paste into Claude/ChatGPT, then paste the response back here.",
        assistantMessage = assistantMessage,
        jsonRequest = request,
        estimatedInputTokens = totalTokens,
        llmReasoning = llmReasoning,
        freshFileNames = freshFiles.keys.map { it.substringAfterLast('/') },
        previouslyGatheredCount = request.previouslyGatheredPaths.size
    )
}
```

> **Важно:** `log()` внутри builder'а не нужен — он pure. Строчку логирования оставляем в `ClipboardInteractionService`.

### 5.Добавить `redoLastRequest()`

        Добавить в public API `ClipboardInteractionService`, после `recopyLastRequest()` (он будет удалён на шаге 4):

```kotlin
/**
 * Re-gathers project files and rebuilds the full JSON request for the given session.
 *
 * Produces an identical payload to what the Generate button produces.
 * Does NOT add a new user message to chat history.
 * Returns [ClipboardStepResult.Error] if:
 * - no active clipboard session exists for this sessionId
 * - the in-memory session state belongs to a different chat
 *
 * @param sessionId          The ID of the current chat session.
 * @param globalContextFiles Paths to always include as fresh files.
 */
suspend fun redoLastRequest(
    sessionId: String,
    globalContextFiles: List<String>
): ClipboardStepResult {
    if (sessionStateOwner != sessionId || sessionState == null) {
        return ClipboardStepResult.Error("No active clipboard session for this chat.")
    }
    val freshFiles = gatherRequestedFiles(globalContextFiles) ?: emptyMap()
    return generateAndCopyJson(
        sessionId = sessionId,
        freshFiles = freshFiles,
        isFirstMessage = false
    )
}
```

## Проверка

```bash
    ./ gradlew : maxvibes -application:compileKotlin
    ./ gradlew : maxvibes -application:test
```

Ожидаемый результат : компиляция без ошибок, все тесты зелёные.Дополнительно вручную в IDE :
1.Отправить сообщение в Clipboard mode → Generate → убедиться что JSON копируется корректно
2.Убедиться что статус переходит в AWAITING_PASTE

## Коммит

```
refactor: delegate generateAndCopyJson to ClipboardRequestBuilder, add redoLastRequest
```
