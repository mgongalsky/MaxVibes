# STEP 2A — Глубокая нарезка ClaudeCodeInteractionService

Статус: В РАБОТЕ. Продолжение STEP 2. Дизайн-решения согласованы с пользователем 2026-07-28.

## Проблема

STEP 2 выполнен: извлечены `ProtocolConverter`, `PendingModificationsStore`, `TokenEstimator`,
добавлена сценарная матрица (7 тестов на фейках). Но DoD по размеру не достигнут: сервис —
**971 строка** при цели ~700. Файл вырос со времени написания плана (questions, diagram,
shell-команды, attached images, planner panel), и крупные конвейеры остались внутри.

## Согласованный дизайн

### Решение 1: ResponseProcessor — чистая функция (выбрано пользователем)

`ClaudeCodeResponseProcessor` НЕ делает побочных эффектов. Он принимает `InteractionResponse`
+ минимальный контекст и возвращает `Outcome`:

```kotlin
object ClaudeCodeResponseProcessor {
data class Outcome(
val result: ClaudeCodeStepResult,
val intents: List<Intent>
)

/** Побочные эффекты, которые сервис обязан исполнить В ПОРЯДКЕ СПИСКА. */
sealed interface Intent {
// примерный состав — уточнить по фактическому телу processResponse:
// HoldPending(mods, commands, commitMessage)   → pendingStore.hold(...)
// Transition(event)                            → sessionManager.transition(...)
// PersistRequestedViews(views)                 → в последний ASSISTANT-message
// SavePlan(plan)                               → session.withPlan(...)
// ApplyModifications(mods)                     → codeRepository (Completed-ветка)
// Notify(...)                                  → notificationPort
}

fun process(response: InteractionResponse, ctx: Context): Outcome
}
```

Сервис исполняет намерения одним коротким интерпретатором. Порядок исполнения — контракт
(hold → transition → persist), фиксируется тестами.

Плюс подхода: вся протокольная логика (mixed-правила, ветвление на 4 результата, plan/diagram)
тестируется таблично, без единого фейка. Минус: обвязка intents — принят осознанно.

### Решение 2: сначала пиновые тесты (выбрано пользователем)

До любого выноса — допиновать ветки, которые сценарная матрица STEP 2 не покрывает:
plan-снапшот (включая пустой steps = очистка), diagram, questions (AwaitingQuestions),
персист requestedViews в доменную сессию, notify-сообщения.

## План

| Шаг | Что | DoD |
|-----|-----|-----|
| 2A.1 | Пиновые тесты непокрытых веток processResponse (на существующих фейках, через handleUserInput) | ветки plan/diagram/questions/persist зафиксированы, всё зелёное |
| 2A.2 | Вынести `ClaudeCodeResponseProcessor` (чистая функция + Intent) + интерпретатор intents в сервисе | пины и матрица зелёные без правки ассертов; юнит-тесты процессора табличные |
| 2A.3 | Вынести `ClaudeCodeRequestFactory`: buildRequest + политика full/minimal context + PLAN_ONLY_SUFFIX | поведение 1:1, тесты зелёные |
| 2A.4 | Вынести workspace-holder: `sessionState`/`sessionStateOwner`/`ensureWorkspace` (по образцу PendingModificationsStore) | инвариант владения покрыт юнитами |
| 2A.5 | Полный `gradlew test`, замер размера, обновление PLAN.md | сервис ≤ ~500–600 строк, всё зелёное, коммит |

Каждый шаг заканчивается зелёным полным прогоном и коммитом.

## Инварианты и запреты

1. **Поведение не меняется.** Существующие тесты не редактируются под новое поведение.
2. **`ClipboardInteractionService` не трогать.** Известное расхождение его `estimateTokens`
(не считает currentMessage/specificPrompt/ideErrors/images) — НЕ баг для 2A.
3. Новые классы — в `com.maxvibes.application.service`, без новых межмодульных зависимостей.
4. PSI-ограничения плагина: REPLACE_FILE для init-блоков, не мешать DELETE+CREATE в одном батче.

## Активы

- Сценарная матрица: `ClaudeCodeInteractionServiceScenarioTest` (7 сценариев, реальный `ClipboardSessionManager`).
- Фейки: `maxvibes-application/src/test/kotlin/com/maxvibes/application/testsupport/` (порт, репо сессий, контекст, код-репо, нотификации, промпты).
- Извлечено в STEP 2: `ProtocolConverter`, `PendingModificationsStore`, `TokenEstimator` (в сервисе — двухстрочные делегаты).

## Целевая структура (схема)

- **Оркестратор (остаётся)**: `ClaudeCodeInteractionService` — handleUserInput / approve / submitCommandResults / status / reset + тонкий doSend + интерпретатор Intent.
- **Response-конвейер**: `ClaudeCodeResponseProcessor` (+ `PendingModificationsStore`, `ProtocolConverter`).
- **Request-сборка**: `ClaudeCodeRequestFactory` (+ `InteractionRequestBuilder`, `TokenEstimator`).
- **Workspace**: holder для sessionState/owner/ensureWorkspace.
- **Общее**: `ClipboardSessionManager`, `ClaudeCodePort` — без изменений.
