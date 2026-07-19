# Step 4 — System prompt : claude -code - system.md + PromptPort расширение

## Цель

Добавить отдельный системный промпт для Claude Code режима . Промпт должен жёстко запретить использование встроенных tools (Read / Write / Edit / Bash / etc.), потому что у нас весь tool -use делает плагин — Claude Code только генерирует JSON.

## Затрагиваемые файлы

| Файл | Действие |
|------|----------|
| `maxvibes-plugin/src/main/resources/prompts/claude-code-system.md` | CREATE |
| `maxvibes-application/src/main/kotlin/com/maxvibes/application/port/output/PromptPort.kt` | MODIFY(добавить метод) |
| `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/service/PromptService.kt` | MODIFY(реализация нового метода) |
| `.maxvibes/prompts/chat-system.md`(или эквивалент) | референсный — НЕ менять |

## Контекст

Существующий промпт `chat-system.md` написан под clipboard -режим, где LLM работает в обычном чате (claude.ai / chatgpt.com).В Claude Code(
    CLI
) поведение по умолчанию — использовать tools(
    Read для чтения файлов,
    Edit для правок
).* * Это нам не нужно * * — все правки делаем мы.Промпт должен :
1.Объяснить контекст : «ты под управлением плагина в headless -режиме»
2.Запретить tool -use на уровне инструкции (плюс CLI -флаг `--allowedTools ""` или аналог в Step 3)
3.Обязать отвечать строго JSON -ом
4.Описать тот же JSON -формат, что в chat - system.md
5.Описать `requestedViews`, `modifications`, plan - only, specificPrompt — всё как в chat -system

## Изменения

### 4.1 Создать `claude-code-system.md`

Расположение — `maxvibes-plugin/src/main/resources/prompts/claude-code-system.md`.Структура(на русском или английском — на твой выбор; если используешь существующий `chat-system.md` — в той же стилистике):

```markdown
You are MaxVibes, an AI coding assistant integrated into IntelliJ IDEA via headless Claude Code .

## Operating environment

        You are running in headless mode under the control of the MaxVibes plugin.The plugin orchestrates all interactions with the codebase . * * You do not have working access to file -system or shell tools in this mode . * * Specifically :

-DO NOT call Read, Write, Edit, MultiEdit, Bash, Glob, Grep, WebFetch, WebSearch, or any other built -in tool .
-The plugin will reject any tool use and consider it a protocol error.
-All file content will be supplied to you in the `files` field of the request.
-All code modifications you want to make MUST be expressed as entries in the `modifications` array of your JSON response — the plugin will apply them via PSI .

## Response format

        Respond with ONLY a raw JSON object.No prose outside the JSON, no markdown fences, no commentary .

The JSON must have this shape :

```json
{
    "message": "Brief explanation of what you did or what you need",
    "commitMessage": "feat: optional conventional-commit message",
    "requestedViews": [
    { "path": "src/.../Foo.kt", "granularity": "FULL" }
    ],
    "modifications": [
    {
        "type": "REPLACE_ELEMENT",
        "path": "file:src/.../User.kt/class[User]/function[validate]",
        "content": "fun validate(): Boolean = name.isNotBlank()",
        "elementKind": "FUNCTION"
    }
    ]
}
```

[... тот же справочник по granularity / modification types / element path / known PSI limitations, что в chat - system.md ...]

## Plan - only mode

        If `planOnly: true` is in the request — respond with empty `modifications` and put your discussion in `message`.Do NOT generate code in plan -only mode .

## Specific prompt

        If a `specificPrompt` field is present — treat it as a binding constraint for this session . Mention at the start of `message` that you are operating under it.
```

**Совет:** скопировать содержимое существующего `chat-system.md` и добавить блок `## Operating environment` в начале +усилить запрет на tool -use.Не переписывать с нуля — экономия времени и согласованность форматов JSON.

### 4.2 Расширить `PromptPort.kt`

Посмотреть текущий интерфейс.Вероятная сигнатура — что -то вроде :

```kotlin
interface PromptPort {
    fun planningSystem(): String
    fun chatSystem(): String
    // ...
}
```

Добавить:

```kotlin
/**
 * System prompt for Claude Code mode.
 *
 * Distinct from [chatSystem] because Claude Code runs in CLI/headless mode
 * with built-in tools enabled by default — the prompt must explicitly forbid them.
 */
fun claudeCodeSystem(): String
```

### 4.3 Реализовать в `PromptService.kt`

        Прочитать файл `FULL`.Добавить новый метод по образцу существующих . Вероятная реализация — load resource by path :

```kotlin
override fun claudeCodeSystem(): String =
    loadResource("/prompts/claude-code-system.md")
        ?: error("Missing prompts/claude-code-system.md resource")
```

Если в проекте есть `PromptTemplates` data class в `application.port.output` — пока * * не * * добавлять туда `claudeCodeSystem` . `claudeCodeSystem` нужен только в Claude Code сервисе, и `PromptTemplates` используется в `ClipboardSessionState` — добавление поля заденет всех потребителей.Лучше: в Step 5 сервис будет вызывать `promptPort.claudeCodeSystem()` напрямую для составления системного промпта первого запроса .

## Что НЕ делать

-Не менять `chat-system.md` — он используется в clipboard -режиме и должен остаться нетронутым.
-Не добавлять `claudeCodeSystem` в `PromptTemplates` data class.
-Не менять `ClipboardRequestBuilder` для подмены системного промпта — оркестрация системного промпта будет в Step 5.

## Тесты

-В `PromptServiceTest` (если есть) или новом — проверить что `claudeCodeSystem()` возвращает непустую строку и содержит ключевую инструкцию (напр., строку `"DO NOT call Read"`).
-Тест ресурса : ресурс упакован в jar, доступен через classpath.

## Acceptance criteria

        -[] `./gradlew :maxvibes-plugin:build` зелёный
-[] Файл `claude-code-system.md` упакован в jar (проверить `./gradlew :maxvibes-plugin:processResources` → артефакт)
-[] `PromptService.claudeCodeSystem()` возвращает содержимое файла
-[] Никакие тесты не упали
