# Step 8: ChatPanelState + ChatMessageController

## Цель

Добавить поля для специфических промптов в `ChatPanelState` и метод выбора промпта
в `ChatMessageController` . Пробросить `specificPromptContent` до `dispatchClipboardMessage`.

## Затрагиваемые файлы

| Файл | Действие |
|------|-------- - |
| `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatPanelState.kt` | MODIFY |
| `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatMessageController.kt` | MODIFY |

**Перед изменениями прочитать оба файла целиком (`FULL`).* *

## Задание

### 1.ChatPanelState.kt

Добавить два поля в конец конструктора :
```kotlin
/**
 * List of available specific prompt names for the dropdown.
 * Does NOT include "Just Code" — UI prepends it.
 */
val availablePrompts: List<String> = emptyList(),

/**
 * Name of the currently selected specific prompt, or null for "Just Code".
 */
val selectedSpecificPromptName: String? = null
```

### 2.ChatMessageController.kt — метод selectSpecificPrompt

        Добавить публичный метод:
```kotlin
/**
 * Updates the selected specific prompt for the currently active session.
 * Null means "Just Code" — no specific prompt.
 */
fun selectSpecificPrompt(name: String?) {
    val session = chatTreeService.getActiveSession() ?: return
    val updated = session.withSelectedPrompt(name)
    chatTreeService.saveSession(updated)
    callbacks.onSessionChanged(updated)
}
```

### 3.ChatMessageController.kt — dispatchClipboardMessage

В методе `dispatchClipboardMessage(...)` перед вызовом `clipboardService.handleUserInput()`
        добавить разрешение контента промпта :

```kotlin
val specificPromptContent = service.specificPromptService
    .resolvePromptContent(chatTreeService.getActiveSession()?.selectedSpecificPromptName)
```

Затем передать в `handleUserInput()` :
```kotlin
specificPromptContent = specificPromptContent
```

### 4.ChatPanel.buildState() — заполнить новые поля

В методе `buildState()` в `ChatPanel.kt` нужно будет добавить :
```kotlin
availablePrompts = service.specificPromptService.getAvailablePromptNames(),
selectedSpecificPromptName = service.specificPromptService
    .validatePromptName(chatTreeService.getActiveSession()?.selectedSpecificPromptName)
```

Примечание: `validatePromptName` проверяет, что файл ещё существует . Если удалён — возвращает null.

## Проверка

```bash
    ./ gradlew : maxvibes -plugin:build
```
