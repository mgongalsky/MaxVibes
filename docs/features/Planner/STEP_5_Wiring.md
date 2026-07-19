# STEP 5 — Wiring: план в ChatPanelState, монтаж PlanPanel, навигация в доки

## Цель

Собрать всё : панель появляется над conversation, обновляется через стандартный `render(buildState())`, тики персистятся, клики открывают доки.

## Файлы

-* * Изменить:** `maxvibes-plugin/.../ui/ChatPanelState.kt` — поле `val plan: TaskPlan? = null` .
-* * Изменить:** `maxvibes-plugin/.../ui/ChatPanel.kt`:
-создать `planPanel = PlanPanel(onToggleStep = ..., onOpenDoc = ...)`;
-смонтировать в NORTH - стек над `conversationPanel`(рядом с местом `liveTurnPanel`; порядок: breadcrumb → planPanel → liveTurnPanel → conversation);
-в `buildState()` — `plan = chatTreeService.getActiveSession()?.plan`;
-в `render(state)` — `planPanel.update(state.plan)`;
-в `loadCurrentSession()` / `onSessionChanged` — панель обновляется тем же render -путём(смена сессии = смена плана).
-* * Возможно изменить : * * `ChatMessageController` — если Step 3 показал, что после turn'а render не вызывается, добавить вызов.

## Колбэки

-`onToggleStep(stepId, newStatus)` → `chatTreeService.setPlanStepStatus(activeSessionId, stepId, newStatus)`(из Step 3), затем `render(buildState())` . Никаких запросов к модели — состояние уедет со следующим send.
-`onOpenDoc(docPath)` → открыть файл в редакторе : резолв от project base dir через `LocalFileSystem` +`OpenFileDescriptor`(
    образец — `openClaudeCodeLog()`); несуществующий файл → `statusLabel.text = "Doc not found: <path>"`, не падать .

## Edge cases

        -Смена активной сессии → панель мгновенно показывает план новой сессии (или скрывается).
-План обновился моделью пока liveTurnPanel активен → просто очередной `render` после завершения turn'а; конфликтов нет.
-Ветка(branch) сессии : план НЕ копируется в дочернюю сессию(child стартует с `plan = null`) — проверить, что `branchSession` не тянет поле случайно .

## Что НЕ делать

-Не добавлять новых кнопок в toolbar — вся интеракция внутри панели.

## Definition of Done

-План из ответа модели появляется над чатом без перезапуска.
-Toggle тикается, переживает рестарт IDE, виден модели в следующем запросе.
-Клик по заголовку / шагу открывает соответствующий.md в редакторе.
