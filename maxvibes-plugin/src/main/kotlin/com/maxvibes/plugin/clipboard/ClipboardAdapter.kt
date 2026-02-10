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

        val jsonText = jsonBlock ?: codeBlock ?: rawJson ?: embedded ?: return null

        // Собираем текст ДО и ПОСЛЕ JSON-блока — это объяснение от Claude
        val surroundingText = extractSurroundingText(text, jsonBlockMatch ?: codeBlockMatch)

        return try {
            val response = parseJsonResponse(jsonText)

            // Если message пустой в JSON, но Claude написал текст вокруг — используем его
            if (response.message.isBlank() && surroundingText.isNotBlank()) {
                response.copy(message = surroundingText)
            } else {
                response
            }
        } catch (e: Exception) {
            try { embedded?.let { parseJsonResponse(it) } } catch (_: Exception) { null }
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
            // Убираем артефакты markdown
            .replace(Regex("^```\\w*\\s*"), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
    }

    // ==================== Serialization ====================

    private fun serializeRequest(request: ClipboardRequest): String {
        val obj = buildJsonObject {
            put("phase", request.phase.name)
            put("task", request.task)
            put("projectName", request.projectName)

            if (request.systemInstruction.isNotBlank()) {
                put("systemInstruction", request.systemInstruction)
            }

            putJsonObject("context") {
                request.context.forEach { (key, value) ->
                    put(key, if (value.length > 8000) value.take(8000) + "\n... [truncated]" else value)
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
        }

        return json.encodeToString(JsonObject.serializer(), obj)
    }

    // ==================== Parsing ====================

    private fun parseJsonResponse(jsonText: String): ClipboardResponse {
        val obj = lenientJson.parseToJsonElement(jsonText).jsonObject

        val hasRequestedFiles = obj.containsKey("requestedFiles")
        val hasModifications = obj.containsKey("modifications")
        val explicitPhase = obj["phase"]?.jsonPrimitive?.contentOrNull

        val phase = when {
            explicitPhase != null -> try { ClipboardPhase.valueOf(explicitPhase) } catch (_: Exception) { null }
            hasRequestedFiles -> ClipboardPhase.PLANNING
            hasModifications -> ClipboardPhase.CHAT
            else -> ClipboardPhase.CHAT
        } ?: ClipboardPhase.CHAT

        return when (phase) {
            ClipboardPhase.PLANNING -> ClipboardResponse(
                phase = ClipboardPhase.PLANNING,
                requestedFiles = obj["requestedFiles"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                reasoning = obj["reasoning"]?.jsonPrimitive?.contentOrNull
            )
            ClipboardPhase.CHAT -> ClipboardResponse(
                phase = ClipboardPhase.CHAT,
                message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "",
                modifications = obj["modifications"]?.jsonArray
                    ?.mapNotNull { parseModification(it.jsonObject) } ?: emptyList()
            )
        }
    }

    private fun parseModification(obj: JsonObject): ClipboardModification? {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return null
        return ClipboardModification(
            type = type,
            path = path,
            content = obj["content"]?.jsonPrimitive?.contentOrNull ?: "",
            elementKind = obj["elementKind"]?.jsonPrimitive?.contentOrNull ?: "FILE",
            position = obj["position"]?.jsonPrimitive?.contentOrNull ?: "LAST_CHILD"
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