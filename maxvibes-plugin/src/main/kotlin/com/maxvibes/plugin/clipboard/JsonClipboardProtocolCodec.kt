package com.maxvibes.plugin.clipboard

import com.maxvibes.application.port.output.ClipboardProtocolCodec
import com.maxvibes.application.port.output.ClipboardRequestSchema
import com.maxvibes.domain.model.interaction.*
import kotlinx.serialization.json.*

/**
 * Pure [ClipboardProtocolCodec] implementation backed by kotlinx.serialization.
 *
 * Encodes [ClipboardRequest] → pretty-printed JSON and decodes raw LLM
 * response text → [ClipboardResponse]. Contains zero IntelliJ Platform SDK
 * imports — only stdlib and kotlinx.serialization — so it is directly
 * unit-testable without an IDE environment.
 *
 * Field name constants are sourced exclusively from [ClipboardRequestSchema];
 * never hardcode string keys here.
 */
class JsonClipboardProtocolCodec : ClipboardProtocolCodec {

    /** Strict pretty-print encoder for outgoing requests. */
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Lenient decoder for incoming LLM responses (unquoted keys, trailing commas, etc.). */
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ── Encode ────────────────────────────────────────────────────────

    /**
     * Serializes [request] into a JSON string suitable for the system clipboard.
     *
     * Only non-default / non-blank fields are included in the output to keep
     * the payload compact. Optional boolean flags (e.g. [ClipboardRequest.planOnly])
     * are omitted when false.
     */
    override fun encode(request: ClipboardRequest): String {
        val obj = buildJsonObject {
            // Meta-fields: instruct the LLM how to behave and format its response
            put(ClipboardRequestSchema.META_PROTOCOL, ClipboardRequestSchema.PROTOCOL_MARKER)
            put(ClipboardRequestSchema.META_RESPONSE_FORMAT, ClipboardRequestSchema.RESPONSE_FORMAT_HINT)

            // System prompt — omitted when blank to save tokens
            if (request.systemInstruction.isNotBlank()) {
                put(ClipboardRequestSchema.FIELD_SYSTEM_INSTRUCTION, request.systemInstruction)
            }

            // Core task fields (always present)
            put(ClipboardRequestSchema.FIELD_TASK, request.task)
            put(ClipboardRequestSchema.FIELD_PROJECT_NAME, request.projectName)

            // Optional flags
            if (request.planOnly) {
                put(ClipboardRequestSchema.FIELD_PLAN_ONLY, true)
            }

            // Project file tree snapshot
            if (request.fileTree.isNotBlank()) {
                put(ClipboardRequestSchema.FIELD_FILE_TREE, request.fileTree)
            }

            // Full file contents for freshly-requested files
            if (request.freshFiles.isNotEmpty()) {
                putJsonObject(ClipboardRequestSchema.FIELD_FILES) {
                    request.freshFiles.forEach { (path, content) -> put(path, content) }
                }
            }

            // Paths already gathered in a previous round-trip (no content, just references)
            if (request.previouslyGatheredPaths.isNotEmpty()) {
                putJsonArray(ClipboardRequestSchema.FIELD_PREVIOUSLY_GATHERED) {
                    request.previouslyGatheredPaths.forEach { add(it) }
                }
            }

            // Multi-turn conversation history
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

            // Optional error context (stack traces, IDE diagnostics)
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

    /**
     * Decodes a raw LLM response into a [ClipboardResponse].
     *
     * Tries the following extraction strategies in order of preference:
     * 1. ` ```json ` … ` ``` ` fenced block
     * 2. Plain ` ``` ` code block whose content starts with `{`
     * 3. Raw text that starts directly with `{`
     * 4. JSON embedded anywhere inside free-form text ([findEmbeddedJson])
     *
     * Falls back to a plain-text [ClipboardResponse] when no JSON is found
     * but the input is non-blank. Returns `null` for blank input or when
     * parsing fails completely.
     */
    override fun decode(rawText: String): ClipboardResponse? {
        val text = rawText.trim()
        if (text.isBlank()) return null

        // Strategy 1: ```json ... ``` block
        val jsonBlockMatch = Regex("`{3}json\\s*\\n([\\s\\S]*?)\\n\\s*`{3}").find(text)
        val jsonBlock = jsonBlockMatch?.groupValues?.get(1)?.trim()

        // Strategy 2: plain ``` ... ``` block that starts with '{'
        val codeBlockMatch = Regex("`{3}\\s*\\n([\\s\\S]*?)\\n\\s*`{3}")
            .findAll(text)
            .firstOrNull { it.groupValues[1].trim().startsWith("{") }
        val codeBlock = codeBlockMatch?.groupValues?.get(1)?.trim()

        // Strategy 3: whole text is raw JSON
        val rawJson = if (text.startsWith("{")) text else null

        // Strategy 4: JSON embedded somewhere in the text
        val embedded = findEmbeddedJson(text)

        val jsonText = jsonBlock ?: codeBlock ?: rawJson ?: embedded

        // No JSON found — treat non-empty text without braces as a plain message
        if (jsonText == null) {
            return if (text.isNotBlank() && !text.contains("{")) {
                ClipboardResponse(message = text)
            } else null
        }

        // Extract any human-readable text surrounding the JSON block
        val surroundingText = extractSurroundingText(text, jsonBlockMatch ?: codeBlockMatch)

        return try {
            val response = parseUnifiedResponse(jsonText)
            // If the JSON message field is blank, use surrounding prose as the message
            if (response.message.isBlank() && surroundingText.isNotBlank()) {
                response.copy(message = surroundingText)
            } else response
        } catch (e: Exception) {
            // Primary parse failed — attempt fallback on embedded JSON
            try {
                embedded?.let { parseUnifiedResponse(it) }
            } catch (_: Exception) {
                if (surroundingText.isNotBlank()) ClipboardResponse(message = surroundingText) else null
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * Parses a JSON string into a [ClipboardResponse] using lenient mode.
     *
     * Missing optional fields default to their zero values (empty string / null / empty list).
     *
     * @throws kotlinx.serialization.SerializationException if [jsonText] is not valid JSON
     */
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

    /**
     * Parses a single `modifications[]` entry.
     *
     * Returns `null` (and silently skips the entry) if mandatory fields
     * [ClipboardRequestSchema.MOD_TYPE] or [ClipboardRequestSchema.MOD_PATH] are absent.
     */
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

    /**
     * Scans [text] for a JSON object that contains at least one known response field
     * (`"message"`, `"requestedFiles"`, or `"modifications"`) and extracts the
     * complete object by counting brace depth.
     *
     * Marked `internal` for unit-test access.
     *
     * @return the extracted JSON substring, or `null` if none is found
     */
    internal fun findEmbeddedJson(text: String): String? {
        // Look for the leftmost occurrence of any known indicator key
        val indicators = listOf(
            "\"${ClipboardRequestSchema.RESP_REQUESTED_FILES}\"",
            "\"${ClipboardRequestSchema.RESP_MODIFICATIONS}\"",
            "\"${ClipboardRequestSchema.RESP_MESSAGE}\""
        )
        val startIndex = indicators
            .mapNotNull { indicator ->
                val idx = text.indexOf(indicator)
                if (idx >= 0) {
                    // Walk back to find the opening '{' of the enclosing object
                    var braceIdx = idx
                    while (braceIdx > 0 && text[braceIdx] != '{') braceIdx--
                    if (text[braceIdx] == '{') braceIdx else null
                } else null
            }
            .minOrNull() ?: return null

        // Walk forward to find the matching closing '}'
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

    /**
     * Extracts human-readable prose before and after a matched JSON block.
     *
     * Used to populate [ClipboardResponse.message] when the LLM places
     * an explanation outside the JSON object.
     *
     * @param fullText  the complete raw LLM response
     * @param jsonMatch the regex match that identified the JSON block
     * @return trimmed surrounding text, or empty string if [jsonMatch] is null
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
            .replace(Regex("^`{3}\\w*\\s*"), "")
            .replace(Regex("\\s*`{3}$"), "")
            .trim()
    }
}
