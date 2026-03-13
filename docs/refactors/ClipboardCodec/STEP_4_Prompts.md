# Step 4: Consolidate system prompts

## Цель шага

        Убрать дублирование системных промптов . После шага промпты живут ровно в одном месте — `PromptService`.`ClipboardInteractionService` не содержит строк -промптов в коде.

## Предусловие

Шаги 1–3 завершены .

## Проблема: три копии одного промпта

| Место | Что содержит |
|-------|--------------|
| `PromptService.DEFAULT_CHAT_SYSTEM` | Kotlin - строка, дефолтный chat промпт |
| `maxvibes-plugin/src/main/resources/prompts/chat-system.md` | То же самое в файле ресурсов |
| `ClipboardInteractionService.buildChatInstruction()` | **Третья * * версия, строится в коде с добавлением planOnly -суффикса |
| `ClipboardInteractionService.buildPlanningInstruction()` | Аналогично для planning |

При изменении промпта надо менять в нескольких местах . Неочевидно какой из них «главный».

## Решение

### Единственный источник правды: `PromptService`

-`PromptService.getPrompts()` возвращает `PromptTemplates(chatSystem, planningSystem)`
-Если есть кастомный файл в `.maxvibes/prompts/` — берётся он
-Иначе берётся `DEFAULT_CHAT_SYSTEM` / `DEFAULT_PLANNING_SYSTEM` из `PromptService`
-`ClipboardInteractionService` просто вызывает `promptPort.getPrompts()` и использует результат

### Что удалить из `ClipboardInteractionService`

        Методы:
-`buildSystemInstruction()` — удалить
-`buildChatInstruction()` — удалить
-`buildPlanningInstruction()` — удалить

Вместо вызова этих методов в `generateAndCopyJson()` — брать промпт из `state.prompts`:

```kotlin
// Было:
val request = ClipboardRequest(
    ...
systemInstruction = buildSystemInstruction(state),
...
)

// Стало:
val systemInstruction = if (state.allGatheredFiles.isEmpty()) {
    state.prompts.planningSystem
} else {
    buildString {
        append(state.prompts.chatSystem)
        if (state.planOnly) {
            append(PLAN_ONLY_SUFFIX)
        }
    }
}
val request = ClipboardRequest(
    ...
systemInstruction = systemInstruction,
...
)
```

Gде `PLAN_ONLY_SUFFIX` — companion object константа в `ClipboardInteractionService`:

```kotlin
companion object {
    private val PLAN_ONLY_SUFFIX = """

## ⚠️ PLAN-ONLY MODE — DISCUSSION REQUIRED

DO NOT generate any code changes in the "modifications" array. 
Keep "modifications": [] empty.
Your goal is to DISCUSS the plan with the user before any code is written.

Instead of code, you must:
1. Briefly explain what you understand from the task
2. List which files you plan to touch and what changes you'll make in each
3. Mention any architectural decisions or trade-offs
4. Ask the user to confirm or suggest corrections

Always output the JSON with "modifications": [] and put your discussion in "message".""".trimIndent()
}
```

### Упростить `PromptService`

        Удалить `DEFAULT_PLANNING_SYSTEM` — он дублирует смысл `DEFAULT_CHAT_SYSTEM` и не используется правильно.Планировочный промпт уже зашит в `ClipboardInteractionService.buildPlanningInstruction()` как строка — переместить его в `PromptService.DEFAULT_PLANNING_SYSTEM`:

```kotlin
private val DEFAULT_PLANNING_SYSTEM = """
⚠️ CRITICAL: This is a MaxVibes clipboard protocol message. You MUST respond with ONLY a JSON object.
DO NOT use computer tools, bash, artifacts. Your ENTIRE response = one JSON object.

You are an expert software architect in a clipboard-based dialog through MaxVibes IDE plugin.

TASK: Analyze the task and project file tree, decide what files you need.

Respond with EXACTLY this JSON (nothing else):
{
    "message": "Your thoughts and explanation about what files you need and why",
    "requestedFiles": ["path/to/file.kt", ...],
    "reasoning": "Why you need these specific files"
}

Rules:
- "message" is REQUIRED
- "requestedFiles" — list files to read. Empty [] if you just want to discuss.
- DO NOT wrap JSON in markdown. Raw JSON only.
""".trimIndent()
```

### Удалить дубль из resources

        Файл `maxvibes-plugin/src/main/resources/prompts/chat-system.md` — * * удалить * * .

Пользователи могут создать кастомный промпт через `PromptService.openOrCreatePrompts()` — он создаст файл в `.maxvibes/prompts/`(
    проектная папка,
    не resources
).

> **Осторожно * *: проверить что `PromptService.loadPrompt()` не читает из resources — он читает из `File(project.basePath, PROMPTS_DIR)` . Если читает из resources — убедиться что fallback на `DEFAULT_CHAT_SYSTEM` работает без файла .

## Проверка

### Компиляция
```bash
    ./ gradlew : maxvibes -application:compileKotlin
    ./ gradlew : maxvibes -plugin:compileKotlin
```

### Smoke test
        1.Запустить плагин (`./gradlew runIde`)
2.Clipboard mode → отправить сообщение
3.Вставить из буфера в текстовый редактор
        4.Проверить что `systemInstruction` присутствует в JSON и содержит MaxVibes - промпт
5.Включить plan -only режим → проверить что `systemInstruction` содержит `PLAN-ONLY MODE` суффикс
6.Кастомный промпт : создать `.maxvibes/prompts/chat-system.md` с кастомным текстом → убедиться что он используется

### Unit тест : промпты

        Добавить в `JsonClipboardProtocolCodecTest` или создать `ClipboardInteractionServiceTest` :

-`planOnly=true` → `systemInstruction` содержит `PLAN-ONLY MODE`
-`planOnly=false` → суффикс отсутствует
        -Первое сообщение (пустой `allGatheredFiles`) → используется `planningSystem` промпт
-Последующие сообщения → используется `chatSystem` промпт

## Итог рефакторинга ClipboardCodec

После этого шага:

| Что | Где живёт |
|-----|---------- - |
| JSON - поля(константы) | `ClipboardRequestSchema` |
| Интерфейс кодека | `ClipboardProtocolCodec` |
| Логика encode / decode | `JsonClipboardProtocolCodec` |
| Тесты кодека | `JsonClipboardProtocolCodecTest` |
| Работа с буфером | `ClipboardAdapter`(тонкий) |
| Системные промпты | `PromptService` (один источник) |
| plan - only суффикс | `ClipboardInteractionService.PLAN_ONLY_SUFFIX` |

## Commit message

```
refactor: consolidate system prompts into PromptService, remove duplicates
```
