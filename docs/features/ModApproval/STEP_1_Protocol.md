# Шаг 1 — Протокол: explanation

## Цель

Каждая модификация может нести краткое пояснение, зачем она нужна.Поле опциональное, обратно совместимое .

## Изменения

1.`maxvibes-domain/.../interaction/ClipboardProtocol.kt` — `InteractionModification`: добавить `val explanation: String = ""` .
2.`maxvibes-application/.../output/InteractionRequestSchema.kt` — константа `MOD_EXPLANATION = "explanation"` (ключи только через схему, в кодеке не хардкодить).
3.`maxvibes-plugin/.../clipboard/JsonInteractionProtocolCodec.kt` — `parseModification`: читать опциональное поле.4.Промпты — `maxvibes-plugin/src/main/resources/prompts/chat-system.md` и `claude-code-system.md`(
    и копии в `.maxvibes/prompts/`
): в описание модификаций добавить `explanation` — «1 - 2 предложения на человеческом языке: зачем эта правка.Пиши для пользователя, не для компилятора».

## Acceptance

-Ответ без `explanation` парсится как раньше .
-Ответ с `explanation` доносит текст до `InteractionModification.explanation`.
-Юнит - тест кодека на оба случая.
