# STEP 2: UI + Controller — чекбокс "Add History" и проброс addHistory

## Контекст

После STEP 1 сервисный слой поддерживает `addHistory`. Теперь нужно:
1. Добавить чекбокс **"Add History"** в `ChatPanel` (видимый только в Clipboard-режиме)
2. Пробросить его значение через `ChatMessageController.sendMessage()`
→ `dispatchClipboardMessage()` → `cs.continueDialog()` / `cs.startTask()`

## Файлы

- `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatPanel.kt`
- `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatMessageController.kt`

## ChatPanel.kt

### 2.1 — Добавить поле чекбокса

Рядом с `planOnlyCheckbox` добавить:

```kotlin
/**
* Галка "Add History": при отправке передаёт LLM список ранее собранных файлов
* (только пути, без содержимого). Используется при старте нового LLM-чата,
* чтобы LLM знала, что уже было в контексте, и могла запросить нужное.
* Видна только в Clipboard-режиме; автоматически сбрасывается после отправки.
*/
private val addHistoryCheckbox = JBCheckBox("Add History").apply {
toolTipText = "Share gathered file list with LLM (use when starting a new LLM chat)"
isVisible = false
}
```

### 2.2 — Добавить в layout (setupUI)

В блоке `SOUTH` input panel (где `planOnlyCheckbox`, `dryRunCheckbox`, `sendButton`):

```kotlin
add(addHistoryCheckbox); add(planOnlyCheckbox); add(dryRunCheckbox); ...
```

### 2.3 — Показывать только в Clipboard режиме

В `InteractionMode.API` и `InteractionMode.CHEAP_API` блоках:
```kotlin
addHistoryCheckbox.isVisible = false
```

В `InteractionMode.CLIPBOARD` блоке:
```kotlin
addHistoryCheckbox.isVisible = true
```

### 2.4 — Сбросить после отправки (sendMessage)

```kotlin
private fun sendMessage() {
val userInput = inputArea.text.trim()
if (userInput.isBlank()) return
// Захватываем ДО сброса
val addHistory = addHistoryCheckbox.isSelected
inputArea.text = ""
// One-shot: сбрасываем автоматически после каждой отправки
addHistoryCheckbox.isSelected = false
messageController.sendMessage(
userInput,
planOnlyCheckbox.isSelected,
dryRunCheckbox.isSelected,
modeManager.currentMode,
addHistory  // новый параметр
)
}
```

### 2.5 — Включить в setInputEnabled

```kotlin
addHistoryCheckbox.isEnabled = enabled
```

## ChatMessageController.kt

### 2.6 — sendMessage: добавить параметр

```kotlin
fun sendMessage(
userInput: String,
isPlanOnly: Boolean,
isDryRun: Boolean,
mode: InteractionMode,
addHistory: Boolean = false  // соответствует галке "Add History"
)
```

Добавить в лог:
```kotlin
"addHistory" to addHistory
```

Передать в:
```kotlin
InteractionMode.CLIPBOARD -> dispatchClipboardMessage(userInput, trace, errs, isPlanOnly, addHistory)
```

### 2.7 — dispatchClipboardMessage: добавить параметр

```kotlin
private fun dispatchClipboardMessage(
userInput: String,
trace: String?,
errs: String?,
isPlanOnly: Boolean,
addHistory: Boolean = false  // соответствует галке "Add History"
)
```

В ветке `cs.hasActiveSession()` — передать в `cs.continueDialog(..., addHistory = addHistory)`.

В ветке `else` (новая сессия) — передать в `cs.startTask(..., addHistory = addHistory)`.

В ветке `cs.isWaitingForResponse()` — `addHistory` игнорируется (не релевантен при вставке ответа).

## Как проверить

### Компиляция
```bash
./gradlew :maxvibes-plugin:compileKotlin
```

### Ручная проверка в IDE

1. Запустить плагин (Run Plugin конфигурация в IntelliJ)
2. Открыть MaxVibes панель
3. Переключиться в **Clipboard** режим
4. Убедиться что чекбокс **Add History** появился рядом с Plan
5. Переключиться в API режим → чекбокс скрылся
6. Вернуться в Clipboard, написать любое сообщение, **без галки** Add History → Generate
7. Убедиться что в JSON поле `previouslyGatheredFiles` пусто (минимум токенов)
8. Поставить галку **Add History**, отправить следующее сообщение → Generate
9. Убедиться что `previouslyGatheredFiles` содержит пути ранее собранных файлов
10. Убедиться что `files` при этом пуст (содержимое не дублируется)
11. Убедиться что после отправки галка сама снялась

### Граничные случаи

- Новая сессия (нет собранных файлов): Add History ON — `previouslyGatheredFiles` = []
- Waiting for paste: Add History проигнорирован — правильно

## Коммит

```
feat(ui): add "Add History" checkbox to ChatPanel for Clipboard mode

Checkbox appears only in Clipboard mode. When checked, previously
gathered file paths are included in the request so a fresh LLM chat
can re-request whatever it needs. Auto-resets after each send (one-shot).
File contents are not re-sent — LLM requests them via requestedFiles.
```
