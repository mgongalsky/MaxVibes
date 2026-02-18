package com.maxvibes.plugin.clipboard

import com.maxvibes.application.port.output.ClipboardPort
import com.maxvibes.domain.model.interaction.*
import kotlinx.serialization.json.*
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Реализация ClipboardPort для IntelliJ plugin.
 *
 * Сериализует запросы в JSON и копирует в системный буфер.
 * Парсит ответы из разных форматов (raw JSON, ```json```, mixed text+JSON).
 *
 * Единый формат ответа:
 * {
 *   "message": "text for user",         // recommended
 *   "requestedFiles": ["path/file.kt"], // optional
 *   "reasoning": "why these files",     // optional
 *   "modifications": [...]              // optional
 * }
 */
class ClipboardAdapter : ClipboardPort {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun copyRequestToClipboard(request: ClipboardRequest): Boolean {
        return try {
            val jsonText = serializeRequest(request)
            val selection = StringSelection(jsonText)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
            true
        } catch (e: Exception) {
            println("[MaxVibes Clipboard] Failed to copy to clipboard: ${e.message}")
            false
        }
    }

    override fun parseResponse(rawText: String): ClipboardResponse? {
        val text = rawText.trim()

        // Attempt 1: ```json ... ``` block
        val jsonBlockMatch = Regex("```json\\s*\\n([\\s\\S]*?)\\n\\s*```").find(text)
        val jsonBlock = jsonBlockMatch?.groupValues?.get(1)?.trim()

        // Attempt 2: ``` ... ``` с JSON внутри
        val codeBlockMatch = Regex("```\\s*\\n([\\s\\S]*?)\\n\\s*```")
            .findAll(text)
            .firstOrNull { it.groupValues[1].trim().startsWith("{") || it.groupValues[1].trim().startsWith("[") }
        val codeBlock = codeBlockMatch?.groupValues?.get(1)?.trim()

        // Attempt 3: raw JSON
        val rawJson = if (text.startsWith("{") || text.startsWith("[")) text else null

        // Attempt 4: JSON внутри текста
        val embedded = findEmbeddedJson(text)

        val jsonText = jsonBlock ?: codeBlock ?: rawJson ?: embedded

        if (jsonText == null) {
            // Attempt 5: Весь текст — это просто сообщение (без JSON)
            if (text.isNotBlank() && !text.contains("{")) {
                println("[MaxVibes Clipboard] No JSON found, treating as plain message")
                return ClipboardResponse(message = text)
            }
            println("[MaxVibes Clipboard] Failed to find JSON in response")
            return null
        }

        // Собираем текст ДО и ПОСЛЕ JSON-блока — это объяснение от Claude
        val surroundingText = extractSurroundingText(text, jsonBlockMatch ?: codeBlockMatch)

        return try {
            val response = parseUnifiedResponse(jsonText)
            println("[MaxVibes Clipboard] Parsed: message=${response.message.take(50)}, " +
                    "files=${response.requestedFiles.size}, mods=${response.modifications.size}")

            // Если message пустой в JSON, но Claude написал текст вокруг — используем его
            if (response.message.isBlank() && surroundingText.isNotBlank()) {
                response.copy(message = surroundingText)
            } else {
                response
            }
        } catch (e: Exception) {
            println("[MaxVibes Clipboard] JSON parse error: ${e.message}")
            try {
                embedded?.let { parseUnifiedResponse(it) }
            } catch (_: Exception) {
                if (surroundingText.isNotBlank()) {
                    ClipboardResponse(message = surroundingText)
                } else null
            }
        }
    }

    /**
     * Извлекает текст до и после JSON-блока (объяснение Claude вне JSON).
     */
    private fun extractSurroundingText(fullText: String, jsonMatch: MatchResult?): String {
        if (jsonMatch == null) return ""

        val before = fullText.substring(0, jsonMatch.range.first).trim()
        val after = fullText.substring(jsonMatch.range.last + 1).trim()

        return buildString {
            if (before.isNotBlank()) append(before)
            if (before.isNotBlank() && after.isNotBlank()) append("\n\n")
            if (after.isNotBlank()) append(after)
        }
            .replace(Regex("^```\\w*\\s*"), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
    }

    // ==================== Serialization ====================

    private fun serializeRequest(request: ClipboardRequest): String {
        val obj = buildJsonObject {
            put("_protocol", "MaxVibes IDE Plugin — respond with JSON only, do NOT use tools/artifacts/computer")
            put("_responseFormat", "Respond with ONLY a raw JSON object: {\"message\": \"...\", \"requestedFiles\": [...], \"modifications\": [...]}")

            if (request.systemInstruction.isNotBlank()) {
                put("systemInstruction", request.systemInstruction)
            }

            put("task", request.task)
            put("projectName", request.projectName)

            if (request.fileTree.isNotBlank()) {
                put("fileTree", truncate(request.fileTree, 8000))
            }

            if (request.freshFiles.isNotEmpty()) {
                putJsonObject("files") {
                    request.freshFiles.forEach { (path, content) ->
                        put(path, truncate(content, 12000))
                    }
                }
            }

            if (request.previouslyGatheredPaths.isNotEmpty()) {
                putJsonArray("previouslyGatheredFiles") {
                    request.previouslyGatheredPaths.forEach { add(it) }
                }
            }

            if (request.chatHistory.isNotEmpty()) {
                putJsonArray("chatHistory") {
                    request.chatHistory.forEach { entry ->
                        addJsonObject {
                            put("role", entry.role)
                            put("content", truncate(entry.content, 4000))
                        }
                    }
                }
            }

            val trace = request.attachedContext
            if (!trace.isNullOrBlank()) {
                put("errorTrace", truncate(trace, 4000))
            }
        }

        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun truncate(text: String, maxLength: Int): String {
        return if (text.length > maxLength) {
            text.take(maxLength) + "\n... [truncated, ${text.length - maxLength} chars omitted]"
        } else text
    }

    // ==================== Parsing ====================

    /**
     * Парсит единый формат ответа. Все поля опциональны.
     */
    private fun parseUnifiedResponse(jsonText: String): ClipboardResponse {
        val obj = lenientJson.parseToJsonElement(jsonText).jsonObject

        return ClipboardResponse(
            message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "",
            requestedFiles = obj["requestedFiles"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            reasoning = obj["reasoning"]?.jsonPrimitive?.contentOrNull,
            modifications = obj["modifications"]?.jsonArray
                ?.mapNotNull { parseModification(it.jsonObject) } ?: emptyList()
        )
    }

    private fun parseModification(obj: JsonObject): ClipboardModification? {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return null
        return ClipboardModification(
            type = type,
            path = path,
            content = obj["content"]?.jsonPrimitive?.contentOrNull ?: "",
            elementKind = obj["elementKind"]?.jsonPrimitive?.contentOrNull ?: "FILE",
            position = obj["position"]?.jsonPrimitive?.contentOrNull ?: "LAST_CHILD",
            importPath = obj["importPath"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }

    private fun findEmbeddedJson(text: String): String? {
        val indicators = listOf("\"requestedFiles\"", "\"modifications\"", "\"message\"")
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

        var depth = 0; var inString = false; var escape = false
        for (i in startIndex until text.length) {
            val c = text[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == '{') depth++
            if (c == '}') { depth--; if (depth == 0) return text.substring(startIndex, i + 1) }
        }
        return null
    }
}