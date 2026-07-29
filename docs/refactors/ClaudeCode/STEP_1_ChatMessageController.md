# STEP 1 — ChatMessageController deep cut — ВЫПОЛНЕН

Цель: превратить `ChatMessageController` из большого владельца логики режимов в тонкий стабильный фасад. State machines, подготовка отправки, фоновые политики, attachments, session actions, result routing и DI-wiring должны жить в отдельных тестируемых компонентах.

## Итог

Рефакторинг завершён полностью.

`ChatMessageController` теперь содержит только:

- стабильный публичный API, используемый `ChatPanel` и editor actions;
- чтение `attachedTrace` / `attachedErrors`;
- тонкие делегаты в `ChatMessageControllerComposition`;
- совместимый статический вход `buildTaskWithContext`.

Создание компонентов и циклическое lazy-wiring вынесены в `ChatMessageControllerComposition`. Контроллер больше не владеет mode routing, background error policy, cancellation recovery, attachment bookkeeping или session orchestration.

## Извлечённые компоненты

| Компонент | Ответственность |
|---|---|
| `ChatMessageControllerComposition` | Composition root и wiring всех chat-компонентов |
| `ClaudeCodeDispatcher` | Send / approve / result flow Claude Code |
| `ClipboardDispatcher` | Clipboard dialog flow и обработка результатов |
| `ApiDispatcher` | API и Cheap API flow, включая auto-retry |
| `TurnSubmissionCoordinator` | Send, approve, redo и маршрутизация подготовленного turn |
| `InteractionExecutionCoordinator` | Mode-specific background execution, cancellation и error mapping |
| `AttachmentCoordinator` | Синхронизация `PendingTurnContext` с attachment UI |
| `IdeErrorsAttachmentLoader` | Фоновый сбор и прикрепление IDE errors |
| `PendingTurnContext` | Trace, errors, images и one-shot state одного turn |
| `SendPreparationPolicy` | Чистая подготовка effective context, prompt и warnings |
| `CommandTurnCoordinator` | Command batch state machine |
| `QuestionTurnCoordinator` | Question turn state machine |
| `CommandResultRouter` | Продолжение диалога после command batch по режимам |
| `SessionActions` | Операции с chat sessions |
| `BackgroundTaskRunner` | IntelliJ background task boundary и EDT callback |
| `DocumentSaver` | Flush editor documents перед чтением файлов |
| `TaskContextFormatter` | Формирование полного task text с trace и IDE errors |

## UI-порты

`ChatPanelCallbacks` остаётся пустым агрегатом для `ChatPanel` и тестовых fake-объектов. Рабочие компоненты используют узкие интерфейсы:

- `MessageFlowView` — transcript и input/status surface для dispatcher-ов;
- `AttachmentView` — trace/errors, image strip и one-shot chip;
- `SessionView` — lifecycle sessions;
- `QuestionView` — interactive question blocks;
- `CommandView` — interactive command blocks.

`QuestionTurnCoordinator` и `CommandTurnCoordinator` также принимают отдельный `InputStatusView`, а не полный агрегат.

## Проверки

Последовательно прошли зелёные целевые наборы для:

- attachment и IDE-errors extraction;
- submission coordinator;
- interaction execution policy;
- composition-root extraction;
- dispatcher port narrowing;
- `TaskContextFormatter`;
- публичного API контроллера и session flow.

Финальный полный прогон:

- команда: `./gradlew.bat :maxvibes-plugin:test`;
- результат: **164/164 теста зелёные**.

## Definition of Done

- [x] `ChatMessageController` является тонким публичным фасадом.
- [x] Composition root вынесен из контроллера.
- [x] Внутренних state-machine классов в контроллере нет.
- [x] Send / approve / attachments / sessions / execution policy вынесены.
- [x] Dispatcher-ы зависят от `MessageFlowView`, а не от полного `ChatPanelCallbacks`.
- [x] Обратная зависимость `ApiDispatcher -> ChatMessageController` устранена.
- [x] Извлечённые компоненты покрыты unit-тестами.
- [x] Полный `:maxvibes-plugin:test` зелёный: 164/164.
