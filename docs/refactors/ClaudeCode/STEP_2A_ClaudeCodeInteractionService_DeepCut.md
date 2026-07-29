# STEP 2A — Глубокая нарезка ClaudeCodeInteractionService

Статус: ВЫПОЛНЕН 2026-07-29.

## Цель

Превратить `ClaudeCodeInteractionService` из крупного application-сервиса, который одновременно управлял workspace, transport, response protocol, requested views и approvals, в тонкий фасад над отдельными компонентами с явными границами ответственности.

Исходная точка этого этапа после предыдущего рефакторинга: **865 строк**.

Финальный размер фасада: **278 строк**.

Сокращение: **587 строк**, или примерно **68%**.

Публичный API сохранён:

- `handleUserInput`
- `approve`
- `submitCommandResults`
- `status`
- `reset`

## Итоговая конфигурация

`text
ClaudeCodeInteractionService
├── ClaudeCodeWorkspaceService
├── ClaudeCodeViewResolver
├── ClaudeCodeTurnExecutor
├── ClaudeCodeResponseHandler
├── ClaudeCodeApprovalService
├── PendingModificationsStore
└── ClipboardSessionManager
`

### ClaudeCodeInteractionService

Тонкий application facade.

Отвечает только за:

- маршрутизацию публичных операций;
- выбор сценария по `ClipboardSessionStatus`;
- координацию extracted-компонентов;
- последовательность workspace → turn execution → response handling;
- lifecycle reset.

Фасад больше не содержит реализации transport, восстановления workspace, интерпретации response intents, разрешения views или применения modifications.

### ClaudeCodeWorkspaceService

Единственный владелец активного `ClipboardSessionState`.

Отвечает за:

- создание workspace для первого сообщения;
- продолжение существующей сессии;
- восстановление workspace из `ChatSessionRepository` после перезапуска IDE;
- владение `state` и `owner`;
- добавление USER и ASSISTANT сообщений в transport-history;
- очистку workspace.

### ClaudeCodeViewResolver

Разрешает запрошенный LLM контекст из трёх источников:

- `FULL` через `ProjectContextPort`;
- `SIGNATURES`, `OUTLINE`, `ELEMENT` через `CodeRepository`;
- `SKILL` через `SpecificPromptService`.

Также обновляет `allGatheredFiles` workspace для полных файлов.

### ClaudeCodeTurnExecutor

Полностью владеет одним transport-turn.

Отвечает за:

- сборку `ClipboardRequest` через `ClaudeCodeRequestFactory`;
- вычисление full/minimal context policy;
- `ensureStarted` и resume существующей Claude session;
- fallback на fresh start после `ResumeFailed`;
- повторную сборку full-context request после resume failure;
- вызов `ClaudeCodePort.send`;
- token accounting и transport metrics;
- сохранение `claudeCodeSessionId` и `claudeCodeNeedsFullContext`;
- преобразование transport errors в `ClaudeCodeStepResult.TransportError`;
- shutdown transport.

Успешный transport-result нормализуется в `ReceivedClaudeTurn`.

### ClaudeCodeResponseHandler

Интерпретатор side-effect intents, созданных чистым `ClaudeCodeResponseProcessor`.

Отвечает за:

- обновление и очистку плана;
- добавление assistant history;
- persistence `requestedViews` в последнее ASSISTANT-сообщение;
- переходы `ClipboardSessionManager`;
- помещение modifications и commands в `PendingModificationsStore`;
- protocol warnings для смешанных response branches;
- session logging response-событий.

Сам выбор protocol-ветки остаётся чистой функцией в `ClaudeCodeResponseProcessor`.

### ClaudeCodeApprovalService

Владеет approval semantics.

Отвечает за:

- approve запрошенных views;
- восстановление workspace перед approve;
- построение continuation `ClaudeCodeTurnCommand`;
- approve pending modifications;
- применение converted modifications через `CodeRepository`;
- release удержанных commands и commit message;
- rejection pending modifications при вводе нового сообщения;
- формирование feedback-prefix для следующего turn.

### Команды сценариев

Введены отдельные модели:

- `UserInputCommand` — user-originated turn;
- `ClaudeCodeTurnCommand` — transport-level turn;
- `ReceivedClaudeTurn` — нормализованный успешный transport-result.

Они устранили длинные списки позиционных параметров между компонентами и закрепили границы сценариев.

## Основные потоки

### Первый user turn

`text
Facade
→ WorkspaceService.start
→ ViewResolver.gatherFullFiles для global context
→ TurnExecutor.execute с full context
→ ResponseHandler.handle
`

### Последующий user turn

`text
Facade
→ WorkspaceService.continueSession
→ TurnExecutor.execute с minimal context
→ ResponseHandler.handle
`

### Approve requested views

`text
Facade.approve
→ ApprovalService.approve
→ WorkspaceService.ensure
→ ViewResolver.resolve
→ ClaudeCodeTurnCommand
→ TurnExecutor.execute
→ ResponseHandler.handle
`

### Approve modifications

`text
Facade.approve
→ ApprovalService.approve
→ PendingModificationsStore.take
→ ProtocolConverter
→ CodeRepository.applyModifications
→ Completed с commands и commitMessage
`

### Resume failure

`text
TurnExecutor.ensureStarted old session
→ ResumeFailed
→ persisted session marked for full replay
→ fresh ensureStarted
→ request rebuilt with full context
→ send
→ observed new Claude session persisted
`

## Инварианты

1. Первый turn содержит full context; обычные продолжения содержат только delta.
2. После `ResumeFailed` выполняется fresh start с полным replay контекста.
3. Requested views не читаются без user approval.
4. Modifications не применяются без user approval.
5. Pending modifications отклоняются новым user message и не применяются.
6. Commands, пришедшие вместе с modifications, удерживаются до успешного approval.
7. Workspace имеет одного owner и не должен использоваться другой сессией без restore.
8. Response protocol выбирается чистым `ClaudeCodeResponseProcessor`.
9. Extracted-компоненты находятся в application layer и не зависят от IntelliJ API.
10. `ClipboardInteractionService` этим этапом не изменялся.

## Проверка поведения

Перед extraction были добавлены characterization tests для ключевых сквозных контрактов:

- full context первого turn и minimal continuation;
- resume fallback с полным replay;
- approve pending modifications и release commands;
- rejection modifications новым сообщением;
- restore workspace при approve;
- разрешение FULL и SIGNATURES views;
- minimal continuation для command results.

После каждого extraction запускались эти 6 сценариев.

После завершения рефакторинга выполнен полный набор тестов `maxvibes-application` — все тесты зелёные.

## Изменение размера фасада

| Стадия | Строк |
|---|---:|
| Начало deep-cut | 865 |
| После TurnExecutor | 526 |
| После WorkspaceService | 448 |
| После ApprovalService | 335 |
| Финальный thin facade | 278 |

## Что сознательно не делалось

- Не объединялись extracted-компоненты в новый service-combine.
- Не менялся публичный API фасада.
- Не переписывался protocol под новые semantics.
- Не переносились application concerns во внешние адаптеры.
- Не заменялись characterization tests моками внутренних компонентов.

## Следующий этап

Продолжение описано в `STEP_2B_Component_Hardening.md`.

Цель следующего этапа — покрыть каждый extracted-компонент чистыми изолированными unit-тестами, чтобы characterization suite оставался сквозной страховкой, а локальные контракты компонентов были проверены напрямую.
