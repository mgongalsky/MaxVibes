# Шаг 3 — UI: карточка аппрува

## Цель

Вместо серой строки — заметная интерактивная карточка pending -модификаций в ленте чата .

## Изменения

1.`ConversationPanel`: `addPendingModsBubble(rows, onApply: (Set<Int>) -> Unit, onReject: () -> Unit, onDiff: (Int) -> Unit): PendingModsBlockView` — по образцу `addCommandBubble`.
-Строка правки : иконка по типу (переиспользовать `ModificationCategory` / `toCategory`), кликабельный путь (`clickableLabel` + существующий `onNavigateToPath`), `explanation` серым, кнопка Diff, чекбокс(
    по умолчанию включён
).
-Футер: `Apply selected (N)` / `Apply all` / `Reject all`; счётчик N живёт от чекбоксов.
-`interface PendingModsBlockView { fun setResolved(applied: Set<Int>); fun setRejectedAll() }` — заморозка блока после решения .
2.`ChatPanelCallbacks` += `addPendingModsBubble(...)`; реализация в `ChatPanel` делегирует в `ConversationPanel` .
3.`ChatMessageController.handleClaudeCodeResult`: при результате с pending -модификациями рендерит карточку; `approve()` принимает выбранные индексы . Набор текста пользователем замораживает карточку (аналог `dismissQuestionTurn`).4.Amber - кнопка Approve остаётся для requestedViews(
    сбор файлов
); для модификаций главное управление — карточка (кнопка эквивалентна Apply all).

## Acceptance

-Ответ с 3 модификациями показывает карточку с 3 строками, чекбоксы включены .
-Снятие чекбокса меняет счётчик; Apply selected применяет ровно выбранные.
-Reject all / набор текста замораживают карточку, ничего не применяется.
-Пути кликабельны и ведут к элементам .
