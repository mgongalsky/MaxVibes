# STEP 7 — Обновление системного промпта

## Цель

Научить LLM использовать `requestedViews` с нужной гранулярностью вместо
        того чтобы всегда запрашивать полные файлы .

## Модуль

`maxvibes-plugin/src/main/resources/prompts/chat-system.md`

## Предварительные условия

        -STEP 5 выполнен : вся реализация готова и работает

## Что добавить в промпт

        Найти секцию где описываются `requestedFiles` и дополнить / заменить её на:

```markdown
## Requesting file context

To minimize token usage, prefer requesting only the level of detail you actually need .
Use the `requestedViews` field instead of `requestedFiles` whenever possible:

```json
"requestedViews": [
    { "path": "src/main/kotlin/.../MyService.kt", "granularity": "SIGNATURES" },
    { "path": "src/main/kotlin/.../ChatSession.kt", "granularity": "ELEMENT", "elementPath": "class[ChatSession]/property[tokenUsage]" },
    { "path": "src/main/kotlin/.../SmallFile.kt", "granularity": "FULL" }
]
```

### Granularity levels

| Value | Use when |
|-------|----------|
| `FULL` | You need to understand the entire file, or the file is small ( < 100 lines) |
| `SIGNATURES` | You need to understand what functions / classes exist without implementation details |
| `OUTLINE` | You need a class's structure: fields, methods, inheritance — without bodies |
| `ELEMENT` | You need a specific function or property by path |

### Element path format for ELEMENT granularity
```
class[ClassName]/function[methodName]
class[ClassName]/property[fieldName]
```

### Legacy format

        The old `requestedFiles` format is still supported and treated as `granularity: FULL`.
Prefer `requestedViews` for new requests .
```

## Рекомендации по тюнингу промпта

        После первых реальных диалогов :
-Проверить: LLM слишком консервативен(всегда запрашивает FULL)?
→ Усилить инструкцию : "**Always prefer SIGNATURES over FULL** unless you need the implementation"
-LLM слишком агрессивен(запрашивает ELEMENT когда нужен контекст)?
→ Добавить: "When in doubt between SIGNATURES and FULL, use SIGNATURES first"
-LLM путает синтаксис elementPath ?
→ Добавить примеры из реального кода проекта

## После шага

### Smoke test

        1.Запустить плагин
        2.Начать новый диалог в Clipboard mode
        3.Задать вопрос вида: «Покажи структуру ClipboardSessionManager»
4.Проверить в JSON - ответе LLM : использует ли он `requestedViews` с `SIGNATURES`?
5.Убедиться что ответное сообщение плагина содержит только сигнатуры, без тел

### Метрика успеха

        До фичи : запрос файла на 400 строк = ~2000 токенов
        После с SIGNATURES: те же 400 строк с сигнатурами = ~300 - 400 токенов
        Экономия: ~5 - 7 x на каждом файле запрошенном для ориентации
