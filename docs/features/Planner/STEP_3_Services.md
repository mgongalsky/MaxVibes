# STEP 3 — Services: персист плана из ответа, план в исходящем запросе

## Цель

Оба interaction -сервиса(CLAUDE_CODE и CLIPBOARD) начинают :(а) сохранять план из ответа модели в `ChatSession`, (б) вкладывать актуальный план сессии в исходящий запрос (`currentPlan`).

## Файлы

-* * Изменить:** `maxvibes-application/.../service/ClaudeCodeInteractionService.kt`
-* * Изменить:** `maxvibes-application/.../service/ClipboardInteractionService.kt`
-* * Изменить:** `maxvibes-application/.../service/InteractionRequestBuilder.kt` — параметр `currentPlan: TaskPlan? = null`, прокладывается в `ClipboardRequest`.
-* * Изменить:** `maxvibes-domain/.../model/interaction/ClipboardProtocol.kt` — поле `currentPlan: TaskPlan? = null` в `ClipboardRequest` (если не сделано в Step 2).

## Логика в `processResponse`(оба сервиса)

1.`response.plan != null` → обновить сессию : `chatSessionRepository` -путём `session.withPlan(...)`; пустой список шагов → `withPlan(null)`.2.Обновление плана НЕ меняет `clipboardStatus` и не влияет на state machine — план ортогонален approve - циклу.3.Результат шага (`ClaudeCodeStepResult` / `ClipboardStepResult`) должен донести факт « план обновился» до UI . Минимальный вариант: ничего не добавлять — контроллер после каждого шага перечитывает активную сессию и вызывает `render(buildState())`; проверить, что это так, иначе добавить поле `planUpdated: Boolean` .

## Логика в сборке запроса

        -Перед `buildRequest` читать актуальную сессию из репозитория и передавать `session.plan` в `InteractionRequestBuilder` .
-Включается и в full -, и в minimal - контекст: план маленький, а ручные toggles юзера должны доходить до модели .

## Ручной toggle (подготовка к Step 5)

-Публичный метод в `ChatTreeService` (или рядом с session -операциями): `setPlanStepStatus(sessionId, stepId, status)` — читает сессию, `plan.withStepStatus(...)`, сохраняет.UI дергает его напрямую, interaction - сервисы не участвуют.

## Что НЕ делать

-Не трогать pendingModifications / commands - логику.
-Не менять сигнатуры существующих публичных методов сервисов без необходимости — `currentPlan` добирается из репозитория внутри, а не через новые параметры `handleUserInput` .

## Definition of Done

-Ответ с `plan` → сессия в репозитории содержит план(unit - тест с фейковым репозиторием).
-Ответ без `plan` → план сессии не изменился .
-Исходящий запрос содержит `currentPlan`, когда план есть, и не содержит — когда нет .
