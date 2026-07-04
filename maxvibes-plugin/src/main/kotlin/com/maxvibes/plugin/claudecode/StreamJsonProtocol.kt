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
 * Encoded against claude-code 2.1.x stream-json spec (May 2026):
 *  - Each line on stdout is a single JSON object with a `type` field.
 *  - Recognised types:
 *      `type="system"` with `subtype="init"` — first event, contains `session_id`
 *      `type="assistant"` — model response; payload at `message.content[]`,
 *                            where each block has a `type` discriminator:
 *                              `type="text"` — user-visible text (the only kind
 *                                              accumulated into the final answer)
 *                              `type="thinking"` — chain-of-thought block,
 *                                                  surfaced as live activity only
 *                              `type="tool_use"` — model invokes a built-in tool;
 *                                                  surfaced as live activity only
 *      `type="rate_limit_event"` — informational rate-limit notice
 *      `type="result"` — terminal event for the turn
 *                        (with `is_error`, `result`, `duration_ms`, etc.)
 *
 * On stdin we send a `type="user"` event with `message.content` as a plain string
 * (NOT an array of content blocks — claude-code stream-json input expects the
 * simpler shape, even though the Anthropic Messages API uses content blocks).
 *
 * SMOKE TEST (TODO before production use): pipe one user event through
 *   claude -p --input-format stream-json --output-format stream-json --verbose
 * and confirm the assistant/result envelope shapes still match these helpers.
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
     * `type=="text"`. Non-text blocks (tool_use, thinking, etc.) are skipped —
     * those are surfaced separately via [extractThinkingPreview] / [extractToolUseName]
     * for live activity, never accumulated into the final assistant text.
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

    /**
     * Returns a short human-readable summary if the line is a `rate_limit_event`,
     * null otherwise.
     *
     * The exact shape of rate_limit_event payloads is not formally documented —
     * we conservatively look for known fields (`message`, `reset_seconds`, `tier`)
     * and fall back to a generic "rate limit notice" string. UI uses this only for
     * informational display in the live bubble; missing detail is acceptable.
     */
    fun extractRateLimitInfo(line: String): String? {
        val obj = parseLine(line) ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "rate_limit_event") return null

        val message = obj["message"]?.jsonPrimitive?.contentOrNull
        if (!message.isNullOrBlank()) return message

        val resetSeconds = obj["reset_seconds"]?.jsonPrimitive?.contentOrNull
        if (!resetSeconds.isNullOrBlank()) return "rate limit, resets in ${resetSeconds}s"

        return "rate limit notice"
    }

    /**
     * Returns a short preview of an extended-thinking block if the line is an
     * `assistant` event whose first content block has `type="thinking"`, null otherwise.
     *
     * Used for live-activity UI only — these previews are NOT accumulated into the
     * final assistant text (see [extractAssistantText], which filters strictly on
     * `type=="text"`). The intent is to give the user a visible signal that the
     * model is working through a chain of thought even when no user-facing text
     * has been streamed yet — empirically these blocks can take 30-180 seconds
     * during which the bubble would otherwise sit on "Started".
     *
     * Truncation: returned preview is single-line, whitespace-normalised, capped at
     * ~90 characters. The UI may further sanitise.
     */
    fun extractThinkingPreview(line: String): String? {
        val obj = parseLine(line) ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "assistant") return null

        val message = obj["message"]?.jsonObject ?: return null
        val content = message["content"]?.jsonArray ?: return null
        val firstBlock = content.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
        if (firstBlock["type"]?.jsonPrimitive?.contentOrNull != "thinking") return null

        val raw = firstBlock["thinking"]?.jsonPrimitive?.contentOrNull?.trim() ?: return null
        if (raw.isEmpty()) return null

        val singleLine = raw.replace('\n', ' ').replace(Regex("\\s+"), " ")
        return if (singleLine.length > 90) singleLine.take(87) + "..." else singleLine
    }

    /**
     * Returns the FULL text of all extended-thinking blocks in an `assistant` event,
     * or null when the line is not an assistant event or carries no thinking.
     *
     * Differences from [extractThinkingPreview]: iterates ALL content blocks (a single
     * event may carry several), preserves whitespace/newlines, applies no truncation.
     * Used to persist the complete chain of thought into the chat message
     * (ThinkingBubble feature); the preview stays as-is for the Live Activity contract.
     * Never fed back to the model — the CLI strips past thinking from context anyway.
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

    /**
     * Returns the tool name if the line is an `assistant` event containing a
     * `tool_use` content block, null otherwise. Surfaces brief progress updates
     * like "using Read" / "using Glob" in the live-activity bubble.
     *
     * Note: in MaxVibes/ClaudeCode mode the model is instructed NOT to call any
     * built-in tools — but it sometimes tries anyway, and each rejected attempt
     * costs 5-30 seconds of silent latency. Surfacing these calls gives the user
     * a fighting chance to see what's happening.
     */
    fun extractToolUseName(line: String): String? {
        val obj = parseLine(line) ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "assistant") return null

        val message = obj["message"]?.jsonObject ?: return null
        val content = message["content"]?.jsonArray ?: return null

        for (element in content) {
            val block = runCatching { element.jsonObject }.getOrNull() ?: continue
            if (block["type"]?.jsonPrimitive?.contentOrNull != "tool_use") continue
            val name = block["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (name != null) return name
        }
        return null
    }

    private fun parseLine(line: String): JsonObject? =
        runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
}
