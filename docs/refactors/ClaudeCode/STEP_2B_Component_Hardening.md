# STEP 2 B — Claude Code component hardening

Статус: В РАБОТЕ .

## Цель

После STEP 2 A крупный `ClaudeCodeInteractionService` разделён на независимые application -компоненты.Сквозное поведение защищено characterization tests, однако локальные контракты extracted -классов пока проверяются преимущественно косвенно через фасад.Цель STEP 2 B — создать фундаментальный набор чистых unit -тестов для каждого компонента .

Тесты должны :

-создавать только тестируемый класс и его непосредственные зависимости;
-не запускать весь facade pipeline без необходимости;
-точно проверять возвращаемое значение;
-точно проверять state changes;
-проверять persistence calls;
-проверять отсутствие запрещённых side effects;
-покрывать ошибки и пограничные ветки;
-использовать единые fixtures и recording fakes .

## Почему characterization tests недостаточно

        Characterization suite отвечает на вопрос:

> Сохранился ли общий публичный сценарий после рефакторинга?

Component unit tests должны отвечать на другие вопросы :

-Каков точный контракт конкретного класса?
-Какая зависимость вызывается и с какими аргументами?
-Какие зависимости не должны вызываться?
-Как компонент ведёт себя на локальной ошибке?
-Каким остаётся state после частичного failure ?
-Какие данные персистятся до и после fallback?

Оба слоя тестов сохраняются .

## Общая тестовая инфраструктура

Планируется вынести в `com.maxvibes.application.testsupport` :

### InMemoryChatSessionRepository

Функции:

-хранение sessions;
-фиксация каждого `saveSession`;
-управление active session;
-global context files;
-удобный доступ к последнему persisted snapshot .

### RecordingClaudeCodePort

Функции:

-очередь результатов `ensureStarted`;
-очередь результатов `send`;
-запись resume ids и system prompts;
-запись всех `ClipboardRequest`;
-счётчик `shutdown`;
-возможность выбросить exception при shutdown.

### RecordingNotificationPort

Фиксирует:

-progress messages;
-success messages;
-warnings;
-отсутствие лишних notifications.

### RecordingClaudeCodeSessionLogPort

Фиксирует:

-`begin` calls;
-ordered events;
-event payloads .

### Test builders

        Нужны компактные builders для :

-`ChatSession`;
-`ChatMessage`;
-`ClipboardSessionState`;
-`InteractionResponse`;
-requested views;
-pending modifications;
-transport success payloads.Builders должны создавать минимально валидные объекты и позволять явно переопределить только важные для теста поля.

## Матрица unit -тестов

## 1.ClaudeCodeViewResolverTest

### FULL views

        -пустой список не вызывает `gatherFiles` и возвращает empty map;
-успешный gather возвращает точную map;
-успешный gather добавляет файлы в `state.allGatheredFiles`;
-повторный gather обновляет существующий tracked file;
-failure возвращает `null`;
-failure не мутирует tracked files;
-progress notification отправляется только для непустого списка.

### Partial views

        -SIGNATURES передаётся в `CodeRepository` без потери `elementPath`;
-OUTLINE передаётся без изменения;
-ELEMENT передаётся без изменения;
-content возвращается под исходным file path;
-exception одного view превращается в error -content только для этого файла;
-exception одного view не блокирует остальные views.

### SKILL views

        -существующий skill возвращается под ключом `skill:name`;
-неизвестный skill возвращает понятный error - content;
-отсутствие `SpecificPromptService` обрабатывается как неизвестный skill;
-skill request не вызывает `CodeRepository` и `ProjectContextPort`.

### Mixed requests

        -FULL, partial и SKILL объединяются в одну map;
-источники вызываются только для своих granularity;
-collision policy фиксируется тестом .

## 2.ClaudeCodeResponseHandlerTest

### History и transition

-text response добавляет assistant history;
-blank response не добавляет history;
-completed response переводит state machine в SESSION_ACTIVE;
-view response переводит в AWAITING_APPROVE.

### Requested views persistence

-views сохраняются в последнее ASSISTANT - сообщение;
-более ранние assistant messages не изменяются;
-отсутствие assistant message является no - op;
-отсутствие session является no -op;
-`elementPath` и granularity сохраняются без потери .

### Plan persistence

        -новый plan сохраняется;
-empty plan очищает persisted plan;
-response без plan не вызывает save plan branch .

### Pending modifications

        -modifications, commands и commit message удерживаются вместе;
-owner pending store соответствует session id;
-mixed modifications +views не персистят views;
-planOnly не создаёт pending set.

### Observability

-response event содержит raw branch counts;
-mixed views +commands создаёт warning event;
-mixed modifications +views создаёт warning event;
-questions создают questions event .

## 3.ClaudeCodeTurnExecutorTest

### Request policy

        -first message создаёт full -context request;
-normal continuation создаёт minimal request;
-persisted `claudeCodeNeedsFullContext` принудительно включает full context;
-fresh files, attachments, IDE errors, specific prompt и command results передаются без потери;
-current plan передаётся в request factory .

### Process startup

        -существующий Claude session id передаётся в `ensureStarted`;
-отсутствие session id запускает fresh process;
-system prompt берётся из workspace state;
-ensure failure не вызывает `send`.

### Resume fallback

        -`ResumeFailed` вызывает второй `ensureStarted` с null;
-перед fresh retry persisted session временно помечается для full replay;
-request пересобирается с full context;
-второй ensure failure возвращается как transport error;
-успешный retry сохраняет новый observed session id;
-после success `claudeCodeNeedsFullContext` очищается .

### Send result

        -transport stats имеют приоритет над estimates;
-нулевые stats заменяются estimates;
-thinking text сохраняется;
-measured duration используется без transport duration;
-send failure маппится на правильный message для каждого `ClaudeCodeError`;
-отсутствующая domain session возвращает Error и не трогает transport.

### Lifecycle

-shutdown делегируется transport;
-exception shutdown подавляется;
-shutdown exception логируется.

## 4.ClaudeCodeWorkspaceServiceTest

### Start

-project context failure возвращает Failure и не устанавливает workspace;
-успешный start устанавливает owner;
-state содержит current message, project context и prompts;
-supplied history копируется;
-USER message добавляется после supplied history;
-`planOnly` сохраняется;
-progress notification отправляется.

### Continue owned session

-current message обновляется;
-USER history дополняется;
-существующие gathered files сохраняются;
-planOnly обновляется;
-restore не вызывается.

### Restore

-persisted USER и ASSISTANT messages восстанавливаются в правильном порядке;
-другие domain roles не попадают в dialog history;
-последнее USER сообщение становится current message;
-owner устанавливается в восстанавливаемый session id;
-project context failure возвращает false;
-отсутствующая session возвращает false;
-session без USER message возвращает false;
-restored planOnly устанавливается в false.

### Ensure и clear

-owned workspace возвращает true без repository / context calls;
-чужой owner вызывает restore;
-clear удаляет state и owner;
-appendAssistantHistory добавляет ровно одно сообщение.

## 5.ClaudeCodeApprovalServiceTest

### Reject pending

        -отсутствие pending возвращает null и не меняет status;
-pending set удаляется;
-status возвращается в SESSION_ACTIVE;
-feedback содержит число modifications;
-feedback содержит число удержанных commands;
-original user input находится после пустой строки;
-modifications не применяются;
-rejection event фиксирует counts .

### Invalid approve

        -approve вне AWAITING_APPROVE возвращает Error;
-invalid approve не читает views и не применяет modifications.

### Approve requested views

-owned workspace используется без restore;
-отсутствующий workspace восстанавливается;
-restore failure возвращает Error;
-отсутствующая session возвращает Error;
-отсутствие assistant message возвращает Error;
-assistant без requested views возвращает Error;
-resolver failure возвращает Error;
-успешный approve возвращает Continue;
-continuation содержит fresh files и attached context fields;
-assistant content добавляется в transport history один раз;
-дубликат assistant content не добавляется;
-status становится SESSION_ACTIVE только после успешного resolution.

### Approve modifications

        -pending modifications конвертируются и применяются;
-invalid protocol modifications отбрасываются;
-success result возвращает commit message и held commands;
-partial failures дают `success=false`;
-success notification отправляется при полном успехе;
-warning notification отправляется при частичном failure;
-пустой converted list не вызывает repository;
-pending set удаляется после approve.

## 6.Thin facade contract

Фасад не нужно тестировать повторно на уровне каждой внутренней ветки .

Оставляем и расширяем только важные boundary tests:

-публичные аргументы правильно превращаются в command objects;
-routing по каждому `ClipboardSessionStatus`;
-approve Continue вызывает transport turn;
-approve Immediate не вызывает transport;
-reset очищает workspace, pending store, state machine и shutdown transport;
-существующие characterization tests остаются зелёными.

## Порядок реализации

        1.Общие recording fakes и builders.2.`ClaudeCodeViewResolverTest`.3.`ClaudeCodeResponseHandlerTest`.4.`ClaudeCodeTurnExecutorTest`.5.`ClaudeCodeWorkspaceServiceTest`.6.`ClaudeCodeApprovalServiceTest`.7.Точечное усиление facade tests .
8.Полный application regression suite .
9.Полный project regression suite .

## Правила этапа

        1.Сначала тестируется текущее поведение .
2.Production code не меняется ради удобства теста без отдельного обоснования .
3.Если unit test обнаруживает вероятный баг, сначала добавляется characterization test текущего поведения .
4.Один тест проверяет один контракт или одну связанную группу side effects.5.Tests не должны зависеть от порядка запуска.6.Shared fakes не должны содержать assertions внутри себя .
7.Проверяется не только наличие ожидаемого вызова, но и отсутствие запрещённых вызовов.8.Для stateful компонентов каждый test получает новый instance .
9.Никакой IntelliJ test fixture для этих application - компонентов не требуется.

## Definition of Done

-Для каждого из пяти extracted - компонентов существует отдельный test class.
-Все public / internal component methods имеют happy -path и failure - path coverage .
-Resume fallback полностью покрыт прямыми unit -тестами TurnExecutor .
-Persistence и state transitions ResponseHandler покрыты напрямую.
-Workspace restore покрыт без facade.
-Approval views и modifications покрыты раздельно .
-Общие fakes переиспользуются и не дублируются по test classes.
-Characterization suite остаётся без ослабления assertions .
-Полный `maxvibes-application:test` зелёный.
-Полный project test suite зелёный.
