# Step 5: Persistence — ChatSession + XmlChatSession

## Цель

Добавить `selectedSpecificPromptName: String?` в доменную модель `ChatSession`
        и обеспечить её сохранение / восстановление через `XmlChatSession` в `ChatHistoryService` .

## Затрагиваемые файлы

| Файл | Действие |
|------|-------- - |
| `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/chat/ChatSession.kt` | MODIFY — добавить поле и метод |
| `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/chat/ChatHistoryService.kt` | MODIFY — XmlChatSession + toDomain / fromDomain |

**Перед изменениями прочитать оба файла целиком (`FULL`).* *

## Задание

### 1.ChatSession.kt

Добавить поле * * последним * * в конструктор(backward compat):
```kotlin
/**
 * Name of the currently selected specific prompt for this session.
 * Null means "Just Code" — no specific prompt is active.
 */
val selectedSpecificPromptName: String? = null
```

Добавить метод -копировщик рядом с `withClipboardStatus` :
```kotlin
/**
 * Returns a new session with [selectedSpecificPromptName] updated and [updatedAt] refreshed.
 */
fun withSelectedPrompt(name: String?): ChatSession =
    copy(selectedSpecificPromptName = name, updatedAt = Instant.now().toEpochMilli())
```

### 2.ChatHistoryService.kt — XmlChatSession

В класс `XmlChatSession` добавить поле после `clipboardStatus`:
```kotlin
/**
 * Selected specific prompt name for this session.
 * Empty string = null ("Just Code"). Default empty for backward compat.
 */
@Attribute("selectedSpecificPromptName")
var selectedSpecificPromptName: String = ""
```

Примечание: `@Attribute` с пустой строкой как дефолтом — при чтении старых XML, где атрибута нет,
IntelliJ вернёт пустую строку, что мы конвертируем в `null`.

### 3.ChatHistoryService.kt — XmlChatSession.toDomain()

В `toDomain()` добавить маппинг :
```kotlin
selectedSpecificPromptName = this.selectedSpecificPromptName.takeIf { it.isNotEmpty() }
```

### 4.ChatHistoryService.kt — XmlChatSession.fromDomain()

В `fromDomain(session: ChatSession)` добавить:
```kotlin
xml.selectedSpecificPromptName = session.selectedSpecificPromptName ?: ""
```

## Проверка

```bash
    ./ gradlew : maxvibes -domain:build
    ./ gradlew : maxvibes -plugin:build
```

Старые XML -файлы(`maxvibes-chat-history.xml`) читаются без ошибок :
отсутствие атрибута `selectedSpecificPromptName` → пустая строка → null в домене → «Just Code ».
