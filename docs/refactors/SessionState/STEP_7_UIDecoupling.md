# STEP 7: Plugin — UI, убираем прямые обращения к сервису

## Контекст

Последний крупный шаг.Убираем два оставшихся нарушения инкапсуляции:
1.`updateModeUI()` в `ChatPanel` читает `service.clipboardService.isWaitingForResponse()` и `service.clipboardService.getCurrentPhase()` напрямую
        2.`ChatMessageController.dispatchClipboardMessage()` содержит `when { cs.isWaitingForResponse() ... }` — роутинг по флагам сервиса

        После этого шага UI только вызывает `cs.handleUserInput()` и рендерит снапшот `ChatPanelState`.См.общий план : `docs/refactors/SessionState/PLAN.md`

## Что делаем

### 1.`ChatPanel.updateModeUI()` — читать из `ChatPanelState`

**Путь:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatPanel.kt`

Изменить сигнатуру :
```kotlin
// Было:
private fun updateModeUI(mode: InteractionMode)

// Стало:
private fun updateModeUI(state: ChatPanelState)
```

Внутри заменить :
```kotlin
// Было:
cs.isWaitingForResponse() → ...
cs.hasActiveSession() → ...
cs.getCurrentPhase() → ...

// Стало:
state.clipboardStatus == ClipboardSessionStatus.AWAITING_PASTE → ...
state.clipboardStatus == ClipboardSessionStatus.SESSION_ACTIVE → ...
// getCurrentPhase() убрать или оставить через session если нужен
```

Обновить все места вызова `updateModeUI()` — теперь передаём `buildState()` или `state`:
-В `render(state)` → `updateModeUI(state)`
        -В `updateModeIndicator()` (override) → `updateModeUI(buildState())`

### 2.`ChatMessageController.dispatchClipboardMessage()` — один вызов

**Путь:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatMessageController.kt`

Заменить весь `when { }` блок роутинга:
```kotlin
// Было:
when {
    cs.isWaitingForResponse() -> {
        ... cs.handlePastedResponse(userInput)
    }

    cs.hasActiveSession() -> {
        ... cs.continueDialog(...)
    }

    else -> {
        ... cs.startTask(...)
    }
}

// Стало:
val session = chatTreeService.getActiveSession()
runClipboardBg("Processing...", session) {
    cs.handleUserInput(
        sessionId = session.id,
        userInput = userInput,
        history = session.messages.map { it.toChatMessageDTO() },
        attachedContext = trace,
        planOnly = isPlanOnly,
        ideErrors = errs,
        globalContextFiles = globalContextFiles,
        addHistory = addHistory
    )
}
```

UI - операции(
    addUserMessageBubble,
    setInputEnabled,
    setStatus
) которые ранее были разнесены по веткам `when` — вынести до вызова `runClipboardBg()`, так как они одинаковы для всех веток:
```kotlin
callbacks.addUserMessageBubble(userInput)   // всегда показываем пузырь
callbacks.setInputEnabled(false)
callbacks.setStatus("Processing...")
```

_Исключение:_ для `AWAITING_PASTE` раньше не добавлялся пузырь пользователя (это ответ LLM).Теперь `handleUserInput()` сам знает что делает — но UI всё равно показывает что пользователь что - то отправил . Проверить визуально что это выглядит разумно.Альтернатива: передать флаг из `ClipboardStepResult.WaitingForResponse` чтобы управлять отображением.

### 3.Убрать вызовы `resetClipboard()` при переключении сессий

        В `ChatPanel` найти все вызовы `resetClipboard()` / `service.clipboardService.reset()` при переключении :
-`newChatButton.addActionListener` → убрать `resetClipboard()`
        -`branchButton.addActionListener` → убрать `resetClipboard()`
        -`deleteCurrentChat()` → убрать `resetClipboard()`
        -`loadCurrentSession()` — если был явный `reset()` при загрузке → убрать

        Теперь статус привязан к конкретной сессии в домене и не сбрасывается при переключении.

### 4.Обновить `ChatPanel.resetClipboard()`

        Метод остаётся, но теперь принимает `sessionId` :
```kotlin
fun resetClipboard(sessionId: String) {
    service.clipboardService.reset(sessionId)
}
```

Вызывать только там где реально нужен сброс(явный новый чат — но статус `IDLE` и так будет у новой сессии, поэтому, вероятно, метод больше не нужен совсем).

## Потенциальные проблемы и что проверять

**Проблема 1: Пузырь при вставке ответа * *
        Раньше при `isWaitingForResponse()` не добавлялся `addUserMessageBubble()` . Теперь UI всегда добавляет пузырь . Проверить что при вставке ответа LLM в conversationPanel не появляется лишний пузырь пользователя с текстом JSON - ответа.

*Решение:* Сделать `addUserMessageBubble()` условным в зависимости от `state.clipboardStatus`:
```kotlin
if (state.clipboardStatus != ClipboardSessionStatus.AWAITING_PASTE) {
    callbacks.addUserMessageBubble(userInput)
}
```

**Проблема 2: Status label при разных сценариях * *
`setStatus()` раньше имел разные тексты для разных веток . Убедиться что универсальный `"Processing..."` достаточно информативен, или сделать текст зависимым от `state.clipboardStatus` .

**Проблема 3: updateModeIndicator() override * *
        Метод `updateModeIndicator()` в `ChatPanelCallbacks` вызывается из `handleClipboardResult()` без параметров.После рефакторинга он должен внутри вызывать `updateModeUI(buildState())`.Убедиться что `buildState()` вызывается на EDT .

## Проверка

```bash
    ./ gradlew : maxvibes -plugin:compileKotlin
```

### Полный ручной тест clipboard workflow:
1.Clipboard - режим, новая сессия → кнопка "Generate", статус IDLE
        2.Отправить сообщение → JSON в буфере, кнопка "Paste", статус AWAITING_PASTE
        3.Переключиться на другую сессию → у неё своё состояние(IDLE)
4.Вернуться на первую → кнопка снова "Paste"(статус восстановлен из домена)
5.Вставить ответ LLM → статус SESSION_ACTIVE, кнопка "Send / Paste"
6.Отправить следующее сообщение → continueDialog, JSON в буфере
7.Создать новый чат → у него статус IDLE, первый чат не пострадал

## Коммит

```
refactor(plugin): decouple UI from clipboard service internal state
```
