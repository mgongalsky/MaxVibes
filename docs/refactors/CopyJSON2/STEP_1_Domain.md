# Step 1: Добавить requestedFiles в ChatMessage

## Контекст

Для Сценария B в `redoLastRequest` (когда sessionState принадлежит другой сессии)
нужно знать какие файлы ЛЛМ запросил в последнем ответе — чтобы собрать их заново.

Храним пути файлов прямо в сообщении ассистента в домене.

## Файл для изменения

**Путь:** `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/chat/ChatMessage.kt`

```kotlin
data class ChatMessage(
val id: String = UUID.randomUUID().toString(),
val role: MessageRole,
val content: String,
val timestamp: Long = Instant.now().toEpochMilli(),
/**
* File paths requested by the LLM in this ASSISTANT message.
*
* Populated only when the LLM response included a non-empty `requestedFiles` field.
* Used by [ClipboardInteractionService.redoLastRequest] to restore file context
* when the in-memory clipboard workspace belongs to a different session.
*
* Empty for USER and SYSTEM messages.
*/
val requestedFiles: List<String> = emptyList()
)
```

## Проверка

```bash
./gradlew :maxvibes-domain:compileKotlin
./gradlew :maxvibes-domain:test
```

Новое поле с дефолтом не ломает ни один существующий вызов конструктора.

## Коммит

```
feat(domain): add requestedFiles to ChatMessage
```
