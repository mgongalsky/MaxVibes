# Step 6: ClipboardRequestBuilder — инжекция specificPromptContent

## Цель

Добавить параметр `specificPromptContent: String?` в `ClipboardRequestBuilder.build()`
и передать его в `ClipboardRequest.specificPrompt`.

## Затрагиваемые файлы

| Файл | Действие |
|------|-------- - |
| `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClipboardRequestBuilder.kt` | MODIFY |

**Перед изменениями прочитать файл целиком(`FULL`).* *

## Задание

В сигнатуру функции `build(...)` добавить параметр * * последним * * с дефолтом null:
```kotlin
fun build(
    state: ClipboardSessionState,
    freshFiles: Map<String, String>,
    isFirstMessage: Boolean,
    addHistory: Boolean = false,
    planOnlySuffix: String = "",
    ideErrors: String? = null,
    attachedContext: String? = null,
    specificPromptContent: String? = null   // ← новый параметр
): ClipboardRequest
```

В теле `build()` при конструировании `ClipboardRequest(...)` добавить:
```kotlin
specificPrompt = specificPromptContent
```

Важно: `specificPromptContent` передаётся в `ClipboardRequest` * * без фильтрации * * —
даже в minimal - режиме.Специфический промпт нужен LLM в каждом сообщении, чтобы
не потерять контекст задачи .

## Проверка

```bash
    ./ gradlew : maxvibes -application:build
    ./ gradlew : maxvibes -application:test
```

Существующие вызовы `ClipboardRequestBuilder.build(...)` компилируются без изменений
        благодаря дефолтному значению `null` .
