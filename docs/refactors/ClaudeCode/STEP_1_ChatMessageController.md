# STEP 1 — ChatMessageController deep cut — ВЫПОЛНЕН

Цель: превратить `ChatMessageController` из большого владельца логики режимов в тонкий стабильный фасад. State machines, подготовка отправки, фоновые политики, attachments, session actions, result routing и DI-wiring должны жить в отдельных тестируемых компонентах.

## Итог

Рефакторинг и последующий test-hardening завершены полностью.

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

## Test hardening

После структурного рефакторинга проведён отдельный аудит покрытия выделенных seams.

### Submission и attachments

Дополнительно зафиксированы:

- независимость snapshot-ов и consume semantics;
- точные UI-публикации trace/errors/images/one-shot;
- image-limit и invalid-index paths;
- повторная очистка состояния;
- сохранение документов и порядок `save → dismiss → consume → dispatch`;
- точная передача параметров во все четыре interaction mode;
- approve и redo ordering;
- замена и сохранение IDE errors при success/empty/failure.

### Execution boundary

Дополнительно покрыты:

- точная передача успешных результатов;
- преобразование неожиданных исключений Clipboard и Claude Code в protocol error;
- отдельная обработка `ProcessCanceledException`;
- cancellation recovery каждого режима;
- отсутствие запуска action при отмене;
- выбор Cheap/Regular API use case;
- параметры cancellable/publishIndicator и task titles;
- command execution boundary.

### Session и result routing

Дополнительно зафиксированы:

- порядок session operations;
- сохранение остальных полей при смене selected prompt;
- неизвестные session/branch/rename paths;
- отдельный routing всех interaction modes;
- Cheap API continuation через обычный API path;
- missing-session recovery;
- передача пустого formatted result без преобразования.

### Question и command state machines

Помимо расширения тестов устранены реальные stale-callback риски:

- новый question turn закрывает предыдущий;
- ответы старых question blocks игнорируются;
- callbacks завершённого/dismissed question turn идемпотентны;
- command callbacks привязаны к конкретному `CommandTurn`;
- старые Run/Decline/Run all/Decline all callbacks после нового batch игнорируются;
- stale completion старого command batch не изменяет новый batch;
- повторные Run/Decline после старта команды игнорируются;
- Run all корректно продолжает вручную запущенную команду;
- FAILED/TIMEOUT/ERROR останавливают цепочку и отклоняют остаток.

### Composition smoke tests

Публичный `ChatMessageController` проверен как composition boundary:

- конструктор не инициализирует lazy service graph;
- attachment-only операции не читают application services;
- session path инициализирует только `ChatTreeService`;
- selected prompt проходит через facade и composition wiring;
- compatibility formatter не зависит от создания контроллера.

## Проверки

Все промежуточные целевые наборы прошли зелёными. После test-hardening выполнен полный прогон:

- команда: `./gradlew.bat :maxvibes-plugin:test`;
- результат: **полный набор тестов зелёный**.

## Definition of Done

- [x] `ChatMessageController` является тонким публичным фасадом.
- [x] Composition root вынесен из контроллера.
- [x] Внутренних state-machine классов в контроллере нет.
- [x] Send / approve / attachments / sessions / execution policy вынесены.
- [x] Dispatcher-ы зависят от `MessageFlowView`, а не от полного `ChatPanelCallbacks`.
- [x] Обратная зависимость `ApiDispatcher -> ChatMessageController` устранена.
- [x] Извлечённые компоненты покрыты unit- и characterization-тестами.
- [x] Composition wiring покрыт smoke-тестами.
- [x] State machines защищены от stale и повторных callbacks.
- [x] Полный `:maxvibes-plugin:test` зелёный после test-hardening.
