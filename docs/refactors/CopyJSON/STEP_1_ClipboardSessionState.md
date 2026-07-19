# Step 1: Вынести ClipboardSessionState в отдельный файл

## Контекст

Сейчас `ClipboardSessionState` объявлен как `private data class` в конце файла `ClipboardInteractionService.kt`:

```kotlin
// ClipboardInteractionService.kt (нижняя часть)
private data class ClipboardSessionState(
    val currentMessage: String,
    val projectContext: ProjectContext,
    val dialogHistory: MutableList<ChatMessageDTO>,
    val prompts: PromptTemplates,
    val allGatheredFiles: MutableMap<String, String>,
    val attachedContext: String? = null,
    val ideErrors: String? = null,
    var lastInputTokens: Int = 0,
    val planOnly: Boolean = false
)
```

Проблема: `ClipboardRequestBuilder`(
    создаётся на шаге
    2
) должен принимать `ClipboardSessionState` как параметр . Но `private` класс недоступен за пределами файла .

## Задача

Вынести `ClipboardSessionState` в отдельный файл в том же пакете, сделав его `internal`.

## Что делать

### 1.Создать новый файл

**Путь * *: `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClipboardSessionState.kt`

```kotlin
package com.maxvibes.application.service

import com . maxvibes . application . port . output . ChatMessageDTO
        import com . maxvibes . application . port . output . PromptTemplates
        import com . maxvibes . domain . model . context . ProjectContext

        /**
         * In-memory state of an active clipboard dialog session.
         *
         * Accumulates context gathered across multiple round-trips with the LLM:
         * file contents, dialog history, prompts, and token estimates.
         *
         * Scoped to a single clipboard session — created in [ClipboardInteractionService.startTask]
         * and discarded on [ClipboardInteractionService.reset].
         *
         * Marked [internal] so [ClipboardRequestBuilder] can consume it without
         * leaking the type to the plugin layer.
         */
        internal data class ClipboardSessionState(
    /** The user message that started or is continuing the session. */
    val currentMessage: String,
    /** Snapshot of the project taken when the session started. */
    val projectContext: ProjectContext,
    /** Full dialog history for this session (mutated in place). */
    val dialogHistory: MutableList<ChatMessageDTO>,
    /** System prompts resolved at session start. */
    val prompts: PromptTemplates,
    /** All file contents gathered so far, keyed by path. */
    val allGatheredFiles: MutableMap<String, String>,
    /** User-attached context text (stack trace, logs, etc.). */
    val attachedContext: String? = null,
    /** IDE compiler / inspection errors for this turn. */
    val ideErrors: String? = null,
    /** Estimated input tokens for the last request — used for token display. */
    var lastInputTokens: Int = 0,
    /** When true, LLM is instructed to plan only without generating code changes. */
    val planOnly: Boolean = false
)
```

### 2.Удалить `private data class ClipboardSessionState` из `ClipboardInteractionService.kt`

        Удалить весь блок в нижней части файла:
```kotlin
// ==================== Internal State ====================

private data class ClipboardSessionState(...)
```

### 3.Убедиться что импорты не нужны(тот же пакет)

Поскольку оба файла в пакете `com.maxvibes.application.service`, дополнительных импортов не требуется .

## Проверка

```bash
    ./ gradlew : maxvibes -application:compileKotlin
```

Ожидаемый результат : компиляция без ошибок .

## Коммит

```
refactor: extract ClipboardSessionState to separate file
```
