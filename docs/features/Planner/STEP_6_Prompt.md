# STEP 6 — Prompt: инструкции модели по ведению плана

## Цель

Научить модель пользоваться полем `plan`: когда создавать, как обновлять, как связывать с PLAN . md -доками.

## Файлы

-* * Изменить:** `maxvibes-plugin/src/main/resources/prompts/claude-code-system.md`
-* * Изменить:** `maxvibes-plugin/src/main/resources/prompts/chat-system.md`
-* * Синхронизировать:** `.maxvibes/prompts/*`(
    локальные оверрайды,
    если юзер их использует
) и корневой `CLAUDE.md` (раздел про формат ответа).

## Содержание инструкции (черновик секции)

```markdown
## Plan(planner panel)

For any multi - step task, maintain a plan via the optional `plan` response field.The IDE pins it above the chat with checkboxes.

-Create the plan in your FIRST response to a multi -step task : 3–10 concrete steps, short imperative titles.
-The field is a FULL SNAPSHOT : always send the complete plan, not a diff.Omit the field entirely when nothing changed.
-As soon as a step is done, resend the plan with that step `DONE`(or `SKIPPED` with a reason in `message`) and the next step `IN_PROGRESS` — tick and go on.
-Exactly one step should be `IN_PROGRESS` at a time.
-`currentPlan` in the request is the live state — the user may have toggled checkboxes manually; respect it as the source of truth.
-When the project keeps plan docs (docs / features / < X > / PLAN.md + STEP_N.md), set `docPath` on the plan and on each step to link them . Keep the docs and the plan consistent .
-Send `"steps": []` to dismiss the plan .
-Statuses: PENDING | IN_PROGRESS | DONE | SKIPPED.
```

-Плюс поле `plan` в описание формата ответа(
    там же,
    где `requestedViews` / `modifications`
), и `currentPlan` — в описание payload 'а запроса.

## Что НЕ делать

-Не заставлять модель создавать план для тривиальных однострочных задач — формулировка «multi - step task ».

## Definition of Done

-Оба системных промпта содержат секцию Plan и упоминание поля в схеме ответа .
-`CLAUDE.md` синхронизирован (payload + response format).
