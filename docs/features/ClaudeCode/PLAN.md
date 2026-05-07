# Claude Code Mode — Feature Plan

## Цель

Добавить * * четвёртый режим взаимодействия * * в MaxVibes — `CLAUDE_CODE` . В этом режиме плагин общается с локально запущенным процессом `claude code`(
    CLI
) через JSON - протокол, идентичный тому, что используется в `CLIPBOARD` -режиме.Все правки и сбор контекста делает плагин — Claude Code только генерирует JSON - ответы.

## Ключевые решения

| # | Решение | Обоснование |
|---|-------- - |------------ - |
| 1 | Транспорт — **CLI streaming * * (stream - json) | Один долгоживущий процесс, низкий overhead на запрос |
| 2 | **Вариант A * * для истории : первый запрос — полный контекст, последующие — minimal - mode | Claude Code сам держит сессию; не дублируем токены |
| 3 | **Fallback на full restart * * если процесс упал и `--resume` не сработал | Нельзя терять диалог; storage `claudeCodeSessionId` в `ChatSession` |
| 4 | Без auto -loop в MVP — пошагово через * * Approve - кнопку * * | Сначала отлаживаем round - trip, потом автоматизируем |
| 5 | Auto - apply модификаций (если не plan - only) | Поведение идентично clipboard'у |
| 6 | Новый статус `ClipboardSessionStatus.AWAITING_APPROVE` | Семантически отличается от `AWAITING_PASTE` |
| 7 | **Не переименовываем * * `Clipboard*` → `Interaction*` сейчас | Refactor отложен до следующей итерации |
| 8 | Системный промпт — отдельный файл `claude-code-system.md` | Нужны жёсткие инструкции « не используй встроенные tools» |

## Что переиспользуется как есть

        Из существующего clipboard - стека * * без изменений * *(только добавляем нового потребителя):

-`ClipboardRequest` / `ClipboardResponse` / `ClipboardModification`(domain) — protocol - agnostic
-`ClipboardRequestSchema`(constants)
-`ClipboardProtocolCodec` + `JsonClipboardProtocolCodec`(encode / decode)
-`ClipboardRequestBuilder`(build request — pure)
-`ClipboardResponseValidator`(parse error feedback)
-`CodeRepository` + PSI - стек(применение модификаций)
-`ChatSessionRepository` + persistence(история)

## Что строится новое

1.* * Domain:** `InteractionMode.CLAUDE_CODE`, `ClipboardSessionStatus.AWAITING_APPROVE`, поля `claudeCodeSessionId` и `claudeCodeNeedsFullContext` в `ChatSession` .
2.* * Application port : * * `ClaudeCodePort` +`ClaudeCodeError`.3.* * Plugin adapter : * * `ClaudeCodeProcessAdapter` (lifecycle процесса, stream - JSON I / O).
4.* * Application service : * * `ClaudeCodeInteractionService` (оркестрация, аналог `ClipboardInteractionService`).
5.* * System prompt : * * `claude-code-system.md` +расширение `PromptPort` .
6.* * Session manager расширение:** транзишены под `AWAITING_APPROVE`.7.* * DI:** wiring в `MaxVibesService`.8.* * UI:** опция в `InteractionModeManager`, **Approve * * - кнопка в `ChatPanel`, статус - индикатор.9.* * Settings:** путь к binary, доп.аргументы, таймаут.

## Out of scope(для следующих итераций)

-* * Auto - loop * *: автоматическое продолжение цепочки без Approve.Hard cap 15 итераций, Stop - кнопка.
-* * Переименования * * `Clipboard*` → `Interaction*` после того, как новый режим заработает .
-* * Удалённый headless * * (claude code на сервере) — пока только локальный процесс .
-* * Параллельные сессии Claude Code * * — пока одна на проект.

## Архитектурная схема

```
UI(ChatPanel)
│
▼
InteractionModeManager — выбор режима (API / CLIPBOARD / CHEAP_API / CLAUDE_CODE)
│
▼
ChatMessageController — роутинг по режиму
│
├─► (API) LangChainLLMService
├─► (CHEAP_API) LangChainLLMService (другой конфиг)
├─► (CLIPBOARD) ClipboardInteractionService → ClipboardPort → системный буфер
└─► (CLAUDE_CODE) ClaudeCodeInteractionService → ClaudeCodePort → ClaudeCodeProcessAdapter → процесс claude
│
├─ ClipboardRequestBuilder(общий)
├─ JsonClipboardProtocolCodec(общий)
├─ ClipboardResponseValidator(общий)
└─ CodeRepository(общий)
```

## Stream - JSON протокол с claude code

**Команда(требует уточнения на Step 3):**
```
claude-- print --input - format stream -json-- output -format stream -json-- append -system - prompt "<MaxVibes system>"
```
или(возобновление):
```
claude-- resume < session -id > --input - format stream -json-- output -format stream -json
```

**Цикл обмена : * *
1.Плагин запускает процесс при первом сообщении
        2.Пишет в stdin строку JSON(наш `ClipboardRequest` через `JsonClipboardProtocolCodec.encode`)
3.Читает stdout построчно — извлекает `session_id` из первого system - events, accумулирует assistant content
4.Финальный assistant text парсит через `JsonClipboardProtocolCodec.decode` → `ClipboardResponse`
        5.На следующий send: пишет следующую строку JSON в тот же процесс (минимальный payload — без system, без history)
6.Если процесс умер — `--resume <session-id>`; если resume failed — full restart с полной историей

## Шаги(порядок)

| Шаг | Файл | Зависимости | Время |
|-----|------|-------------|------ - |
| 1 | `STEP_1_Domain.md` | — | малый |
| 2 | `STEP_2_Port.md` | 1 | малый |
| 3 | `STEP_3_Adapter.md` | 2 | **большой * * |
| 4 | `STEP_4_SystemPrompt.md` | — | малый |
| 5 | `STEP_5_Service.md` | 1, 2, 4 | средний |
| 6 | `STEP_6_SessionManager.md` | 1 | малый |
| 7 | `STEP_7_DI.md` | 3, 5, 6 | малый |
| 8 | `STEP_8_UI.md` | 5, 7 | средний |
| 9 | `STEP_9_Tests.md` | 5, 6 | средний |
| 10 | `STEP_10_SmokeTest.md` | все | malый |

Шаги 1, 2, 4, 6 независимы и могут идти в любом порядке . Шаг 3 зависит от 2.Шаг 5 — от 1, 2, 4.Шаг 7 собирает 3 + 5 + 6.Шаги 8–10 финальные .

## Definition of Done всей фичи

-Юзер в UI может переключиться на режим Claude Code
-Первое сообщение запускает процесс claude code и получает ответ
-В ответе с `requestedViews` — кнопка Approve собирает файлы и шлёт следующий запрос
-В ответе с `modifications` — авто -применение(если не plan - only)
-IDE - restart восстанавливает состояние через persisted `claudeCodeSessionId`
        -Все unit -тесты зелёные на `./gradlew :maxvibes-application:test`
        -Smoke test из `STEP_10_SmokeTest.md` пройден
