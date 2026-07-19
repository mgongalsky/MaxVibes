# ThinkingBubble — полный ризонинг Claude Code в пузыре ответа

## Цель

Сохранять полный текст thinking -блоков из stream - JSON Claude Code и показывать его
        в сворачиваемом футере ассистентского пузыря(💭). Сейчас полный текст живёт только
в per -dialog транскрипте (`.maxvibes/logs/claude-code/<id>.log`); в UI попадает лишь
~90 - символьное превью Live Activity, которое исчезает по завершении хода.Thinking — presentation - only: модель НЕ видит свои прошлые thinking -блоки на следующих
ходах(и API, и CLI вырезают их из контекста).Мы ничего не отправляем обратно —
только сохраняем и показываем .

## Ключевое открытие (сверено с кодом на момент планирования)

Пайплайн reasoning уже существует end - to - end и используется clipboard -режимом:

| Слой | Что есть | Статус |
|------|----------|--------|
| domain | `ChatMessage.reasoning: String?` | ✅ менять не надо |
| persistence | `XmlChatMessage.reasoning` `@Tag("reasoning")`, null опускается xmlb | ✅ |
| renderer | `ConversationRenderer` → `DisplayMessage.reasoning` | ✅ |
| UI | `ConversationPanel.addAssistantBubble(reasoning=...)` → `collapsibleFooter` | ✅ |
| step result | `ClaudeCodeStepResult.{WaitingForApprove,Completed}.llmReasoning` | ✅ |

**Отсутствует только источник * *: полный thinking из CLI не извлекается
        (`extractThinkingPreview` смотрит только ПЕРВЫЙ content - блок и режет до ~90 символов)
и не доносится до `ClaudeCodeSendResult`.

## Что делаем

        1.`StreamJsonProtocol.extractThinkingFull` — полный текст ВСЕХ thinking -блоков события .
2.`ClaudeCodeProcessAdapter.send` — аккумулирует thinking за ход (блоков может быть
        несколько между tool -раундами, склейка через пустую строку) →
`ClaudeCodeSendResult.thinkingText`(
    новое поле,
    default = null
).3.`ClaudeCodeInteractionService` — прокидывает `thinkingText` в `processResponse`,
мёржит с JSON - полем `response.reasoning` → `llmReasoning` (оба исхода).4.UI - верификация: `llmReasoning` → `ChatMessage.reasoning` при персисте
(`ChatMessageController` — единственное непроверенное звено), 💭 в футере .
5.Тесты.

## Что НЕ делаем

-Не трогаем domain и XML - персист — `ChatMessage.reasoning` уже есть.
-Не трогаем `extractThinkingPreview` и Live Activity — это отдельный слой (by design).
-Не отправляем thinking обратно в модель — оно презентационное.
-Не трогаем clipboard - режим и `ClipboardInteractionService`.

## Порядок выполнения

| Шаг | Файл | Слои |
|-----|------|------|
| 1 | STEP_1_Protocol.md | plugin(protocol + adapter), application(port DTO) |
| 2 | STEP_2_Service.md | application(service) |
| 3 | STEP_3_UIWiring.md | plugin(controller + panel) — верификация / доводка |
| 4 | STEP_4_Tests.md | plugin + application тесты |

Каждый шаг оставляет проект компилируемым и запускаемым.
