# STEP 1 — Протокол и адаптер: полный thinking за ход

## Цель

Извлекать полный текст thinking -блоков из stream - JSON и доносить его до
`ClaudeCodeSendResult.thinkingText`.После шага поведение UI не меняется —
поле пока никто не читает(это STEP 2).

## Запросить файлы (FULL)

-`maxvibes-application/src/main/kotlin/com/maxvibes/application/port/output/ClaudeCodeSendResult.kt`
-`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/claudecode/StreamJsonProtocol.kt`
-`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/claudecode/ClaudeCodeProcessAdapter.kt`

## 1.ClaudeCodeSendResult — новое поле

        Третье поле с default 'ом — существующие call-sites и тесты продолжают компилироваться.
Файл маленький, KDoc над классом — надёжнее заменить целиком через * * REPLACE_FILE * *.
        Целевое содержимое (KDoc класса сохранить, дополнив):

```kotlin
data class ClaudeCodeSendResult(
    val response: InteractionResponse,
    val observedSessionId: String?,
    /**
     * Full extended-thinking text accumulated over the turn, or null when the
     * model emitted no thinking blocks. Multiple blocks (one per assistant event
     * between tool rounds) are joined with a blank line. Presentation-only:
     * never sent back to the model — the CLI strips past thinking from context anyway.
     */
    val thinkingText: String? = null
)
```

## 2.StreamJsonProtocol.extractThinkingFull

**CREATE_ELEMENT * *(elementKind: FUNCTION, position AFTER), path указывает на СИБЛИНГА :
`file:...StreamJsonProtocol.kt/object[StreamJsonProtocol]/function[extractThinkingPreview]`

```kotlin
/**
 * Returns the FULL text of all extended-thinking blocks in an `assistant` event,
 * or null when the line is not an assistant event or carries no thinking.
 *
 * Differences from [extractThinkingPreview]: iterates ALL content blocks (a single
 * event may carry several), preserves whitespace/newlines, applies no truncation.
 * Used to persist the complete chain of thought into the chat message (ThinkingBubble);
 * the preview stays as-is for the Live Activity contract.
 */
fun extractThinkingFull(line: String): String? {
    val obj = parseLine(line) ?: return null
    if (obj["type"]?.jsonPrimitive?.contentOrNull != "assistant") return null

    val message = obj["message"]?.jsonObject ?: return null
    val content = message["content"]?.jsonArray ?: return null

    val parts = content.mapNotNull { element ->
        val block = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        if (block["type"]?.jsonPrimitive?.contentOrNull != "thinking") return@mapNotNull null
        block["thinking"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }
    return if (parts.isEmpty()) null else parts.joinToString("\n\n")
}
```

`extractAssistantText` фильтрует строго `type=="text"`, так что thinking в финальный
ответ не задваивается — его менять НЕ нужно . `extractThinkingPreview` тоже не трогаем.

## 3.Адаптер — аккумуляция за ход

В `send()` три точечных изменения; применять одним * * REPLACE_ELEMENT * * по пути
`class[ClaudeCodeProcessAdapter]/function[send]` — контент обязан быть ПОЛНЫМ элементом
(модификаторы `override suspend`, сигнатура, всё тело).

3.1.Рядом с `val accumulated = StringBuilder()`:

```kotlin
val thinkingAccumulated = StringBuilder()
```

3.2.В цикле чтения stdout, сразу ПОСЛЕ блока `extractThinkingPreview` (превью остаётся
        для Live Activity, полный текст копим отдельно):

```kotlin
StreamJsonProtocol.extractThinkingFull(line)?.let { full ->
    if (thinkingAccumulated.isNotEmpty()) thinkingAccumulated.append("\n\n")
    thinkingAccumulated.append(full)
}
```

3.3.Успешный возврат (последние строки send):

```kotlin
val thinkingText = thinkingAccumulated.toString().takeIf { it.isNotBlank() }
```

и в лог - мапы `"send: done"` (MaxVibesLogger) и `"turn done"`(sessionLog) добавить
        `"thinkingLen" to (thinkingText?.length ?: 0)`, затем:

```kotlin
return Result.Success(ClaudeCodeSendResult(response, observedSessionId, thinkingText))
```

## PSI - заметки

-REPLACE_FILE для ClaudeCodeSendResult.kt и REPLACE_ELEMENT / CREATE_ELEMENT для двух
РАЗНЫХ файлов в одном батче — безопасно(правило про смешанные батчи касается одного файла).
-Не объявлять thinkingAccumulated отдельным CREATE_ELEMENT — это локальная переменная
внутри функции, только через полный REPLACE_ELEMENT send().

## Чекпоинт

Проект компилируется : `./gradlew :maxvibes-application:compileKotlin` +сборка плагина .
Поведенчески ничего не изменилось .

## Не делать

        -Не менять extractThinkingPreview, extractAssistantText, Live Activity .
-Не добавлять thinking в encodeUserEvent / исходящий payload .
