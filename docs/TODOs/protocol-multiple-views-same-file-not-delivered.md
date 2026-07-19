# Протокол: несколько view одного файла не доставляются вместе

## Симптом

Запрос двух ELEMENT - вью одного и того же файла в одном `requestedViews`
(например, `buildSummaryHtml` и `detailsPanel` из `ConversationPanel.kt`) —
в `files` ответа доставляется только один; второй теряется * * молча * *, без
error - плейсхолдера.Обнаружено: 2026 - 07, Claude Code режим, поток approve (ThinkingBubble STEP 3).

## Причина(структурная)

`files` в JSON - запросе — `Map<путь файла, содержимое>`: два вью одного пути
        физически не могут сосуществовать . В `ClaudeCodeInteractionService.approve()`
`partialFilesMap` строится через `associate { req.filePath to view.content }` —
дубликат ключа схлопывается.

## Workaround

-Не более одного view на файл за ход .
-Нужны несколько элементов одного файла — запрашивать SIGNATURES или FULL,
либо разносить ELEMENT - запросы по ходам.
-Стоит добавить это правило в системные промпты
(chat - system.md / claude - code - system.md), чтобы LLM не наступала на грабли .

## Возможное лечение

        Ключевать доставку по `path#elementPath` или конкатенировать несколько вью
        одного файла с разделителями -комментариями.Затрагивает
`InteractionRequestBuilder` и схему запроса — отдельная задача.
