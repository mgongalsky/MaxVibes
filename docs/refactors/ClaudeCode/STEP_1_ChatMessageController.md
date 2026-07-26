# STEP 1 — Распил ChatMessageController (1534 LOC)

Цель: контроллер остаётся тонким маршрутизатором; state machines и режимные диспетчеры — отдельные тестируемые классы.Поведение не меняется.

## Что выделяем (в порядке выполнения)

### 1.1 `CommandTurnCoordinator`
        Вся command -turn state machine: `CommandTurn`, `CommandItem`, `presentCommands`, `startRunAll`, `runNextQueued`, `declineAllRemaining`, `declineItem`, `runCommand`, `recordExecution`.

-Зависимости: `ExecuteCommandUseCase`, колбэки рендера (`addCommandBubble`, `addCommandBatchBar`) и завершения батча(
    `onBatchComplete(resultsForLlm)`
).
-Правила сохраняем : Run all останавливается на первом ненулевом exit code; батч завершён — авто -продолжение диалога .
-Тесты: полный жизненный цикл(run all, decline all, частичный decline, стоп по ошибке).

### 1.2 `QuestionTurnCoordinator`
        Question - turn state machine: `QuestionTurn`, `QuestionItem`, `presentQuestions`, `answerQuestion`, `dismissQuestionTurn`.

-Правила сохраняем : ввод в главное поле = dismiss всех блоков; все отвечены — составной ответ через обычный send -путь.
-Тесты: ответ по кнопке, свободный ответ, dismiss печатанием .

### 1.3 Режимные диспетчеры
`dispatchApiMessage` / `dispatchCheapApiMessage` / `dispatchClipboardMessage` / `dispatchClaudeCodeMessage` + соответствующие `handle*Result` → 3–4 класса -диспетчера(
    Api и CheapApi можно объединить параметром
).Контроллер держит только `sendMessage` -роутинг по `InteractionMode`.

-`ClaudeCodeDispatcher` — приоритетный: `dispatchClaudeCodeMessage`, `handleClaudeCodeResult`, `approve`, `buildTokenInfoForClaudeCode`, взаимодействие с координаторами из 1.1 / 1.2.

### 1.4 Остаётся в контроллере
        Attachments(trace / errors / images), one - shot skills, session - операции(уже покрыты тестами), роутинг.

## Риски

-`runBlocking` в контроллере — при выносе диспетчеров проверить, что EDT не блокируется (фоновые Task . Backgroundable сохраняем как есть).
-`ChatPanelCallbacks` пока НЕ сужаем — интерфейс режем в STEP_3, чтобы не смешивать два распила.
-PSI - ограничение: новые классы — отдельными файлами(`CREATE_FILE`), правки контроллера — `REPLACE_ELEMENT` по одному методу.

## Definition of Done

-[] `ChatMessageController` ≤ ~600 LOC, без внутренних state - machine классов .
-[] `CommandTurnCoordinator`, `QuestionTurnCoordinator`, `ClaudeCodeDispatcher` — отдельные файлы с юнит -тестами на `FakeChatPanelCallbacks`.
-[] Поведение в IDE не изменилось (smoke: send, approve, команды run / decline, вопросы).
-[] `./gradlew test` зелёный.
