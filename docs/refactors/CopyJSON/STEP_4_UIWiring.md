# Step 4: Подключить Copy JSON к новому методу +удалить старый кэш

## Контекст

После Step 3 `ClipboardInteractionService.redoLastRequest()` готов.Этот шаг — финальный : подключаем кнопку к сервису через контроллер и удаляем устаревший код.

## Задача

### 1.Добавить `redoClipboardJson()` в `ChatMessageController`

**Файл * *: `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatMessageController.kt`

Добавить в секцию `// ==================== Clipboard Mode ====================` :

```kotlin
/**
 * Re-generates and copies the clipboard JSON for the current active session.
 *
 * Runs in a background task — identical to what Generate produces:
 * re-gathers project files and rebuilds the full JSON payload via
 * [ClipboardInteractionService.redoLastRequest].
 *
 * Does NOT add a new user message to history.
 * Result is routed through [handleClipboardResult] — same as Generate.
 */
fun redoClipboardJson() {
    val session = chatTreeService.getActiveSession()
    val globalContextFiles = chatTreeService.getGlobalContextFiles()
    callbacks.setInputEnabled(false)
    runClipboardBg("Re-generating JSON...", session) {
        service.clipboardService.redoLastRequest(session.id, globalContextFiles)
    }
}
```

### 2.Обновить listener кнопки Copy JSON в `ChatPanel`

**Файл * *: `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatPanel.kt`

**До:**
```kotlin
copyJsonButton.addActionListener {
    if (service.clipboardService.recopyLastRequest()) {
        statusLabel.text = "JSON re-copied to clipboard"
    } else {
        statusLabel.text = "Nothing to copy"
    }
}
```

**После:**
```kotlin
copyJsonButton.addActionListener {
    messageController.redoClipboardJson()
    // Status and mode indicator are updated by handleClipboardResult via callbacks
}
```

> **Примечание:** статус и индикатор обновятся автоматически через `handleClipboardResult` → `callbacks.setStatus()` + `callbacks.updateModeIndicator()`.Отдельный `statusLabel.text` здесь не нужен.

### 3.Удалить устаревший кэш из `ClipboardInteractionService`

**Файл * *: `maxvibes-application/.../service/ClipboardInteractionService.kt`

Удалить:
-поле: `private var lastRequest: ClipboardRequest? = null`
-присваивание в `generateAndCopyJson()`: `lastRequest = request`
-метод:
```kotlin
fun recopyLastRequest(): Boolean {
    val req = lastRequest ?: return false
    return clipboardPort.copyRequestToClipboard(req)
}
```

## Проверка

### Компиляция

```bash
    ./ gradlew : maxvibes -application:compileKotlin
    ./ gradlew : maxvibes -plugin:compileKotlin
```

### Ручное тестирование в IDE

**Сценарий 1 — базовый:**
1.Переключиться в Clipboard mode
        2.Ввести сообщение → нажать Generate
3.Статус → AWAITING_PASTE, кнопка Copy JSON видна
        4.Нажать Copy JSON → должен запуститься background task (прогресс - бар в IDE)
5.После завершения буфер обмена содержит полный JSON — такой же как при Generate
6.Статус снова AWAITING_PASTE, кнопка Copy JSON видна

**Сценарий 2 — баг с переключением сессий : * *
1.Сессия A : Generate → AWAITING_PASTE
        2.Переключиться на сессию B (статус IDLE, кнопка Copy JSON скрыта)
3.Переключиться обратно на сессию A
4.Нажать Copy JSON → JSON должен содержать данные * * сессии A * *

**Сценарий 3 — Copy JSON без активной сессии:**
1.Переключиться на сессию, которая никогда не делала Generate
2.Убедиться что кнопка Copy JSON скрыта (статус IDLE → `copyJsonButton.isVisible = false`)
3.Если вызвать `redoLastRequest` программно — вернётся `ClipboardStepResult.Error`

**Сценарий 4 — Copy JSON в чужой сессии(защита sessionStateOwner):**
1.Сессия A : Generate → AWAITING_PASTE
        2.Сессия B : Generate → sessionState перезаписывается, sessionStateOwner = B
3.Переключиться на A, нажать Copy JSON
4.Ожидаемо: `ClipboardStepResult.Error("No active clipboard session for this chat.")` → статус `"Nothing to copy"` (можно добавить setStatus в `redoClipboardJson` при Error)

> **Опционально:** в `redoClipboardJson()` можно обработать `ClipboardStepResult.Error` явно :
> ```kotlin
> runClipboardBg("Re-generating JSON...", session) {
    >
    val result = service.clipboardService.redoLastRequest(session.id, globalContextFiles)
    >     if (result is ClipboardStepResult.Error) {
    >         // handleClipboardResult уже покажет ошибку — ничего дополнительного не нужно
    >
}
    >     result
    >
}
> ```
> Это уже покрыто `handleClipboardResult` → `ClipboardStepResult.Error` ветка.

## Коммит

```
feat: wire Copy JSON button to redoClipboardJson, remove lastRequest cache
```

## После всех шагов

```bash
    ./ gradlew : maxvibes -application:test
    ./ gradlew : maxvibes -domain:test
    ./ gradlew : maxvibes -shared:test
```

Все тесты зелёные.Запустить плагин через IntelliJ IDEA runner и пройти сценарии 1–4 из этого файла .
