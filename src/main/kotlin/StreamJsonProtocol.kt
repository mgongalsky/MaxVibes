package com.maxvibes.plugin.claudecode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Helpers for the Claude Code stream-JSON format.
 *
 * VERIFIED against claude-code 2.1.129 (May 2026) by piping a stream-json user
 * event through `claude -p --input-format stream-json --output-format stream-json --verbose`
 * and recording the actual stdout shape.
 *
 * Each line on stdout is a single JSON object with a `type` field. Recognised types:
 *  - `type="system"` with `subtype="init"` — first event, contains `session_id`
 *  - `type="assistant"` — model response; payload at `message.content[].text`
 *    (each block has `type="text"` and `text="..."`)
 *  - `type="rate_limit_event"` — interleaved diagnostic, ignored for now
 *  - `type="result"` — terminal event for the turn (with `is_error`, `result`, `duration_ms`, etc.)
 *
 * On stdin we send a `type="user"` event with `message.content` as a plain string
 * (NOT an array of content blocks — claude-code stream-json input expects the
 * simpler shape, even though the Anthropic Messages API uses content blocks).
 */
internal object StreamJsonProtocol {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    /**
     * Wraps an encoded request JSON string into a stream-json user event.
     *
     * Produces a single line of the form:
     *   {"type":"user","message":{"role":"user","content":"<requestJsonText>"}}
     *
     * The whole serialized request from [com.maxvibes.application.port.output.InteractionProtocolCodec]
     * is passed through as the `content` string — claude-code does not parse it,
     * it only forwards it as the user message text.
     */
    fun encodeUserEvent(requestJsonText: String): String {
        val obj = buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                put("role", "user")
                put("content", requestJsonText)
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /** Returns session id if the line is a system/init event, null otherwise. */
    fun extractSessionId(line: String): String? {
        val obj = parseLine(line) ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "system") return null
        if (obj["subtype"]?.jsonPrimitive?.contentOrNull != "init") return null
        return obj["session_id"]?.jsonPrimitive?.contentOrNull
    }

    /**
     * Returns assistant text content if the line is an assistant event, null otherwise.
     *
     * Handles multiple content blocks by concatenating all `text` fields where
     * `type=="text"`. Non-text blocks (tool_use, etc.) are skipped — we expect
     * none of them since the system prompt forbids built-in tools, but the code
     * is robust to their presence.
     */
    fun extractAssistantText(line: String): String? {
        val obj = parseLine(line) ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "assistant") return null

        val message = obj["message"]?.jsonObject ?: return null
        val content = message["content"]?.jsonArray ?: return null

        val parts = content.mapNotNull { element ->
            val contentObj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            if (contentObj["type"]?.jsonPrimitive?.contentOrNull != "text") return@mapNotNull null
            contentObj["text"]?.jsonPrimitive?.contentOrNull
        }
        return if (parts.isEmpty()) null else parts.joinToString(separator = "")
    }

    /**
     * True if the line is the turn-terminator event (type="result").
     *
     * Note: a `result` event with `is_error=true` also returns true here — it
     * still terminates the turn. Callers that need to distinguish must inspect
     * the line themselves before passing it in.
     */
    fun isTurnEnd(line: String): Boolean {
        val obj = parseLine(line) ?: return false
        return obj["type"]?.jsonPrimitive?.contentOrNull == "result"
    }

    private fun parseLine(line: String): JsonObject? =
        runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
}
