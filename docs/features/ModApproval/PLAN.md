# ModApproval + PsiLinks — план

## Цель

Прозрачный диалог LLM ↔ пользователь вокруг правок кода :

1.Каждая модификация несёт краткое пояснение(`explanation`) — зачем нужен этот кусок .
2.Перед применением пользователь видит карточку аппрува : список правок с пояснениями, диффом и выбором — применить выбранные, все или отклонить всё .
3.В тексте сообщений LLM — кликабельные ссылки на PSI - элементы(функции, классы, файлы).

## Аудит текущего состояния(июль 2026)

-* * Claude Code режим * *: гейт уже есть — `pendingModifications` + `AWAITING_APPROVE` в `ClaudeCodeInteractionService`, amber - кнопка Approve в `ChatPanel` . Но всё - или - ничего, без предпросмотра; в процессе видна только серая статусная строка.
-* * Clipboard режим * * : гейта нет — `processUnifiedResponse` применяет модификации сразу .
-* * `ConversationPanel` * * уже умеет интерактивные блоки с view - handle: `CommandBlockView`, `QuestionBlockView`, `CommandBatchBarView` — карточка аппрува копирует этот паттерн.
-* * Навигация * * прошита насквозь: `ConversationPanel(onNavigateToPath)` → `ChatNavigationHelper.navigateToElement`; `MarkdownRenderer.createPane(onLink)` отдаёт туда все не - http hrefs .
-* * Кодек * * стоит на `ignoreUnknownKeys` — новые поля протокола обратно совместимы .

## Шаги

1.* * STEP_1_Protocol.md * * — поле `explanation` в протоколе +промпты.2.* * STEP_2_SelectiveApprove.md * * — селективный approve в application -слое.3.* * STEP_3_UICard.md * * — карточка аппрува в ленте чата.4.* * STEP_4_Diff.md * * — Diff - окно per -модификация.5.* * STEP_5_PsiLinks.md * * — PSI - ссылки в тексте сообщений .
6.* * STEP_6_ClipboardGate.md * * — тот же гейт в clipboard - режиме(follow - up).7.* * STEP_7_Tests.md * * — тесты.Порядок: 2 → 3 → 4(
    ядро
), 1 — параллельно в любой момент, 5 — независим, 6 — после обкатки в Claude Code режиме, 7 — по ходу и в конце.

## Решения и trade - offs

-`explanation` НЕ вшивается в sealed - иерархию `Modification` — живёт в протокольном `InteractionModification` и доезжает до UI отдельным полем.Домен не трогаем.
-Фидбек об отклонённых правках НЕ отправляется автоматическим round -trip: он префиксуется к следующему сообщению пользователя — так уже работает reject - by - typing.
-Ввод при показе карточки остаётся активным : набор текста =
    отклонить всё (существующая семантика), карточка замораживается как question -блоки.

## Ограничения → TODO

-pending - набор in -memory: рестарт IDE до Approve теряет его — см . docs / TODOs / pending -modifications - lost - on - restart.md.
-Связано с существующим TODO modification - results - not - fed - back - to - llm — селективный approve обостряет вопрос .
-Diff для element - правок показывает элемент, а не файл с контекстом — сознательное упрощение MVP.
