# STEP 2A — Глубокая нарезка ClaudeCodeInteractionService

Статус: ВЫПОЛНЕН (2026-07-28). Продолжение STEP 2. Дизайн-решения согласованы с пользователем.

## Проблема

STEP 2 выполнен: извлечены `ProtocolConverter`, `PendingModificationsStore`, `TokenEstimator`,
добавлена сценарная матрица (7 тестов на фейках). Но DoD по размеру не достигнут: сервис —
**971 строка** при цели ~700. Файл вырос со времени написания плана (questions, diagram,
shell-команды, attached images, planner panel), и крупные конвейеры остались внутри.

## Согласованный дизайн

### Решение 1: ResponseProcessor — чистая функция (выбрано пользователем)

`ClaudeCodeResponseProcessor` НЕ делает побочных эффектов: принимает `InteractionResponse`
+ `Context` и возвращает `Outcome(result, intents)`. Итоговый состав Intent (по фактическому
телу processResponse):

```kotlin
sealed interface Intent {
data class SavePlan(val plan: TaskPlan?)                  // null = очистка; только если response.plan != null
data class AppendAssistantHistory(val message: String)
data class PersistRequestedViews(val views: List<CodeViewRequest>)
data class Transition(val hasRequestedViews: Boolean)     // ClipboardEvent.ResponseReceived
data class HoldPending(val modifications, val commands, val commitMessage)
}
```

Сервис исполняет намерения одним интерпретатором строго в порядке списка
(SavePlan → AppendAssistantHistory → PersistRequestedViews → Transition → HoldPending) —
порядок воспроизводит порядок побочных эффектов до извлечения и прибит тестами.
Логирование/sessionLog остались в сервисе (наблюдаемость, не поведение).

### Решение 2: сначала пиновые тесты (выбрано пользователем)

До выноса допинованы ветки, которые сценарная матрица STEP 2 не покрывала:
plan-снапшот (замена/очистка/отсутствие), diagram, questions, персист requestedViews,
thinking+reasoning, blank message → "Done.", planOnly.

## Результаты

| Шаг | Что сделано | Итог |
|-----|-------------|------|
| 2A.1 | 11 пиновых тестов через handleUserInput (`ClaudeCodeInteractionServicePinTest`) | 214/214 зелёные |
| 2A.2 | `ClaudeCodeResponseProcessor` (чистая функция + Intent) + 10 табличных тестов; processResponse → интерпретатор | 224/224 без правки существующих ассертов |
| 2A.3 | `ClaudeCodeRequestFactory`: один параметр `fullContext` разворачивается в isFirstMessage+addHistory (рассинхрон флагов невозможен); PLAN_ONLY_SUFFIX и omitSystemInstruction=true — в фабрике; buildRequest и companion удалены | 224/224 |
| 2A.4 | `ClaudeCodeWorkspaceHolder`: state+owner с атомарными install/clear, isOwnedBy; sessionState/sessionStateOwner — read-only алиасы; ensureWorkspace ставит workspace через install; + 4 юнит-теста инварианта | 228/228 |
| 2A.5 | Полный `gradlew test` (application 228, plugin 105, adapter-llm 10 — всё зелёное), замер размера | **865 строк** |

### DoD по размеру: НЕ достигнут

Цель была ≤ ~500–600 строк, факт — 865 (971 → 865). Крупные остатки в сервисе:
`approve` и `submitCommandResults` (конвейеры применения модификаций и команд),
интерпретатор intents + деривация WARN-логов (~80 строк), `doSend` с resume-fallback (~140 строк),
`handleUserInput`/`startOrContinue` (оркестрация), `ensureWorkspace` (тело осталось в сервисе,
холдеру отдана только установка владения — перенос тела тянул бы зависимости портов в холдер).

Варианты дальнейшего движения (решение за пользователем): принять 865 как новый DoD;
отдельный шаг 2B (approve-конвейер, transport-цикл doSend); дешёвый срез — WARN-деривация
в Outcome процессора.

## Инварианты и запреты (соблюдены)

1. **Поведение не изменилось.** Существующие тесты не редактировались под новое поведение.
Принятая микро-девиация: sessionLog-событие "response" логирует raw `response.commands.size`
(до конвертации) — зафиксировано осознанно.
2. **`ClipboardInteractionService` не тронут.** Расхождение его `estimateTokens` — НЕ баг для 2A.
3. Новые классы — в `com.maxvibes.application.service`, без новых межмодульных зависимостей.

## Итоговая структура

- **Оркестратор**: `ClaudeCodeInteractionService` (865) — handleUserInput / approve / submitCommandResults / status / reset + doSend + интерпретатор Intent.
- **Response-конвейер**: `ClaudeCodeResponseProcessor` (чистый) + `PendingModificationsStore` + `ProtocolConverter`.
- **Request-сборка**: `ClaudeCodeRequestFactory` + `InteractionRequestBuilder` + `TokenEstimator`.
- **Workspace**: `ClaudeCodeWorkspaceHolder` (инвариант владения, юнит-тесты).
- **Общее**: `ClipboardSessionManager`, `ClaudeCodePort` — без изменений.

## Тестовые активы

- `ClaudeCodeInteractionServicePinTest` — 11 пинов протокольных веток.
- `ClaudeCodeInteractionServiceScenarioTest` — 7 сценариев полного цикла.
- `ClaudeCodeResponseProcessorTest` — 10 табличных тестов чистой функции.
- `ClaudeCodeWorkspaceHolderTest` — 4 теста инварианта владения.
- `TokenEstimatorTest` — 4 теста оценки токенов.
