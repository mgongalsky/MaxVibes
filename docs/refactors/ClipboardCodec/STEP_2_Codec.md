# Step 2: JsonClipboardProtocolCodec implementation +tests

## Цель шага

        Создать `JsonClipboardProtocolCodec` — чистую реализацию кодека без зависимостей на IDE . Написать unit - тесты.Существующий `ClipboardAdapter` пока не трогаем — они работают параллельно.

## Предусловие

Шаг 1 завершён : существуют `ClipboardRequestSchema` и `ClipboardProtocolCodec`.

## Контекст: что переносим

        Логика берётся из `ClipboardAdapter` (плагин):

-* * `serializeRequest()` * * — строит `JsonObject` через `buildJsonObject { put(...) }`, все строки -ключи будут заменены на константы из `ClipboardRequestSchema`
-* * `parseUnifiedResponse()` * * — парсит `JsonObject` в `ClipboardResponse`
        -* * `parseModification()` * * — парсит один элемент `modifications[]`
        -* * `findEmbeddedJson()` * * — ищет JSON -блок в произвольном тексте
        -* * `extractSurroundingText()` * * — берёт текст вокруг JSON -блока

## Что создаём

### `JsonClipboardProtocolCodec.kt`

**Путь * *: `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/clipboard/JsonClipboardProtocolCodec.kt`

> Живёт в plugin - модуле, т.к.использует `kotlinx.serialization.json` (зависимость уже есть там).
> Не содержит ни одного импорта из IntelliJ Platform SDK — только stdlib и kotlinx . serialization .

```kotlin
package com.maxvibes.plugin.clipboard

import com . maxvibes . application . port . output . ClipboardProtocolCodec
        import com . maxvibes . application . port . output . ClipboardRequestSchema
        import com . maxvibes . domain . model . interaction . *
        import kotlinx . serialization . json . *

/**
 * Реализация [ClipboardProtocolCodec] на базе kotlinx.serialization.
 *
 * Чистая логика: JSON ↔ доменные модели.
 * Нет зависимостей на IDE, AWT или IntelliJ Platform.
 * Тестируется unit-тестами напрямую.
 */
class JsonClipboardProtocolCodec : ClipboardProtocolCodec {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ── Encode ────────────────────────────────────────────────────────

    override fun encode(request: ClipboardRequest): String {
        val obj = buildJsonObject {
            put(ClipboardRequestSchema.META_PROTOCOL, ClipboardRequestSchema.PROTOCOL_MARKER)
            put(ClipboardRequestSchema.META_RESPONSE_FORMAT, ClipboardRequestSchema.RESPONSE_FORMAT_HINT)

            if (request.systemInstruction.isNotBlank()) {
                put(ClipboardRequestSchema.FIELD_SYSTEM_INSTRUCTION, request.systemInstruction)
            }
            put(ClipboardRequestSchema.FIELD_TASK, request.task)
            put(ClipboardRequestSchema.FIELD_PROJECT_NAME, request.projectName)

            if (request.planOnly) {
                put(ClipboardRequestSchema.FIELD_PLAN_ONLY, true)
            }
            if (request.fileTree.isNotBlank()) {
                put(ClipboardRequestSchema.FIELD_FILE_TREE, request.fileTree)
            }
            if (request.freshFiles.isNotEmpty()) {
                putJsonObject(ClipboardRequestSchema.FIELD_FILES) {
                    request.freshFiles.forEach { (path, content) -> put(path, content) }
                }
            }
            if (request.previouslyGatheredPaths.isNotEmpty()) {
                putJsonArray(ClipboardRequestSchema.FIELD_PREVIOUSLY_GATHERED) {
                    request.previouslyGatheredPaths.forEach { add(it) }
                }
            }
            if (request.chatHistory.isNotEmpty()) {
                putJsonArray(ClipboardRequestSchema.FIELD_CHAT_HISTORY) {
                    request.chatHistory.forEach { entry ->
                        addJsonObject {
                            put(ClipboardRequestSchema.HISTORY_ROLE, entry.role)
                            put(ClipboardRequestSchema.HISTORY_CONTENT, entry.content)
                        }
                    }
                }
            }
            request.attachedContext?.takeIf { it.isNotBlank() }?.let {
                put(ClipboardRequestSchema.FIELD_ERROR_TRACE, it)
            }
            request.ideErrors?.takeIf { it.isNotBlank() }?.let {
                put(ClipboardRequestSchema.FIELD_IDE_ERRORS, it)
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    // ── Decode ────────────────────────────────────────────────────────

    override fun decode(rawText: String): ClipboardResponse? {
        val text = rawText.trim()
        if (text.isBlank()) return null

        val jsonBlockMatch = Regex("`{3}json\\s*\\n([\\s\\S]*?)\\n\\s*`{3}").find(text)
        val jsonBlock = jsonBlockMatch?.groupValues?.get(1)?.trim()

        val codeBlockMatch = Regex("`{3}\\s*\\n([\\s\\S]*?)\\n\\s*`{3}")
            .findAll(text)
            .firstOrNull { it.groupValues[1].trim().startsWith("{") }
        val codeBlock = codeBlockMatch?.groupValues?.get(1)?.trim()

        val rawJson = if (text.startsWith("{")) text else null
        val embedded = findEmbeddedJson(text)
        val jsonText = jsonBlock ?: codeBlock ?: rawJson ?: embedded

        if (jsonText == null) {
            return if (text.isNotBlank() && !text.contains("{")) {
                ClipboardResponse(message = text)
            } else null
        }

        val surroundingText = extractSurroundingText(text, jsonBlockMatch ?: codeBlockMatch)

        return try {
            val response = parseUnifiedResponse(jsonText)
            if (response.message.isBlank() && surroundingText.isNotBlank()) {
                response.copy(message = surroundingText)
            } else response
        } catch (e: Exception) {
            try {
                embedded?.let { parseUnifiedResponse(it) }
            } catch (_: Exception) {
                if (surroundingText.isNotBlank()) ClipboardResponse(message = surroundingText) else null
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────

    private fun parseUnifiedResponse(jsonText: String): ClipboardResponse {
        val obj = lenientJson.parseToJsonElement(jsonText).jsonObject
        return ClipboardResponse(
            message = obj[ClipboardRequestSchema.RESP_MESSAGE]?.jsonPrimitive?.contentOrNull ?: "",
            reasoning = obj[ClipboardRequestSchema.RESP_REASONING]?.jsonPrimitive?.contentOrNull,
            requestedFiles = obj[ClipboardRequestSchema.RESP_REQUESTED_FILES]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            modifications = obj[ClipboardRequestSchema.RESP_MODIFICATIONS]?.jsonArray
                ?.mapNotNull { parseModification(it.jsonObject) } ?: emptyList(),
            commitMessage = obj[ClipboardRequestSchema.RESP_COMMIT_MESSAGE]?.jsonPrimitive?.contentOrNull
        )
    }

    private fun parseModification(obj: JsonObject): ClipboardModification? {
        val type = obj[ClipboardRequestSchema.MOD_TYPE]?.jsonPrimitive?.contentOrNull ?: return null
        val path = obj[ClipboardRequestSchema.MOD_PATH]?.jsonPrimitive?.contentOrNull ?: return null
        return ClipboardModification(
            type = type,
            path = path,
            content = obj[ClipboardRequestSchema.MOD_CONTENT]?.jsonPrimitive?.contentOrNull ?: "",
            elementKind = obj[ClipboardRequestSchema.MOD_ELEMENT_KIND]?.jsonPrimitive?.contentOrNull
                ?: ClipboardRequestSchema.DEFAULT_ELEMENT_KIND,
            position = obj[ClipboardRequestSchema.MOD_POSITION]?.jsonPrimitive?.contentOrNull
                ?: ClipboardRequestSchema.DEFAULT_POSITION,
            importPath = obj[ClipboardRequestSchema.MOD_IMPORT_PATH]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }

    internal fun findEmbeddedJson(text: String): String? {
        val indicators = listOf(
            "\"${ClipboardRequestSchema.RESP_REQUESTED_FILES}\"",
            "\"${ClipboardRequestSchema.RESP_MODIFICATIONS}\"",
            "\"${ClipboardRequestSchema.RESP_MESSAGE}\""
        )
        val startIndex = indicators
            .mapNotNull { indicator ->
                val idx = text.indexOf(indicator)
                if (idx >= 0) {
                    var braceIdx = idx
                    while (braceIdx > 0 && text[braceIdx] != '{') braceIdx--
                    if (text[braceIdx] == '{') braceIdx else null
                } else null
            }
            .minOrNull() ?: return null

        var depth = 0;
        var inString = false;
        var escape = false
        for (i in startIndex until text.length) {
            val c = text[i]
            if (escape) {
                escape = false; continue
            }
            if (c == '\\') {
                escape = true; continue
            }
            if (c == '"') {
                inString = !inString; continue
            }
            if (inString) continue
            if (c == '{') depth++
            if (c == '}') {
                depth--; if (depth == 0) return text.substring(startIndex, i + 1)
            }
        }
        return null
    }

    private fun extractSurroundingText(fullText: String, jsonMatch: MatchResult?): String {
        if (jsonMatch == null) return ""
        val before = fullText.substring(0, jsonMatch.range.first).trim()
        val after = fullText.substring(jsonMatch.range.last + 1).trim()
        return buildString {
            if (before.isNotBlank()) append(before)
            if (before.isNotBlank() && after.isNotBlank()) append("\n\n")
            if (after.isNotBlank()) append(after)
        }.replace(Regex("^`{3}\\w*\\s*"), "").replace(Regex("\\s*`{3}$"), "").trim()
    }
}
```

## Тесты

**Путь * *: `src/test/kotlin/com/maxvibes/plugin/clipboard/JsonClipboardProtocolCodecTest.kt`

> Используй модуль где уже лежит `ChatMessageMapperTest.kt` (корневой `src/test/`).Тест - сценарии:

### encode()
-`encode` → содержит `_protocol` маркер
-`encode` → содержит `_responseFormat`
        -`encode` → `planOnly=true` добавляет поле, `planOnly=false` — не добавляет
        -`encode` → пустой `fileTree` не добавляет поле
-`encode` → `freshFiles` сериализуются в `files`
        -`encode` → `chatHistory` сериализуется корректно(role / content)
-`encode` → `attachedContext` → `errorTrace`
-`encode` → `ideErrors` → `ideErrors`

### decode()
-`decode` → raw JSON `{"message": "hello"}` → message = hello
-`decode` → JSON в ` ```json ` блоке → парсится корректно
-`decode` → JSON без маркера ```json ` → парсится через codeBlock fallback
        -`decode` → plain text без JSON → ClipboardResponse (message = text)
-`decode` → пустая строка → null
-`decode` → JSON встроен в текст до и после → парсится, surrounding text → message
        -`decode` → `requestedFiles` → список файлов
        -`decode` → `modifications` со всеми полями → ClipboardModification
        -`decode` → `modifications` без `elementKind` → default "FILE"
-`decode` → `commitMessage` → поле заполнено
        -`decode` → невалидный JSON → null
-`decode` → `reasoning` → заполнен

### findEmbeddedJson()
-текст с JSON внутри → извлекается корректный JSON
        -текст без JSON → null

## Проверка

```bash
    ./ gradlew : maxvibes -plugin:compileKotlin
    ./ gradlew test  # или через IntelliJ IDEA runner
```

Существующий `ClipboardAdapter` * * не тронут * * — плагин работает идентично .

## Commit message

```
feat: implement JsonClipboardProtocolCodec with unit tests
```

## Следующий шаг

→ [STEP_3_Adapter.md](STEP_3_Adapter.md) — переключить `ClipboardAdapter` на кодек
