# STEP 1 — Domain: TaskPlan, PlanStep, ChatSession.plan

## Цель

Ввести доменную модель плана и прицепить её к `ChatSession` так, чтобы она автоматически персистилась существующим стеком .

## Файлы

-* * Новый:** `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/planning/TaskPlan.kt`
-* * Изменить:** `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/chat/ChatSession.kt`

## Модель

```kotlin
package com.maxvibes.domain.model.planning

enum class PlanStepStatus { PENDING, IN_PROGRESS, DONE, SKIPPED }

data class PlanStep(
    val id: String,
    val title: String,
    val status: PlanStepStatus = PlanStepStatus.PENDING,
    /** Относительный путь к STEP_N.md шага, если есть. */
    val docPath: String? = null
)

data class TaskPlan(
    val title: String,
    /** Относительный путь к PLAN.md фичи, если есть. */
    val docPath: String? = null,
    val steps: List<PlanStep> = emptyList()
) {
    val doneCount: Int get() = steps.count { it.status == PlanStepStatus.DONE || it.status == PlanStepStatus.SKIPPED }
    val isComplete: Boolean get() = steps.isNotEmpty() && doneCount == steps.size
    fun withStepStatus(stepId: String, status: PlanStepStatus): TaskPlan =
        copy(steps = steps.map { if (it.id == stepId) it.copy(status = status) else it })
}
```

## ChatSession

-Добавить поле `val plan: TaskPlan? = null`.
-Добавить `fun withPlan(plan: TaskPlan?): ChatSession` — обновляет `updatedAt`(по образцу `withClipboardStatus`).

## Persistence — проверить

-Найти сериализацию сессий(`ChatHistoryService` / persistence - адаптер `ChatSessionRepository`): если сессии сериализуются автоматически (kotlinx / Gson по data class), новое nullable -поле обратно -совместимо.Если есть ручной маппинг DTO — добавить поле плана в DTO и маппер.
-Старые сохранённые сессии без поля `plan` должны загружаться как `plan = null` (не падать).

## Что НЕ делать

-Никакой логики протокола / UI — только модель .
-Не трогать `TokenUsage` / `ClipboardSessionStatus`.

## Definition of Done

-`:maxvibes-domain` компилируется; `withPlan` и `withStepStatus` покрыты простым unit -тестом.
-Существующие сессии загружаются без ошибок(ручная проверка после Step 5 допустима).
