# STEP 4 — UI: PlanPanel(pinned, collapsible, чекбоксы, ссылки)

## Цель

Самодостаточный Swing -компонент панели плана.Никакой бизнес -логики: получает `TaskPlan` через `update()`, о действиях юзера сообщает колбэками.

## Файл

-* * Новый:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/PlanPanel.kt`

## API

```kotlin
class PlanPanel(
    private val onToggleStep: (stepId: String, newStatus: PlanStepStatus) -> Unit,
    private val onOpenDoc: (docPath: String) -> Unit
) : JPanel(BorderLayout()) {
    /** Полная перерисовка под новый план; null скрывает панель. EDT only. */
    fun update(plan: TaskPlan?)
}
```

## Вид

-* * Хедер(всегда виден):** стрелка ▸/▾ (collapse / expand), 📋, заголовок плана (кликабелен, если `docPath` есть — открывает PLAN . md), прогресс `3/7 ✓` справа.Прогресс - цвет: серый в работе, зелёный когда `isComplete`.
-* * Тело(collapsible):** строка на шаг — `JBCheckBox` + название.Клик по чекбоксу: PENDING / IN_PROGRESS → DONE, DONE / SKIPPED → PENDING(
    через `onToggleStep`
).Клик по названию шага с `docPath` — `onOpenDoc` .
-Статусы: `IN_PROGRESS` — жирный + синий акцент (цвета как у `liveTurnPanel` : `JBColor(0x2196F3, 0x64B5F6)`); `DONE` — чекнут, серый; `SKIPPED` — чекнут, серый курсив, суффикс `(skipped)`; `PENDING` — обычный.
-Состояние collapsed хранится в поле панели и переживает `update()`(не сбрасывать при каждом рендере).По умолчанию — развёрнута; при `isComplete` — автоматически сворачивается один раз.
-Стиль — по образцу `LiveTurnPanel`: `JBColor.background()`, тонкая верхняя линия - акцент, `JBUI.Borders.empty(4, 6)`.

## Поведение `update()`

        -Идемпотентно: полная пересборка строк из snapshot'а (не diff DOM' а — план маленький).
-Guard от рекурсии: программная установка чекбоксов не должна дёргать `onToggleStep`(
    флаг `suppressEvents`,
    как `suppressModelCombo` в ChatPanel
).
-`plan == null` → `isVisible = false`.

## Что НЕ делать

-Не ходить в сервисы / репозитории — только колбэки.
-Не монтировать в ChatPanel (это Step 5).

## Definition of Done

-Компилируется; предпросмотр через временный `main()` / UI -тест не обязателен — визуальная проверка на Step 8.
-`update()` можно звать многократно без утечек слушателей и потери collapsed -состояния.
