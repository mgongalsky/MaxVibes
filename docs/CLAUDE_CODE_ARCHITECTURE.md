# Claude Code dialog architecture

Актуальная архитектура Claude Code application pipeline после завершения STEP 2A и STEP 2B.

## Общая схема

`text
Plugin UI / Controller
│
▼
ClaudeCodeInteractionService
│
├── session routing ─────────────── ClipboardSessionManager
│
├── workspace lifecycle ────────── ClaudeCodeWorkspaceService
│                                      └── ClaudeCodeWorkspaceHolder
│
├── requested context ───────────── ClaudeCodeViewResolver
│                                      ├── ProjectContextPort
│                                      ├── CodeRepository
│                                      └── SpecificPromptService
│
├── transport turn ──────────────── ClaudeCodeTurnExecutor
│                                      ├── ClaudeCodeRequestFactory
│                                      ├── ClaudeCodePort
│                                      ├── TokenEstimator
│                                      └── ChatSessionRepository
│
├── response semantics ──────────── ClaudeCodeResponseProcessor
│                                      └── pure Outcome + Intent
│
├── response side effects ───────── ClaudeCodeResponseHandler
│                                      ├── ChatSessionRepository
│                                      ├── ClipboardSessionManager
│                                      └── PendingModificationsStore
│
└── approval semantics ──────────── ClaudeCodeApprovalService
├── ClaudeCodeViewResolver
├── ClaudeCodeWorkspaceService
├── CodeRepository
└── PendingModificationsStore
`

## Границы ответственности

### `ClaudeCodeInteractionService`

Тонкий публичный application facade.

Он отвечает за:

- routing по `ClipboardSessionStatus`;
- координацию workspace, executor, handler и approval service;
- публичные entry points;
- reset lifecycle.

В нём не должна появляться внутренняя реализация transport, context resolution, restore, response semantics или modification application.

### `ClaudeCodeWorkspaceService`

Единственный владелец активного in-memory workspace.

Он отвечает за:

- start;
- continue;
- restore;
- ensure;
- owner invariant;
- dialog history;
- clear.

### `ClaudeCodeViewResolver`

Единственная точка разрешения requested context:

| Granularity | Источник |
|---|---|
| FULL | `ProjectContextPort.gatherFiles` |
| SIGNATURES | `CodeRepository.getCodeView` |
| OUTLINE | `CodeRepository.getCodeView` |
| ELEMENT | `CodeRepository.getCodeView` |
| SKILL | `SpecificPromptService.resolveSkillBody` |

FULL failure останавливает continuation.

Partial и SKILL failures возвращаются как локальный error-content.

### `ClaudeCodeTurnExecutor`

Владеет одним transport-level turn:

`text
ClaudeCodeTurnCommand + ClipboardSessionState
→ Success ReceivedClaudeTurn
или
→ Failure ClaudeCodeStepResult
`

Он отвечает за:

- request assembly;
- full/minimal context policy;
- process startup;
- resume fallback;
- send;
- token and duration metrics;
- Claude session persistence;
- transport error mapping;
- shutdown.

### `ClaudeCodeResponseProcessor`

Чистая protocol-функция.

Она принимает `InteractionResponse` и immutable turn context, затем возвращает:

- `ClaudeCodeStepResult`;
- ordered side-effect intents.

Она не вызывает persistence, state machine или UI ports.

### `ClaudeCodeResponseHandler`

Исполняет intents процессора в заданном порядке.

Он отвечает за:

- plan persistence;
- assistant history;
- requestedViews persistence;
- state transitions;
- pending modifications;
- protocol warnings;
- response observability.

### `ClaudeCodeApprovalService`

Владеет approve и reject semantics.

Его результат:

`text
Immediate(ClaudeCodeStepResult)
или
Continue(ClaudeCodeTurnCommand)
`

Approval service не запускает transport самостоятельно.

## Dependency direction

Все классы находятся в `maxvibes-application` и зависят только от:

- domain models;
- shared `Result`;
- application output ports;
- application services с более узкой ответственностью.

IntelliJ, PSI implementation, process implementation и persistence implementation находятся за output ports и адаптерами.

## Владение состоянием

### Persisted state

`ChatSessionRepository` хранит:

- domain messages;
- clipboard status;
- current plan;
- Claude session id;
- флаг необходимости полного replay;
- requested views внутри assistant messages.

### In-memory workspace

`ClaudeCodeWorkspaceService` владеет:

- активным `ClipboardSessionState`;
- owner session id;
- transport dialog history;
- набором собранных файлов;
- текущим `planOnly`;
- последней оценкой input tokens.

### Pending approval state

`PendingModificationsStore` хранит:

- modifications;
- commands;
- commit message;
- owner session id.

Workspace и pending store очищаются при reset.

## Основные flow

### Первый turn

`text
Facade
→ WorkspaceService.start
→ ViewResolver.gatherFullFiles для global context
→ TurnExecutor.execute с full context
→ ResponseHandler.handle
`

### Последующий turn

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
→ Continue ClaudeCodeTurnCommand
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
→ Immediate Completed
`

### Resume fallback

`text
TurnExecutor.ensureStarted old Claude session
→ ResumeFailed
→ persist claudeCodeSessionId=null
→ persist claudeCodeNeedsFullContext=true
→ fresh ensureStarted
→ rebuild full-context request
→ send
→ persist observed new Claude session
→ clear claudeCodeNeedsFullContext
`

## State transitions

`text
IDLE
└── StartSession → SESSION_ACTIVE

SESSION_ACTIVE
├── normal response → SESSION_ACTIVE
└── response requiring approval → AWAITING_APPROVE

AWAITING_APPROVE
├── Approved → SESSION_ACTIVE
├── new user message rejects pending modifications → SESSION_ACTIVE
└── Reset → IDLE
`

`AWAITING_PASTE` принадлежит shared clipboard state machine и является ошибочным route для Claude Code mode.

## Testing architecture

STEP 2B добавил **61 новый поведенческий тест**.

| Test class | Количество | Уровень |
|---|---:|---|
| `ClaudeCodeViewResolverTest` | 9 | Direct component unit |
| `ClaudeCodeResponseHandlerTest` | 10 | Direct component unit |
| `ClaudeCodeTurnExecutorTest` | 11 | Direct component unit |
| `ClaudeCodeWorkspaceServiceTest` | 12 | Direct component unit |
| `ClaudeCodeApprovalServiceTest` | 13 | Direct component unit |
| `ClaudeCodeInteractionServiceFacadeTest` | 6 | Facade boundary |
| **Всего новых** | **61** | |

### Pure protocol layer

Проверяет чистую трансформацию данных:

- `ClaudeCodeResponseProcessorTest`;
- ProtocolConverter tests;
- `TokenEstimatorTest`.

### State invariant layer

Проверяет локальные state containers:

- `ClaudeCodeWorkspaceHolderTest`;
- PendingModificationsStore tests.

### Direct component layer

Проверяет каждый extracted-компонент отдельно от фасада:

- source routing и failures;
- persistence;
- state transitions;
- transport lifecycle;
- resume fallback;
- workspace ownership;
- approval semantics;
- отсутствие запрещённых side effects.

### Facade boundary layer

`ClaudeCodeInteractionServiceFacadeTest` проверяет только публичные routing boundaries и reset lifecycle.

Он сознательно не дублирует все внутренние component branches.

### Facade characterization layer

`ClaudeCodeInteractionServiceCharacterizationTest` сохраняет сквозные контракты:

- full first turn;
- minimal continuation;
- resume fallback;
- approve modifications;
- reject modifications;
- approve requested views;
- command-results continuation.

Дополнительно остаются существующие pin и scenario suites.

### Full regression layer

`text
gradlew :maxvibes-application:test
gradlew test
`

После STEP 2B оба уровня зелёные.

## Shared test fixtures

Переиспользуются:

- `InMemoryChatSessionRepository`;
- `RecordingClaudeCodePort`;
- `RecordingNotificationPort`;
- `RecordingClaudeCodeSessionLogPort`;
- расширенный `FakeProjectContextPort`;
- `FakePromptPort`.

Recording fakes не содержат assertions и могут использоваться новыми component tests.

## Инварианты, защищённые тестами

1. Первый turn содержит full context.
2. Нормальный continuation содержит только delta.
3. Resume failure вызывает fresh start и full replay.
4. Requested views не читаются без approval.
5. Modifications не применяются без approval.
6. Rejected modifications никогда не применяются.
7. Held commands освобождаются только после approve.
8. Workspace имеет одного owner.
9. Failed restore не уничтожает workspace другой session.
10. FULL view failure останавливает continuation.
11. Partial view failure не блокирует остальные views.
12. Response semantics выбираются чистым processor.
13. Handler исполняет intents, но не пересчитывает protocol branch.
14. ApprovalService не вызывает transport.
15. Facade остаётся orchestration boundary.

## Правило будущих изменений

Новая логика добавляется в компонент, владеющий соответствующей ответственностью:

- workspace и restore → `ClaudeCodeWorkspaceService`;
- requested context → `ClaudeCodeViewResolver`;
- CLI lifecycle и request execution → `ClaudeCodeTurnExecutor`;
- response side effects → `ClaudeCodeResponseHandler`;
- approve/reject/apply → `ClaudeCodeApprovalService`;
- protocol decision → `ClaudeCodeResponseProcessor`;
- публичная маршрутизация → `ClaudeCodeInteractionService`.

Каждое изменение должно сопровождаться тестом на минимально возможном уровне.

Facade characterization test добавляется только тогда, когда меняется сквозной публичный контракт.

Нельзя возвращать component logic обратно в фасад ради удобства реализации.
