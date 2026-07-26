# STEP 3 — UI: ChatPanel(1485 LOC) и ConversationPanel(956 LOC)

Цель: вернуть `ChatPanel` статус thin view, который ему приписывает ARCHITETURE . md . Чисто структурный шаг — ни один пиксель поведения не меняется.

## 3.1 ChatPanel

        Выделяем по зонам ответственности :

-* * `ChatToolbarPanel` * * — кнопки сессий (Sessions / Branch / New / Del), prompts, ctx, maximize / floating, breadcrumb.
-* * `ClaudeCodeControlsPanel` * * — комбо модели и effort вместе с их sync / commit -логикой(
    `syncModelComboFromSettings`,
    `commitModelComboToSettings`,
    suppress - флаги и т.д.
), CC log link.Вся запись в `MaxVibesSettings` уходит из view сюда .
-* * `PromptFileManagerUI` * * — CRUD промпт -файлов(
    `createNewPromptFile`,
    `editCurrentPromptFile`,
    `deleteCurrentPromptFile`,
    `showPromptSelectionPopup`,
    `buildPromptPanel`
).CRUD файлов — вообще не дело view; логика работы с файлами → в `SpecificPromptService` / repository, UI - класс только рисует.
-* * `AttachmentsStrip` * * — trace / errors индикаторы, image thumbnails, one - shot chip .
-Конструирование `SubscriptionUsagePoller` — из поля панели в `MaxVibesService` (это сервис с lifecycle, а не UI - виджет; dispose через сервис).

Остаётся в `ChatPanel`: layout - сборка, `render(state)`, реализация `ChatPanelCallbacks`, роутинг событий .

### Сужение ChatPanelCallbacks
        После STEP_1 часть колбэков (command / question view handles) нужна только координаторам — разнести интерфейс на 2–3 роли (`ConversationView`, `CommandTurnView`, `QuestionTurnView`), `ChatPanel` реализует все.Это уменьшит и болевой интерфейс из STEP_0.

## 3.2 ConversationPanel

        -* * `BubbleFactory` * * — user / assistant / system bubble, segments, collapsible code / reasoning / footer (`assistantBubble`, `collapsibleFooter`, `collapsibleReasoningPanel`, `buildSummaryHtml`, `detailsPanel`).
-* * `InteractiveBlockFactory` * * — command / question / batch - bar / post - apply - errors блоки с их view - handle реализациями .
-`ConversationPanel` остаётся контейнером: список, скролл, `appendToLast`, делегирование фабрикам .

## Порядок

1.`ClaudeCodeControlsPanel`(приоритет — Claude Code).
2.`PromptFileManagerUI` + перенос файловой логики в service.3.`ChatToolbarPanel`, `AttachmentsStrip`, poller → сервис.4.Разнесение `ChatPanelCallbacks` на роли .
5.`ConversationPanel` фабрики .

## Definition of Done

-[] `ChatPanel` ≤ ~600 LOC, не пишет в settings, не трогает файловую систему .
-[] `ConversationPanel` ≤ ~400 LOC .
-[] Smoke в IDE : все режимы, команды, вопросы, диаграмма, лимит - бары, промпт - CRUD.
-[] `./gradlew test` зелёный.
