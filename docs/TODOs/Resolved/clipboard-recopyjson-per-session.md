# TODO: Per - session "Copy JSON" via domain

## Проблема

Кнопка * * Copy JSON * *(перекопировать последний сгенерированный JSON) работает некорректно при
        наличии нескольких активных Clipboard -сессий.`ClipboardInteractionService.lastRequest` — одно поле на весь сервис.При переключении чатов
кнопка всегда копирует JSON последней сессии, которая вызывала `generateAndCopyJson()`, а не
        текущей открытой .

## Правильное решение

        По аналогии с тем, как `clipboardStatus` был перенесён в доменную модель(`ChatSession.clipboardStatus`),
последний сгенерированный запрос тоже должен храниться * * в домене * * :

### 1.Доменная модель (`maxvibes-domain`)

Добавить поле в `ChatSession` :
```kotlin
val lastClipboardRequest: String? = null  // сериализованный JSON или структура
```

Альтернативно — хранить не сырой JSON, а структуру `ClipboardRequest`(
    или её сериализованную
            форму
), чтобы перекодировать при необходимости .

### 2.Порт(`maxvibes-application`)

Добавить методы в `ChatSessionRepository` :
```kotlin
fun saveLastClipboardRequest(sessionId: String, requestJson: String)
fun getLastClipboardRequest(sessionId: String): String?
```

Или обновлять сессию через `ChatTreeService`:
```kotlin
fun updateLastClipboardRequest(sessionId: String, requestJson: String): ChatSession
```

### 3.Сервис(`ClipboardInteractionService`)

В `generateAndCopyJson()` после кодирования запроса вызывать :
```kotlin
chatSessionRepository.saveLastClipboardRequest(sessionId, encodedJson)
```

`recopyLastRequest(sessionId)` — читает из репозитория:
```kotlin
fun recopyLastRequest(sessionId: String): Boolean {
    val json = chatSessionRepository.getLastClipboardRequest(sessionId) ?: return false
    return clipboardPort.copyRawToClipboard(json)
}
```

### 4.Persistence(`ChatHistoryService`)

Сериализовать / десериализовать новое поле в XML.

## Затронутые файлы

        -`maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/chat/ChatSession.kt`
-`maxvibes-application/src/main/kotlin/com/maxvibes/application/port/output/ChatSessionRepository.kt`
-`maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ChatTreeService.kt`
-`maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClipboardInteractionService.kt`
-`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/chat/ChatHistoryService.kt`
-`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatPanel.kt`(вызов `recopyLastRequest`)

## Текущий статус

        Временный обходной путь * * не применён * * — проблема зафиксирована для правильного решения через домен.При нажатии Copy JSON в сессии без сохранённого запроса показывается "Nothing to copy".
