# Шаг 2 — Application: селективный approve

## Цель

`approve` умеет применять подмножество pending - модификаций; сводка об отклонённых уходит LLM как фидбек.

## Изменения(`ClaudeCodeInteractionService`)

1.Сигнатура: `approve(sessionId, selectedIndices: Set<Int>? = null, ...)` — `null` =
    применить все (текущее поведение кнопки сохраняется).2.`approvePendingModifications`: применяет только выбранные; для отклонённых формирует сводку вида `Applied 2 of 3 proposed modifications. Rejected by user: REPLACE_ELEMENT <path>, ...` .
3.Сводка сохраняется как префикс к следующему сообщению пользователя (по образцу reject - by - typing) — БЕЗ автоматического round - trip.4.Результат - состояние для UI: вариант `ClaudeCodeStepResult`, сигнализирующий AWAITING_APPROVE, несёт список pending - правок:
`data class PendingModView(index: Int, type: String, path: String, explanation: String, content: String)`
(`content` нужен Шагу 4 для диффа).
5.Reject - by - typing остаётся : новое сообщение при непустом pending = отклонить всё.

## Acceptance

-`approve(sessionId, setOf(0, 2))` применяет правки 0 и 2; правка 1 не применяется.
-Сводка об отклонённых попадает в следующий запрос к LLM.
-Юнит - тесты application -слоя без IDE(фейковый `CodeRepository`).
