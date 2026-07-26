# STEP 2 — Распил ClaudeCodeInteractionService (984 LOC)

Цель: сервис остаётся оркестратором state machine; конвертация протокола и pending -состояние — отдельные тестируемые классы.Заодно снимаем « осознанное дублирование» с `ClipboardInteractionService` (827 LOC) — стабилизация, на которую ссылаются KDoc -комментарии, давно случилась .

## Что выделяем

### 2.1 `ProtocolConverter` (общий для обоих сервисов)
`convertModification` и `convertCommand` сейчас продублированы в `ClaudeCodeInteractionService` и `ClipboardInteractionService`(
    помечено
    "kept duplicated by conscious decision until both services stabilise"
).

-Новый класс в `maxvibes-application/service/` : `InteractionModification → Modification?`, `InteractionCommand → CommandRequest?`.
-ПЕРЕД удалением дублей: юнит - тесты на конвертер, зафиксировав текущее поведение ОБОИХ вариантов.При расхождении вариантов — стоп, расхождение разбирается с пользователем (это скрытый баг одного из режимов).
-Оба сервиса переходят на общий конвертер; приватные `convert*` удаляются.

### 2.2 `PendingModificationsStore`
        Четвёрка полей `pendingModifications` / `pendingCommands` / `pendingCommitMessage` / `pendingOwner` + `clearPending` → отдельный класс с явным контрактом владения по sessionId .

-Инвариант сохраняем : in -memory only, рестарт IDE до Approve теряет pending -набор(задокументировано в KDoc).
-Тесты: hold → approve, hold → reject(новое сообщение), cross - session guard (pendingOwner ≠ sessionId).

### 2.3 Кандидат (по ситуации): `TokenEstimator`
`estimateTokens` / `estimateOutputTokens` — мелкие чистые функции; выносить, только если после 2.1–2.2 сервис всё ещё > ~700 LOC .

### 2.4 Остаётся в сервисе
        `handleUserInput`, `approve`, `submitCommandResults`, `status`, `reset`, `startOrContinue`, `doSend`, `processResponse`, `ensureWorkspace`, `buildRequest` — это и есть оркестрация, её не режем.

## Тесты(главный выигрыш шага)

Сейчас у Claude Code режима один тест(`ClaudeCodeInteractionServiceThinkingTest`).Добавить на `FakeClaudeCodePort` из STEP_0:
-полный цикл : send → requestedViews → AWAITING_APPROVE → approve → files → completed;
-pending - модификации: propose → approve → apply + release held commands;
-pending - модификации: propose → новое сообщение → reject с префиксом;
-commands - турн: send → commands → submitCommandResults → continue;
-смешанные ответы : commands +requestedViews(commands skip), views + modifications(views skip);
-восстановление workspace после «рестарта» (ensureWorkspace).

## Риски

-PSI - ограничение: не смешивать DELETE_ELEMENT и CREATE_ELEMENT на одном родителе в одном батче; правки сервиса — REPLACE_ELEMENT по одному методу, новые классы — CREATE_FILE .
-`ClipboardInteractionService` трогаем минимально: только замена convert * на общий конвертер.

## Definition of Done

-[] `ProtocolConverter` с тестами; дубли convert * удалены из обоих сервисов.
-[] `PendingModificationsStore` с тестами .
-[] Матрица сценарных тестов сервиса(список выше) зелёная .
-[] `ClaudeCodeInteractionService` ≤ ~700 LOC .
-[] `./gradlew test` зелёный.
