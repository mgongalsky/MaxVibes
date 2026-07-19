# STEP 3 — UI: персист llmReasoning и футер 💭

## Цель

Убедиться, что `llmReasoning` из step result доезжает до `ChatMessage.reasoning` при
сохранении ассистентского сообщения и отображается в сворачиваемом футере пузыря.Достроить недостающее .

## Контекст(сверено на момент планирования — менять НЕ надо)

-`ChatMessage.reasoning: String?` + `XmlChatMessage.reasoning` `@Tag("reasoning")`
        (нестед - тег, длинный текст ок; null xmlb опускает) — персист готов .
-`ConversationRenderer` уже кладёт `message.reasoning` в `DisplayMessage.reasoning` .
-`ConversationPanel.addAssistantBubble(reasoning=...)` → `collapsibleFooter(..., reasoning, ...)`
→ `buildSummaryHtml` / `detailsPanel` уже принимают reasoning .

**Непроверенное звено * * (в план не попало — файл не читали): `ChatMessageController` —
мапит ли он `ClaudeCodeStepResult.llmReasoning` в `ChatMessage(reasoning = ...)` при
создании ассистентского сообщения, для ОБОИХ исходов(Completed И WaitingForApprove).Clipboard - путь reasoning уже пишет — искать по аналогии .

## Запросить файлы

        -`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatMessageController.kt`(FULL)
-`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatPanel.kt`(SIGNATURES; точечно ELEMENT, если правки нужны)
-`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ConversationPanel.kt`(
    ELEMENT: `class[ConversationPanel]/function[buildSummaryHtml]`
    и `class[ConversationPanel]/function[detailsPanel]`
)

## Действия

1.* * Персист.* * Если в обработке ClaudeCodeStepResult контроллер НЕ передаёт
llmReasoning в ChatMessage — добавить `reasoning = result.llmReasoning` в месте
        создания ассистентского ChatMessage.Отдельно проверить путь WaitingForApprove :
сообщение с requestedViews тоже должно сохранить reasoning(
    иначе thinking первой
            половины хода потеряется при Approve
).2.* * Футер.* * В `buildSummaryHtml` : при `reasoning != null` добавить в summary -строку
маркер `💭` (по образцу существующих счётчиков).В `detailsPanel` reasoning уже
        должен выводиться — проверить читаемость длинного многоабзацного текста
        (переносы строк, отсутствие горизонтального скролла).
3.По умолчанию футер свёрнут — так и оставить, ничего не менять.

## PSI - заметки

Правки в ConversationPanel — точечные REPLACE_ELEMENT нужных функций . НЕ смешивать
с CREATE_ELEMENT на том же файле в одном батче.

## Чекпоинт(смоук)

`./gradlew clean :maxvibes-plugin:runIde` → Claude Code режим → кодовая задача →
в пузыре ответа развернуть футер → виден ПОЛНЫЙ thinking(не 90 - символьное превью).Ответ с requestedViews(
    AWAITING_APPROVE
) — thinking тоже на месте . Рестарт IDE →
reasoning пережил перезапуск(XML).Live Activity во время хода работает как раньше .
