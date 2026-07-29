# STEP 1 — Распил ChatMessageController (1534 LOC) — ВЫПОЛНЕН

Цель: контроллер остаётся тонким маршрутизатором; state machines и режимные диспетчеры — отдельные тестируемые классы. Поведение не меняется.

## Итог (2026-07-29)

**ChatMessageController: 1534 → 501 LOC (−67%). DoD «≤ ~600 LOC» достигнут.**
Оставшиеся ~500 строк — композиционный корень: DI-вайринг ленивых компонентов, три run*Bg-обвязки с обработкой ошибок фона, approve-бухгалтерия вложений, делегаты публичного API. Логики state machines и рендера результатов в контроллере не осталось.

### Извлечённые файлы (plugin/ui)

| Файл | LOC | Содержимое |
|------|-----|-----------|
| ClaudeCodeDispatcher.kt | 418 | send/approve/handleResult всех веток ClaudeCodeStepResult |
| ClipboardDispatcher.kt | 317 | dispatch/redo/handleResult clipboard-режима |
| ApiDispatcher.kt | 302 | API + CheapAPI (объединены параметром) |
| CommandTurnCoordinator.kt | 176 | command-turn state machine (run all / decline all / стоп по ошибке) |
| ChatPanelViews.kt | 109 | 6 узких UI-граней + агрегат ChatPanelCallbacks |
| PendingTurnContext.kt | 99 | trace/errors/images/one-shot до отправки |
| QuestionTurnCoordinator.kt | 75 | question-turn state machine |
| SendPreparationPolicy.kt | 73 | чистая подготовка отправки (warnings, effective-поля) |
| BackgroundTaskRunner.kt | 55 | абстракция Task.Backgroundable + EDT-хоп |
| SessionActions.kt | 43 | session-операции |
| CommandResultRouter.kt | 29 | роутинг результатов команд по режимам |

### Тесты

140 тестов maxvibes-plugin зелёные. Новые тесты: координаторы (полный жизненный цикл обоих state machines), SessionActions, CommandResultRouter, PendingTurnContext, SendPreparationPolicy — все на переиспользуемом FakeChatPanelCallbacks.

### Сужение UI-портов (шаг 11)

ChatPanelCallbacks порезан на грани прямо здесь (изначально планировалось в STEP_3): ConversationView, InputStatusView, AttachmentView, SessionView, QuestionView, CommandView; ChatPanelCallbacks — пустой агрегат, поэтому ChatPanel и фейки не менялись.

- QuestionTurnCoordinator принимает (QuestionView, InputStatusView), CommandTurnCoordinator — (CommandView, InputStatusView).
- Диспетчеры ОСОЗНАННО оставлены на агрегате: каждый использует ровно ConversationView + InputStatusView (12 методов), сужение — это смена типа параметра конструктора, а конструкторы сейчас правятся только через REPLACE_FILE из-за PSI-бага (см. TODOs/BUG_replace_element_primary_constructor.md). Ретип ~800 строк ради одной строки отложен до починки бага.

## Исходный план (для истории)

### 1.1 CommandTurnCoordinator
Вся command-turn state machine: CommandTurn, CommandItem, presentCommands, startRunAll, runNextQueued, declineAllRemaining, declineItem, runCommand, recordExecution. Правила сохранены: Run all останавливается на первом ненулевом exit code; батч завершён — авто-продолжение диалога.

### 1.2 QuestionTurnCoordinator
Question-turn state machine: presentQuestions, answerQuestion, dismissQuestionTurn. Правила сохранены: ввод в главное поле = dismiss всех блоков; все отвечены — составной ответ через обычный send-путь.

### 1.3 Режимные диспетчеры
dispatch*/handle*Result → ClaudeCodeDispatcher, ClipboardDispatcher, ApiDispatcher (Api и CheapApi объединены). Контроллер держит только sendMessage-роутинг по InteractionMode.

### 1.4 Осталось в контроллере
Attachments (trace/errors/images), one-shot skills, session-делегаты, роутинг, run*Bg-обвязки фоновых задач.

## Definition of Done

- [x] ChatMessageController ≤ ~600 LOC, без внутренних state-machine классов (факт: 501).
- [x] CommandTurnCoordinator, QuestionTurnCoordinator, ClaudeCodeDispatcher — отдельные файлы с юнит-тестами на FakeChatPanelCallbacks.
- [x] Поведение в IDE не изменилось (рефакторинг структурный, пины не редактировались).
- [x] gradlew test зелёный (140/140 plugin).
