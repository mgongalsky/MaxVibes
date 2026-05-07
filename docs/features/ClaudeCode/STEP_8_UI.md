# Step 8 — UI: режим Claude Code + Approve - кнопка

## Цель

Добавить UI для нового режима: четвёртая опция в `InteractionModeManager`, роутинг в `ChatMessageController`, кнопка * * Approve * * в `ChatPanel`, индикатор «Sending to Claude Code …» в статус -строке.

## Затрагиваемые файлы

| Файл | Действие |
|------|----------|
| `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/InteractionModeManager.kt` | MODIFY |
| `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatMessageController.kt` | MODIFY |
| `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatPanel.kt` | MODIFY |
| `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatPanelState.kt` | MODIFY |

Зависит от Step 5, 6, 7.

## Контекст

-`InteractionModeManager` — state machine выбора режима, синхронизирована с `MaxVibesSettings`.Сейчас имеет методы `isClipboardMode`, `isApiMode`, `isCheapApiMode`.
-`ChatMessageController` — роутер: `dispatchUserMessage(...)` решает, куда отправить — в API / Clipboard / CheapAPI сервис .
-`ChatPanel` — view, рендерит state и роутит UI events . Имеет кнопки Send / Stop / Reset / Copy JSON / Paste Response .
-`ChatPanelState` — снимок состояния для render : текущий режим, статус, видимость кнопок, message draft и т . д .

Добавляем:
-В `InteractionModeManager` — `isClaudeCodeMode()` .
-В `ChatMessageController` — ветку для CLAUDE_CODE в `dispatchUserMessage` .
-В `ChatPanelState` — `claudeCodeApproveVisible: Boolean` .
-В `ChatPanel` — кнопку Approve, обработчик нажатия .

## Изменения

### 8.1 `InteractionModeManager.kt`

        Прочитать `FULL` . Добавить метод:

```kotlin
/**
 * Returns true if the current mode is Claude Code (local CLI process).
 */
fun isClaudeCodeMode(): Boolean = currentMode == InteractionMode.CLAUDE_CODE
```

Если в `switchMode` есть `when (newMode)` с явными ветками — добавить ветку CLAUDE_CODE . Если общая логика подходит — оставить как есть.Проверить, что `readModeFromSettings()` корректно мапит сохранённое значение `"CLAUDE_CODE"` в enum(
    если используется `enumValueOf`,
    то автоматически
).

### 8.2 `ChatMessageController.kt`

        Прочитать `FULL` . Найти метод вроде `dispatchUserMessage(text: String)` или `sendMessage(text: String)`.Сейчас он делает что -то вроде :

```kotlin
when {
    modeManager.isClipboardMode() -> dispatchClipboardMessage(...
        )
        modeManager.isCheapApiMode()

    -> dispatchCheapApiMessage(...
        )
        modeManager.isApiMode()

    -> dispatchApiMessage(...
        )
}
```

Добавить ветку CLAUDE_CODE * * первой * * (или в зависимости от существующего порядка):

```kotlin
when {
    modeManager.isClaudeCodeMode() -> dispatchClaudeCodeMessage(text, ...
        )
        modeManager.isClipboardMode()

    -> dispatchClipboardMessage(...
        )
    // ...
}
```

И добавить новый приватный метод `dispatchClaudeCodeMessage`, который:
1.Берёт текущую сессию
2.Зовёт `claudeCodeInteractionService.handleUserInput(...)` в корутине
        3.На результат — рендерит в чат через те же helper 'ы, что используются для `ClipboardStepResult.Completed` / `WaitingForResponse`

```kotlin
private fun dispatchClaudeCodeMessage(
    text: String,
    attachedContext: String?,
    planOnly: Boolean,
    ideErrors: String?,
    specificPromptContent: String?
) {
    val sessionId = sessions.currentId() ?: return
    scope.launch {
        val result = service.handleUserInput(
            sessionId = sessionId,
            userInput = text,
            history = sessions.historyForLLM(sessionId),
            attachedContext = attachedContext,
            planOnly = planOnly,
            ideErrors = ideErrors,
            globalContextFiles = sessions.globalContextFiles(),
            specificPromptContent = specificPromptContent
        )
        renderClaudeCodeResult(sessionId, result)
    }
}
```

И метод * * `approve()` * *, вызываемый из ChatPanel:

```kotlin
fun approve() {
    val sessionId = sessions.currentId() ?: return
    scope.launch {
        val result = service.approve(sessionId)
        renderClaudeCodeResult(sessionId, result)
    }
}
```

### 8.3 `ChatPanelState.kt`

        Прочитать `FULL` . Добавить поля:

```kotlin
/** True when the current mode is Claude Code AND status is AWAITING_APPROVE. */
val claudeCodeApproveVisible: Boolean = false,

/** True when a Claude Code send is in flight — disables Send and Approve. */
val claudeCodeSending: Boolean = false
```

Обновить место (а), где собирается snapshot state для render — обычно метод вроде `currentState()` или похожий — добавить вычисление этих флагов .

### 8.4 `ChatPanel.kt`

        Прочитать `FULL` или `OUTLINE` сначала.Добавить:

1.Кнопку * * Approve * * (рядом с Send / Reset).Видимость управляется `state.claudeCodeApproveVisible`.2.Обработчик нажатия → `controller.approve()` .
3.В методе `render(state)` управлять видимостью и enabled - состоянием:

```kotlin
approveButton.isVisible = state.claudeCodeApproveVisible
approveButton.isEnabled = !state.claudeCodeSending
sendButton.isEnabled = !state.claudeCodeSending && !state.isClipboardSending
```

4.В статус -строке(если есть метод `setStatus(text: String)`) — показывать «Claude Code : sending …» когда `state.claudeCodeSending` .

5.* * Скрывать * * clipboard -специфичные кнопки (Copy JSON, Paste Response) когда `modeManager.isClaudeCodeMode()` — они не нужны в этом режиме.

### 8.5 Settings UI

Если четвёртый режим должен быть выбираем в settings — добавить radio button « Claude Code» в `MaxVibesSettingsPanel.kt` . Если переключение через UI кнопку в `InteractionModeManager` — settings UI трогать не надо .

## Что НЕ делать

-Не делать auto - loop — Approve остаётся ручным.
-Не показывать кнопку Approve в clipboard -режиме(она там не имеет смысла).
-Не показывать Copy JSON / Paste Response в Claude Code режиме.
-Не блокировать UI на время `send` — корутина в IO -scope, UI остаётся отзывчивым.

## Тесты

UI - тесты — через IntelliJ runner(
    не Gradle,
    чтобы избежать `kotlinx-coroutines-debug` javaagent crash
).Если в проекте есть `ChatPanelTest` — добавить кейсы :

-`claudeCodeMode_awaitingApprove_showsApproveButton`
-`claudeCodeMode_idle_hidesApproveButton`
-`claudeCodeMode_sending_disablesSendAndApprove`
-`claudeCodeMode_hidesCopyJsonAndPasteButtons`

## Acceptance criteria

        -[] `./gradlew :maxvibes-plugin:build` зелёный
-[] В run - IDE можно переключиться в Claude Code режим
-[] Кнопка Approve появляется только в `AWAITING_APPROVE` и в Claude Code режиме
        -[] Кнопка Send отключается во время send'а
-[] Clipboard -специфичные кнопки скрываются в Claude Code режиме
