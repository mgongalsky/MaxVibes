package com.maxvibes.plugin.clipboard

import com.maxvibes.application.port.output.ClipboardPort
import com.maxvibes.domain.model.interaction.*
import com.maxvibes.plugin.service.MaxVibesLogger
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
            MaxVibesLogger.debug("Clipboard", "copied request", mapOf(
                "task" to request.task.take(60),
                "freshFiles" to request.freshFiles.size,
                "planOnly" to request.planOnly
            ))
            true
        } catch (e: Exception) {
            MaxVibesLogger.error("Clipboard", "copyRequestToClipboard failed", e)
            false
        }
    }

    override fun parseResponse(rawText: String): ClipboardResponse? {
        val text = rawText.trim()

        val jsonBlockMatch = Regex("```json\\s*\\n([\\s\\S]*?)\\n\\s*```").find(text)
        val jsonBlock = jsonBlockMatch?.groupValues?.get(1)?.trim()

        val codeBlockMatch = Regex("```\\s*\\n([\\s\\S]*?)\\n\\s*```")
            .findAll(text)
            .firstOrNull { it.groupValues[1].trim().startsWith("{") || it.groupValues[1].trim().startsWith("[") }
        val codeBlock = codeBlockMatch?.groupValues?.get(1)?.trim()

        val rawJson = if (text.startsWith("{") || text.startsWith("[")) text else null
        val embedded = findEmbeddedJson(text)
        val jsonText = jsonBlock ?: codeBlock ?: rawJson ?: embedded

        if (jsonText == null) {
            if (text.isNotBlank() && !text.contains("{")) {
                MaxVibesLogger.debug("Clipboard", "no JSON, treating as plain message", mapOf("len" to text.length))
                return ClipboardResponse(message = text)
            }
            MaxVibesLogger.warn("Clipboard", "failed to find JSON", data = mapOf("preview" to text.take(120)))
            return null
        }

        val surroundingText = extractSurroundingText(text, jsonBlockMatch ?: codeBlockMatch)

        return try {
            val response = parseUnifiedResponse(jsonText)
            MaxVibesLogger.debug("Clipboard", "parsed response", mapOf(
                "msg" to response.message.take(60),
                "files" to response.requestedFiles.size,
                "mods" to response.modifications.size
            ))
            if (response.message.isBlank() && surroundingText.isNotBlank()) {
                response.copy(message = surroundingText)
            } else {
                response
            }
        } catch (e: Exception) {
            MaxVibesLogger.error("Clipboard", "JSON parse error", e, mapOf("jsonPreview" to jsonText.take(200)))
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
            put(
                "_responseFormat",
                "Respond with ONLY a raw JSON object: {\"message\": \"...\", \"requestedFiles\": [...], \"modifications\": [...]}"
            )

            if (request.systemInstruction.isNotBlank()) {
                put("systemInstruction", request.systemInstruction)
            }

            put("task", request.task)
            put("projectName", request.projectName)

            if (request.planOnly) {
                put("planOnly", true)
            }

            if (request.fileTree.isNotBlank()) {
                put("fileTree", request.fileTree)
            }

            if (request.freshFiles.isNotEmpty()) {
                putJsonObject("files") {
                    request.freshFiles.forEach { (path, content) ->
                        put(path, content)
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
                            put("content", entry.content)
                        }
                    }
                }
            }

            val trace = request.attachedContext
            if (!trace.isNullOrBlank()) {
                put("errorTrace", trace)
            }

            val errs = request.ideErrors
            if (!errs.isNullOrBlank()) {
                put("ideErrors", errs)
            }
        }

        return json.encodeToString(JsonObject.serializer(), obj)
    }

    // ==================== Parsing ====================

    private fun parseUnifiedResponse(jsonText: String): ClipboardResponse {
        val obj = lenientJson.parseToJsonElement(jsonText).jsonObject

        return ClipboardResponse(
            message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "",
            reasoning = obj["reasoning"]?.jsonPrimitive?.contentOrNull,
            requestedFiles = obj["requestedFiles"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            modifications = obj["modifications"]?.jsonArray
                ?.mapNotNull { parseModification(it.jsonObject) } ?: emptyList(),
            commitMessage = obj["commitMessage"]?.jsonPrimitive?.contentOrNull
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
