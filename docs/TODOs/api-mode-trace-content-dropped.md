# API - режим: содержимое Trace не доходит до модели (только маркер)

**Статус:** открыт — обнаружен попутно в сессии EditorActions, мимоходом не чинить
**Дата:** 2026 - 07 - 17
**Компонент:** `plugin/ui/ChatMessageController.dispatchApiMessage`

## Симптом

В режиме API прикреплённый Trace до модели не доходит.`dispatchApiMessage` кладёт в задачу только маркер :

```kotlin
if (!trace.isNullOrBlank()) append("\n[trace: ${trace.lines().size} lines]")
```

Содержимое трейса дальше никуда не передаётся — модель видит «[trace: 42 lines]» и вынуждена гадать, что там было.

## Контраст с соседними режимами

        -CHEAP_API: `buildTaskWithContext(msg, trace, errs)` — полный текст трейса и ошибок встраивается в текст задачи.
-CLIPBOARD / CLAUDE_CODE: трейс уезжает параметром `attachedContext` (после фикса билдера 2026 - 07 - 17 — на любом ходе сессии; раньше терялся в minimal -режиме).
-IDE errors в API -режиме ДОХОДЯТ : в тексте задачи тоже только маркер `[attached ide errors]`, но содержимое передаётся отдельным полем `ideErrors` в `ContextAwareRequest` .

## Влияние

-Классический сценарий « прикрепи стектрейс и почини» в API -режиме молча деградирует: модель отвечает без данных .
-Фича EditorActions : «Add Element to Context » и attach - element скиллов едут тем же каналом (elementContext → effectiveTrace), поэтому в API - режиме тело элемента тоже теряется.`sendMessage` предупреждает об этом в чате — это смягчение, не фикс .

## Идея фикса

        -Вариант A (минимальный): в `dispatchApiMessage` строить задачу через `buildTaskWithContext`, как уже делает CheapAPI .
-Вариант B (чище): добавить `attachedContext` в `ContextAwareRequest` и прокинуть до `LLMService.chat`; маркер в тексте оставить для истории чата.

## Где чинить

        Вариант A : только `ChatMessageController.dispatchApiMessage`.Вариант B : `ContextAwareRequest` +`ContextAwareModifyService` + `LangChainLLMService`(
    и маппинг в промпт
).
