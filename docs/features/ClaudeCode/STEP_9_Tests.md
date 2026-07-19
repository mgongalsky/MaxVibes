# Step 9 — Tests: перечень и команды

## Цель

Зафиксировать полный набор автотестов для фичи Claude Code и команды их запуска . Большинство тестов уже описаны в соответствующих STEP -файлах — этот файл собирает их в один чек - лист и добавляет интеграционный сценарий.

## Что покрыто

### Domain(Step 1)

Не пишем — только data - shifts, нечего тестировать . `ChatSessionTest` (если существует) должен пройти без изменений.

### Application port (Step 2)

Не пишем — это интерфейс.

### Plugin adapter (Step 3)

**Smoke - test вручную * * — описан в STEP_3 . Полный unit - тест отложен (требует моков процесса; инвестиция > выгода для MVP).

Желательно(но не обязательно для MVP) — `StreamJsonProtocolTest`:
-`extractSessionId_systemInitLine_returnsId`
-`extractSessionId_unrelatedLine_returnsNull`
-`extractSessionId_malformedJson_returnsNull`
-`extractAssistantText_assistantLine_returnsText`
-`isTurnEnd_resultLine_returnsTrue`

Эти тесты — pure JSON parsing, легко пишутся, дают уверенность в самом хрупком месте .

### System prompt (Step 4)

`PromptServiceTest`(или новый):
-`claudeCodeSystem_returnsNonEmpty`
-`claudeCodeSystem_containsToolBan`(проверяет наличие ключевой инструкции)

### Application service (Step 5)

`ClaudeCodeInteractionServiceTest` — полный набор :
-`firstMessage_withModifications_appliesAndCompletes`
-`firstMessage_withRequestedViews_transitionsToAwaitingApprove`
-`approve_gathersFilesAndSends`
-`approve_inIdle_returnsError`
-`approve_inAwaitingPaste_returnsError`(cross - mode защита)
-`transportError_returnsTransportError`
-`resume_failedWithFallback_marksNeedsFullContextAndRetries`
-`handleUserInput_inAwaitingApprove_returnsError`
-`responseWithEmptyMessage_handledGracefully`
-`parseError_returnsErrorWithoutCrashing`

### Session manager (Step 6)

`ClipboardSessionManagerTest` — добавить кейсы (см.STEP_6):
-`responseReceivedWithViews_inSessionActive_transitionsToAwaitingApprove`
-`responseReceivedWithoutViews_inSessionActive_staysInSessionActive`
-`responseReceivedWithViews_inAwaitingApprove_staysInAwaitingApprove`
-`approved_inAwaitingApprove_transitionsToSessionActive`
-`approved_inIdle_warnsAndReturnsFalse`
-`jsonCopied_inAwaitingApprove_warnsAndReturnsFalse`
-`reset_inAwaitingApprove_transitionsToIdle`

### UI(Step 8)

Если есть существующий `ChatPanelTest` :
-`claudeCodeMode_awaitingApprove_showsApproveButton`
-`claudeCodeMode_idle_hidesApproveButton`
-`claudeCodeMode_sending_disablesSendAndApprove`
-`claudeCodeMode_hidesCopyJsonAndPasteButtons`

UI - тесты гоняются через IntelliJ runner, не через Gradle.

## Команды запуска

        Domain + application + adapter - llm — через Gradle :

```bash
    ./ gradlew : maxvibes -domain:test
    ./ gradlew : maxvibes -application:test
    ./ gradlew : maxvibes -adapter - llm:test
```

Принудительно перезапустить (если кеш UP - TO - DATE):

```bash
    ./ gradlew : maxvibes -application:test-- rerun -tasks
```

Полный билд :

```bash
    ./ gradlew build
```

UI - тесты — через IntelliJ runner:

1.Открыть тест в IDEA → правой кнопкой → Run
2.Не использовать Gradle для этих тестов (известный конфликт `kotlinx-coroutines-debug` javaagent)

## Стиль тестов (project conventions)

-* * `runBlocking` * *, не `runTest` (нет dependency `kotlinx-coroutines-test`)
-* * MockK * * для моков, `coEvery` для suspend - функций
-* * Реальные data classes * * где возможно, моки только для портов и сервисов
        -Имена тестов в `backtick form` : `` ` firstMessage_withModifications_appliesAndCompletes `() ``
-Группировать через `@Nested` если кейсов > 5 в одном классе

## Acceptance criteria

        -[] Все тесты из списков выше написаны и проходят
-[] `./gradlew test` зелёный целиком
        -[] Покрытие `ClaudeCodeInteractionService` ≥ 80 % по строкам (по визуальной оценке — все основные ветки)
-[] `ClipboardSessionManagerTest` дополнен и не сломался
