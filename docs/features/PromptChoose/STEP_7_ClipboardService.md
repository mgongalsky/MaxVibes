# Step 7: ClipboardInteractionService — проброс specificPromptContent

## Цель

Добавить параметр `specificPromptContent: String?` в `handleUserInput()` и пробросить
его до `ClipboardRequestBuilder.build()`.

## Затрагиваемые файлы

| Файл | Действие |
|------|-------- - |
| `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClipboardInteractionService.kt` | MODIFY |

**Перед изменениями прочитать файл целиком(`FULL`).* *

## Задание

### 1.Найти сигнатуру handleUserInput()

Метод выглядит примерно так :
```kotlin
suspend fun handleUserInput(
    sessionId: String,
    userInput: String,
    isPlanOnly: Boolean,
    addHistory: Boolean,
    attachedContext: String? = null,
    ideErrors: String? = null
): ClipboardStepResult
```

Добавить параметр * * последним * * с дефолтом null:
```kotlin
specificPromptContent: String? = null
```

### 2.Найти все вызовы ClipboardRequestBuilder . build () внутри метода

В каждый вызов добавить :
```kotlin
specificPromptContent = specificPromptContent
```

### 3.Аналогично для redoLastRequest() если в нём есть вызов build()

Прочитать метод — если он вызывает `build()`, добавить параметр аналогично.Для `redoLastRequest` можно передавать `specificPromptContent = null`(
    или пробросить
            через параметр — на усмотрение агента, предпочтительнее пробросить
).

## Проверка

```bash
    ./ gradlew : maxvibes -application:build
    ./ gradlew : maxvibes -application:test
```

Существующие вызовы `handleUserInput(...)` компилируются без изменений .
