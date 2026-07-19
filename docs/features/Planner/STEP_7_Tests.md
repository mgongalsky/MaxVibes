# STEP 7 — Tests

## Цель

Unit - тесты на доменную модель, кодек и сервисный слой планнера.

## Файлы

-* * Новый:** `maxvibes-domain/src/test/kotlin/.../planning/TaskPlanTest.kt`
-* * Изменить / новый:** тесты кодека рядом с существующими тестами `JsonInteractionProtocolCodec`(найти текущий тест - класс и расширить).
-* * Изменить / новый:** тесты `ClaudeCodeInteractionService` (расширить существующие из `docs/features/ClaudeCode/STEP_9_Tests.md` -набора).

## Кейсы

**Domain:**
-`withStepStatus` меняет только целевой шаг; неизвестный id — no -op.
-`doneCount` считает DONE + SKIPPED; `isComplete` false при пустых steps .
-`ChatSession.withPlan` обновляет `updatedAt`; `withPlan(null)` очищает .

**Codec:**
-Ответ с валидным планом → корректная `TaskPlan`(
    включая docPath 'ы).
            - Ответ без поля `plan` → `plan == null`,
    остальное парсится как раньше .
            - Битый статус → PENDING; шаг без id → порядковый id; шаг без title → выброшен; поле `plan` совсем битое → ответ парсится, план null(
    и залогировано
).
-`steps: []` → маркер очистки (по контракту Step 2).
-Encode запроса : `currentPlan` присутствует ↔ план передан .

**Service(фейковые порты, по образцу существующих тестов):**
-`processResponse` с планом → репозиторий содержит план у сессии; `clipboardStatus` не изменился.
-Ответ без плана → план не тронут.
-`setPlanStepStatus` → статус шага обновлён и сохранён.
-Следующий `doSend` после ручного toggle кладёт обновлённый план в запрос .

## Definition of Done

-`./gradlew :maxvibes-domain:test :maxvibes-application:test` зелёные .
-Существующие тесты не сломаны .
