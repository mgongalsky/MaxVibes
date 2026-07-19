# STEP 2 — Protocol: поле `plan` в JSON -ответе LLM

## Цель

Научить протокол переносить план : модель присылает snapshot плана в новом опциональном поле `plan` ответа; кодек толерантно его парсит в доменную `TaskPlan`.

## Файлы

-* * Изменить:** `maxvibes-domain/.../model/interaction/ClipboardProtocol.kt` — поле `plan: TaskPlan? = null` в `InteractionResponse` .
-* * Изменить:** `maxvibes-application/.../port/output/InteractionRequestSchema.kt` — константы схемы (имена полей +описание для промпта).
-* * Изменить:** `maxvibes-plugin/.../clipboard/JsonInteractionProtocolCodec.kt` — decode поля `plan`; encode плана в исходящий запрос(
    см.ниже
).

## Формат в ответе LLM

```json
{
    "message": "...",
    "plan": {
    "title": "Planner feature",
    "docPath": "docs/features/Planner/PLAN.md",
    "steps": [
    { "id": "1", "title": "Domain model", "status": "DONE", "docPath": "docs/features/Planner/STEP_1_Domain.md" },
    { "id": "2", "title": "Protocol", "status": "IN_PROGRESS" }
    ]
}
}
```

Семантика:
-Поле отсутствует / null → план сессии НЕ меняется .
-Поле присутствует → полный snapshot, заменяет текущий план целиком .
-`steps: []` при присутствующем поле → план очищается(модель отменила план) — маппить в `plan = null`.

## Правила декодирования (толерантность)

-Неизвестный / битый `status` → `PENDING` (не падать).
-Отсутствующий `id` шага → порядковый номер строки(`"1"`, `"2"`, ...).
-Пустой `title` шага → шаг пропускается; пустой `title` плана → `"Plan"`.
-Любая ошибка парсинга поля `plan` не должна ронять разбор остального ответа — логировать и вернуть ответ без плана .

## Исходящий запрос

        -В запрос (minimal и full) добавить опциональное поле `currentPlan` с тем же форматом — чтобы модель видела актуальное состояние(
    включая ручные toggles юзера
).Включается только когда план у сессии есть.Реализация включения — Step 3; здесь только encode - поддержка в кодеке.

## Что НЕ делать

-Не менять существующие поля запроса / ответа.
-Не добавлять инструкции в системные промпты (это Step 6).

## Definition of Done

-Round - trip тест кодека: JSON с планом → `InteractionResponse.plan` → JSON запроса с `currentPlan` .
-Ответ без поля `plan` парсится как раньше(регрессии нет).
-Битые статусы / шаги не роняют декодер(тест на толерантность).
