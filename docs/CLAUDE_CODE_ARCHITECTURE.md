# Claude Code dialog architecture

        Актуальная архитектура Claude Code application pipeline после завершения STEP 2 A .

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
│                                      │
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
│                                      └── pure Outcome +Intent
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

## Dependency direction

        Все классы находятся в `maxvibes-application` и зависят только от:

-domain models;
-shared `Result`;
-application output ports;
-других application services с более узкой ответственностью.IntelliJ, process implementation, PSI implementation и persistence implementation остаются за output ports и адаптерами.

## Владение состоянием

### Persisted state

        `ChatSessionRepository` хранит :

-domain messages;
-clipboard status;
-current plan;
-Claude session id;
-флаг необходимости полного replay;
-requested views внутри assistant messages.

### In - memory state

        `ClaudeCodeWorkspaceService` владеет :

-активным `ClipboardSessionState`;
-owner session id;
-transport dialog history;
-набором уже собранных файлов;
-текущим `planOnly`;
-последней оценкой input tokens .

`PendingModificationsStore` отдельно владеет временным approval - набором:

-modifications;
-commands;
-commit message;
-owner session id.Оба хранилища являются in -memory и очищаются при reset.

## Protocol boundary

        `ClaudeCodeResponseProcessor` является чистой границей protocol semantics .

Он принимает :

-`InteractionResponse`;
-immutable context turn metrics и `planOnly` .

Он возвращает :

-конечный `ClaudeCodeStepResult`;
-упорядоченный список `Intent`.`ClaudeCodeResponseHandler` не выбирает protocol branch заново . Он только исполняет intents в заданном порядке .

Это разделяет :

-решение, что означает response;
-выполнение persistence и state -machine side effects.

## Transport boundary

        `ClaudeCodeTurnExecutor` не знает approval semantics и не интерпретирует LLM response .

Его контракт :

`text
ClaudeCodeTurnCommand + ClipboardSessionState
→ Success ReceivedClaudeTurn
        или
→ Failure ClaudeCodeStepResult
`

Transport executor отвечает за корректность одного CLI turn, включая resume fallback.

## Approval boundary

        `ClaudeCodeApprovalService` возвращает один из двух outcomes :

-`Continue` с новым `ClaudeCodeTurnCommand`;
-`Immediate` с уже готовым `ClaudeCodeStepResult`.Таким образом, approval service не вызывает transport сам и не создаёт циклическую зависимость с фасадом или executor.

## View resolution policy

| Granularity | Источник |
|---|-- - |
| FULL | `ProjectContextPort.gatherFiles` |
| SIGNATURES | `CodeRepository.getCodeView` |
| OUTLINE | `CodeRepository.getCodeView` |
| ELEMENT | `CodeRepository.getCodeView` |
| SKILL | `SpecificPromptService.resolveSkillBody` |

Ошибка одного partial или skill view превращается в error - content для конкретного ключа .

Ошибка общего FULL gather возвращает `null` и останавливает continuation.

## Основные state transitions

`text
IDLE
└── StartSession → SESSION_ACTIVE

SESSION_ACTIVE
├── response without requested approval → SESSION_ACTIVE
└── response requiring approval → AWAITING_APPROVE

AWAITING_APPROVE
├── Approved → SESSION_ACTIVE
├── new user message rejects pending modifications → SESSION_ACTIVE
└── Reset → IDLE
`

`AWAITING_PASTE` принадлежит shared clipboard state machine и для Claude Code facade считается ошибочным маршрутом .

## Testing layers

### Pure unit tests

-`ClaudeCodeResponseProcessorTest`
-`ClaudeCodeWorkspaceHolderTest`
-component tests для ViewResolver, ResponseHandler, TurnExecutor, WorkspaceService и ApprovalService.

### Facade characterization tests

`ClaudeCodeInteractionServiceCharacterizationTest` проверяет сквозные взаимодействия компонентов и публичный контракт фасада.

### Regression suite

        Полный `maxvibes-application:test` проверяет отсутствие конфликтов с остальным application layer.

## Правило будущих изменений

Новая логика должна добавляться в компонент, который владеет соответствующей ответственностью :

-workspace и restore → WorkspaceService;
-чтение context → ViewResolver;
-CLI lifecycle и request execution → TurnExecutor;
-interpretation side effects → ResponseHandler;
-approve / reject / apply → ApprovalService;
-только маршрутизация публичного use case → InteractionService.Если изменение требует правок сразу в нескольких компонентах, сначала следует определить новый явный command или outcome, а не возвращать логику обратно в фасад.
