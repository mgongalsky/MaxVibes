# STEP 2B — Claude Code component hardening

Статус: ВЫПОЛНЕН 2026-07-29.

## Цель

После STEP 2A крупный `ClaudeCodeInteractionService` был разделён на независимые application-компоненты. Сквозное поведение уже защищали characterization tests, но локальные контракты extracted-классов проверялись преимущественно косвенно через фасад.

Цель STEP 2B — создать фундаментальный набор чистых unit-тестов для каждого нового компонента и закрепить тонкую границу фасада.

## Итог

Добавлен **61 новый поведенческий тест**:

| Компонент | Новых тестов | Основное покрытие |
|---|---:|---|
| `ClaudeCodeViewResolver` | 9 | FULL, partial, SKILL, failures, mixed sources |
| `ClaudeCodeResponseHandler` | 10 | intents, history, transitions, persistence, pending state |
| `ClaudeCodeTurnExecutor` | 11 | request policy, transport, resume fallback, persistence, shutdown |
| `ClaudeCodeWorkspaceService` | 12 | start, continue, restore, ownership, ensure, clear |
| `ClaudeCodeApprovalService` | 13 | reject, views approval, modifications approval, failures |
| `ClaudeCodeInteractionService` facade | 6 | public routing boundaries, invalid states, reset |
| **Всего** | **61** | |

Все существовавшие characterization, scenario, pin и pure-function tests сохранены без ослабления assertions.

Полный `gradlew test` после завершения этапа — зелёный.

## Общая test-support инфраструктура

Созданы четыре переиспользуемых recording fake:

### `InMemoryChatSessionRepository`

Поддерживает:

- setup с начальными sessions;
- прямой `put` без регистрации persistence-вызова;
- хранение актуальных sessions;
- ordered `savedSessions`;
- `lastSavedSession`;
- запись удалённых session ids;
- active session;
- global context files.

### `RecordingClaudeCodePort`

Поддерживает:

- очередь результатов `ensureStarted`;
- очередь результатов `send`;
- запись resume ids и system prompts;
- запись всех `ClipboardRequest`;
- готовый helper `enqueueResponse`;
- счётчики `shutdown` и `abort`;
- управляемый exception при shutdown.

### `RecordingNotificationPort`

Записывает:

- progress notifications;
- success notifications;
- warnings;
- errors;
- confirmation requests и ответы.

### `RecordingClaudeCodeSessionLogPort`

Записывает:

- `begin` calls;
- ordered events и payloads;
- outbound lines;
- inbound lines;
- stderr lines;
- test log paths.

Дополнительно расширен `FakeProjectContextPort`:

- счётчиком `getProjectContext`;
- управляемым `projectContextError`;
- управляемым `gatherFilesError`;
- записью списков запрошенных paths.

## Покрытие `ClaudeCodeViewResolver`

Добавлено 9 unit-тестов.

Проверено:

1. Пустой FULL-список возвращает empty map без вызовов и notifications.
2. Успешный FULL gather возвращает точную map.
3. FULL gather обновляет `state.allGatheredFiles`.
4. Повторный gather заменяет tracked content существующего path.
5. Ошибка FULL gather возвращает `null` и не мутирует tracked files.
6. Partial request передаётся в `CodeRepository` без изменения granularity и `elementPath`.
7. Exception одного partial view превращается в локальный error-content и не блокирует остальные views.
8. Известный и неизвестный SKILL разрешаются без чтения code repository.
9. FULL, partial и SKILL корректно объединяются в mixed-source response.

Главный закреплённый контракт:

`text
FULL failure останавливает continuation.
Partial или SKILL failure локализуется внутри конкретного результата.
`

## Покрытие `ClaudeCodeResponseHandler`

Добавлено 10 unit-тестов.

Проверено:

1. Text response добавляет ASSISTANT history.
2. Blank response не добавляет пустое history-сообщение.
3. Completed response оставляет session active.
4. Requested views переводят session в `AWAITING_APPROVE`.
5. Requested views сохраняются только в последнее domain ASSISTANT-сообщение.
6. Отсутствие ASSISTANT-сообщения не создаёт искусственное domain message.
7. Plan snapshot заменяет persisted plan.
8. Empty plan очищает persisted plan.
9. Modifications, commands и commit message удерживаются одним pending set.
10. Plan-only, mixed views/commands и questions создают правильные results и session-log events.

Главный закреплённый контракт:

`text
ClaudeCodeResponseProcessor выбирает protocol semantics.
ClaudeCodeResponseHandler только исполняет ordered intents и side effects.
`

## Покрытие `ClaudeCodeTurnExecutor`

Добавлено 11 unit-тестов.

Проверено:

1. Отсутствующая domain session возвращает application error без transport calls.
2. Первый turn всегда создаёт full-context request.
3. Обычный continuation создаёт minimal request.
4. Fresh files, attached context, IDE errors, specific prompt и command results передаются без потери.
5. `claudeCodeNeedsFullContext` принудительно включает full replay.
6. Startup failure не вызывает `send`.
7. `ResumeFailed` запускает второй fresh `ensureStarted` с null session id.
8. После resume failure request пересобирается с полным контекстом.
9. Failure второго старта возвращает ошибку второго transport attempt.
10. Успешный send сохраняет observed Claude session id и очищает full-context flag.
11. Shutdown делегируется transport и подавляет transport exception.

Главный закреплённый контракт:

`text
Первый turn и recovery turn являются full-context.
Нормальный continuation является minimal.
Resume fallback сохраняет промежуточное состояние до fresh retry.
`

## Покрытие `ClaudeCodeWorkspaceService`

Добавлено 12 unit-тестов.

Проверено:

1. Ошибка project context не устанавливает workspace.
2. Успешный start устанавливает полный state и owner.
3. Supplied history копируется, после чего добавляется новый USER message.
4. `planOnly` сохраняется при start.
5. Owned continuation обновляет current message и сохраняет gathered files.
6. Continue другой session восстанавливает persisted history.
7. Failed restore не уничтожает workspace предыдущего owner.
8. Restore включает только USER и ASSISTANT domain messages.
9. Последнее USER-сообщение становится `currentMessage`.
10. `ensure` owned workspace не перечитывает project context.
11. Missing session, missing USER message и context failure возвращают false.
12. Assistant history и clear корректно меняют workspace state.

Главный закреплённый контракт:

`text
Workspace имеет одного owner.
Неудачный restore не должен разрушать уже установленный workspace другой session.
`

## Покрытие `ClaudeCodeApprovalService`

Добавлено 13 unit-тестов.

Проверено:

1. Reject без pending set возвращает null и не меняет status.
2. Reject потребляет pending set и формирует feedback-prefix.
3. Feedback содержит количество rejected modifications и held commands.
4. Approve вне `AWAITING_APPROVE` возвращает immediate error.
5. Approved modifications конвертируются и применяются.
6. Commit message и commands освобождаются только после approve.
7. Partial apply failure возвращает `success=false` и warning notification.
8. Invalid protocol modifications отбрасываются без repository call.
9. Owned workspace используется без restore.
10. Missing workspace восстанавливается перед requestedViews approval.
11. Restore failure, missing assistant, missing views и resolver failure возвращают explicit errors.
12. Successful requestedViews approval возвращает `Continue` с `ClaudeCodeTurnCommand`.
13. ASSISTANT history не дублируется, если latest content уже совпадает.

Главный закреплённый контракт:

`text
ApprovalService либо возвращает Immediate result,
либо возвращает Continue command.
Transport внутри ApprovalService не запускается.
`

## Покрытие thin facade

Добавлено 6 boundary-тестов.

Проверено:

1. `status` отражает persisted clipboard status.
2. User input в `AWAITING_PASTE` отклоняется без transport calls.
3. User input в `AWAITING_APPROVE` без pending modifications отклоняется.
4. Invalid approve возвращает immediate error без transport.
5. Command results вне `SESSION_ACTIVE` отклоняются.
6. Reset переводит session в IDLE и вызывает transport shutdown.

Characterization tests продолжают проверять полноценные happy-path сценарии фасада:

- first full context → minimal continuation;
- resume fallback;
- modifications approval;
- rejection новым сообщением;
- requested views approval;
- command-results continuation.

## Тестовая пирамида после STEP 2B

`text
Pure protocol tests
ClaudeCodeResponseProcessorTest
ProtocolConverter tests
TokenEstimatorTest

State invariant tests
ClaudeCodeWorkspaceHolderTest
PendingModificationsStore tests

Direct component unit tests
ClaudeCodeViewResolverTest
ClaudeCodeResponseHandlerTest
ClaudeCodeTurnExecutorTest
ClaudeCodeWorkspaceServiceTest
ClaudeCodeApprovalServiceTest

Facade boundary tests
ClaudeCodeInteractionServiceFacadeTest

Facade characterization tests
ClaudeCodeInteractionServiceCharacterizationTest
ClaudeCodeInteractionServicePinTest
ClaudeCodeInteractionServiceScenarioTest

Full regression
gradlew test
`

## Правила, соблюдённые на этапе

1. Production code не менялся ради удобства тестирования.
2. Каждый component test создаёт новый instance тестируемого класса.
3. Tests не зависят от порядка запуска.
4. Recording fakes не содержат assertions.
5. Проверялись ожидаемые вызовы и отсутствие запрещённых вызовов.
6. Characterization tests не заменялись component tests.
7. IntelliJ fixture для application-компонентов не использовался.
8. Failure paths проверялись напрямую, а не только через facade.

## Definition of Done

- [x] Для каждого extracted-компонента существует отдельный test class.
- [x] ViewResolver покрыт happy paths, source routing и failures.
- [x] ResponseHandler покрыт persistence и state transitions.
- [x] TurnExecutor напрямую покрыт resume fallback.
- [x] Workspace restore покрыт без facade.
- [x] Approval views и modifications покрыты раздельно.
- [x] Общие recording fakes переиспользуются.
- [x] Thin-facade границы закреплены отдельными tests.
- [x] Characterization suite сохранён.
- [x] Полный `maxvibes-application:test` зелёный.
- [x] Полный `gradlew test` зелёный.

## Результат

После STEP 2A Claude Code pipeline стал структурно разделённым.

После STEP 2B границы каждого компонента стали исполняемыми спецификациями.

Теперь изменение одного участка pipeline можно проверять:

- локально через direct unit tests;
- на границе через facade tests;
- сквозным образом через characterization tests;
- системно через полный regression suite.
