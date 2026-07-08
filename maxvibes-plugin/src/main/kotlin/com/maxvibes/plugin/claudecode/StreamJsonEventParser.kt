package com.maxvibes.plugin.claudecode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Stateful line parser for the Claude Code stream-json NDJSON output.
 *
 * One instance per adapter; [reset] is called at every turn start. Tolerant by
 * contract: a malformed line or unknown type/subtype yields [Line.Unknown] or
 * [Line.Ignored] and must NEVER take the session down. Uses kotlinx.serialization
 * (lenient, unknown keys ignored) - same stack as the rest of the stream-json path;
 * no Jackson, hence no ServiceLoader classloader hazard by construction.
 *
 * Field access is defensive throughout: a missing field degrades the specific
 * feature (stats, live text, notices), never the turn. Encoded against CLI 2.1.138;
 * verify against a live transcript during smoke and adjust field names here only.
 */
internal class StreamJsonEventParser {

    /** Parsed meaning of one stdout line. */
    sealed interface Line {
        data class Init(val sessionId: String?, val model: String?) : Line

        /** system/api_retry and other informational system subtypes. */
        data class SystemNotice(val text: String) : Line

        /** Live delta from stream_event / content_block_delta. */
        data class Delta(val messageId: String, val text: String, val thinking: Boolean) : Line

        /** Authoritative assistant message (may carry text, thinking and tool uses at once). */
        data class Assistant(
            val messageId: String,
            val text: String?,
            val thinking: String?,
            val toolUses: List<ToolUse>
        ) : Line

        data class ToolUse(val id: String, val name: String, val summary: String)

        data class ToolResult(val toolUseId: String, val ok: Boolean, val summary: String?) : Line

        data class TurnEnd(
            val finalText: String?,
            val isError: Boolean,
            val costUsd: Double,
            val numTurns: Int,
            val durationMs: Long,
            val inputTokens: Int,
            val outputTokens: Int
        ) : Line

        /** Recognised envelope, nothing to surface (message_start, block start/stop, ...). */
        object Ignored : Line

        /** Unrecognised top-level type - caller logs it to the plugin log. */
        data class Unknown(val type: String?) : Line
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** stream_event state: id of the message currently being streamed via deltas. */
    private var currentMessageId: String = "msg-unknown"

    fun reset() {
        currentMessageId = "msg-unknown"
    }

    fun parse(rawLine: String): Line {
        val obj = runCatching { json.parseToJsonElement(rawLine).jsonObject }.getOrNull()
            ?: return Line.Unknown(null)
        return when (val type = obj.str("type")) {
            "system" -> parseSystem(obj)
            "assistant" -> parseAssistant(obj)
            "user" -> parseToolResult(obj)
            "stream_event" -> parseStreamEvent(obj)
            "result" -> parseResult(obj)
            "rate_limit_event" -> parseRateLimit(obj)
            else -> Line.Unknown(type)
        }
    }

    private fun parseSystem(obj: JsonObject): Line = when (val sub = obj.str("subtype")) {
        "init" -> Line.Init(sessionId = obj.str("session_id"), model = obj.str("model"))
        "api_retry" -> {
            val attempt = obj.int("attempt")
            val max = obj.int("max_retries")
            val err = obj.str("error") ?: obj.str("message") ?: "retrying"
            val delayMs = obj.long("delay_ms")
            val head = if (attempt != null && max != null) "API retry $attempt/$max" else "API retry"
            val tail = delayMs?.takeIf { it > 0 }?.let { " (waiting ${it / 1000}s)" } ?: ""
            Line.SystemNotice("$head: $err$tail")
        }
        // Fires at every turn start with status="requesting" - zero info for the user;
        // liveness is covered by the last-event-age indicator. Raw line stays in CC log.
        "status" -> Line.Ignored
        null -> Line.Ignored
        else -> Line.SystemNotice("system: $sub") // compaction and friends
    }

    /**
     * Verified 2.1.138 schema: {"type":"rate_limit_event","rate_limit_info":{"status":"allowed",
     * "resetsAt":<unix-sec>,"rateLimitType":"five_hour",...}}. status "allowed" is a routine
     * per-turn ping - ignored, or every turn block would show a scary rate-limit Notice.
     * Anything else surfaces with type and reset ETA.
     */
    private fun parseRateLimit(obj: JsonObject): Line {
        val info = obj.obj("rate_limit_info")
            ?: return Line.SystemNotice(obj.str("message") ?: "rate limit notice") // pre-2.1 fallback
        val status = info.str("status") ?: return Line.Ignored
        if (status.equals("allowed", ignoreCase = true)) return Line.Ignored
        val type = info.str("rateLimitType")?.let { " ($it)" } ?: ""
        val eta = info.long("resetsAt")?.let {
            val min = ((it * 1000 - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
            ", resets in ${min}min"
        } ?: ""
        return Line.SystemNotice("rate limit: $status$type$eta")
    }

    private fun parseAssistant(obj: JsonObject): Line {
        val message = obj.obj("message") ?: return Line.Ignored
        val id = message.str("id") ?: currentMessageId
        val content = message["content"] as? JsonArray ?: return Line.Ignored
        val text = StringBuilder()
        val thinking = StringBuilder()
        val tools = ArrayList<Line.ToolUse>()
        for (el in content) {
            val block = el as? JsonObject ?: continue
            when (block.str("type")) {
                "text" -> block.str("text")?.let { text.append(it) }
                "thinking" -> block.str("thinking")?.takeIf { it.isNotBlank() }?.let {
                    if (thinking.isNotEmpty()) thinking.append("\n\n")
                    thinking.append(it)
                }
                "tool_use" -> tools.add(
                    Line.ToolUse(
                        id = block.str("id") ?: "tool-${tools.size}",
                        name = block.str("name") ?: "tool",
                        summary = toolSummary(block["input"] as? JsonObject)
                    )
                )
            }
        }
        return Line.Assistant(
            messageId = id,
            text = text.toString().takeIf { it.isNotEmpty() },
            thinking = thinking.toString().takeIf { it.isNotEmpty() },
            toolUses = tools
        )
    }

    /** Short one-line summary of a tool_use input: known path-ish keys first, raw JSON tail otherwise. */
    private fun toolSummary(input: JsonObject?): String {
        if (input == null) return ""
        for (key in listOf("file_path", "path", "pattern", "command", "url", "query", "description")) {
            input.str(key)?.takeIf { it.isNotBlank() }?.let { return oneLine(it) }
        }
        return oneLine(input.toString())
    }

    private fun parseToolResult(obj: JsonObject): Line {
        val content = obj.obj("message")?.get("content") as? JsonArray ?: return Line.Ignored
        for (el in content) {
            val block = el as? JsonObject ?: continue
            if (block.str("type") != "tool_result") continue
            val id = block.str("tool_use_id") ?: continue
            val isError = (block["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false
            val summary = when (val c = block["content"]) {
                is JsonPrimitive -> c.contentOrNull
                is JsonArray -> c.filterIsInstance<JsonObject>().firstNotNullOfOrNull { it.str("text") }
                else -> null
            }?.let { oneLine(it) }
            return Line.ToolResult(toolUseId = id, ok = !isError, summary = summary)
        }
        return Line.Ignored
    }

    private fun parseStreamEvent(obj: JsonObject): Line {
        val event = obj.obj("event") ?: return Line.Ignored
        return when (event.str("type")) {
            "message_start" -> {
                currentMessageId = event.obj("message")?.str("id") ?: "msg-unknown"
                Line.Ignored
            }
            "content_block_delta" -> {
                val delta = event.obj("delta") ?: return Line.Ignored
                when (delta.str("type")) {
                    "text_delta" -> delta.str("text")
                        ?.let { Line.Delta(currentMessageId, it, thinking = false) } ?: Line.Ignored
                    "thinking_delta" -> delta.str("thinking")
                        ?.let { Line.Delta(currentMessageId, it, thinking = true) } ?: Line.Ignored
                    else -> Line.Ignored
                }
            }
            // message_stop, content_block_start/stop, message_delta, ping - nothing to surface.
            else -> Line.Ignored
        }
    }

    private fun parseResult(obj: JsonObject): Line {
        val usage = obj.obj("usage")
        val input = (usage?.int("input_tokens") ?: 0) +
                (usage?.int("cache_creation_input_tokens") ?: 0) +
                (usage?.int("cache_read_input_tokens") ?: 0)
        return Line.TurnEnd(
            finalText = obj.str("result"),
            isError = (obj["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false,
            costUsd = (obj["total_cost_usd"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            numTurns = obj.int("num_turns") ?: 0,
            durationMs = obj.long("duration_ms") ?: 0L,
            inputTokens = input,
            outputTokens = usage?.int("output_tokens") ?: 0
        )
    }

    private fun oneLine(s: String): String =
        s.replace('\n', ' ').replace(Regex("\\s+"), " ").trim().take(SUMMARY_MAX)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private companion object {
        /** ТЗ guideline: tool summary <= ~80 chars. */
        const val SUMMARY_MAX = 80
    }
}