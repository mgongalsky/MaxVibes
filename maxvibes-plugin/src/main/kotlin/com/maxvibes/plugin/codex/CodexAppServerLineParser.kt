package com.maxvibes.plugin.codex

import com.maxvibes.application.port.output.SessionStats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

/** Tolerant parser for one Codex App Server JSONL message. */
internal class CodexAppServerLineParser {
    sealed interface Line {
        data class Response(
            val id: Long,
            val result: JsonElement?,
            val error: RpcError?
        ) : Line

        data class RpcError(
            val code: Int?,
            val message: String,
            val data: String? = null
        )

        data class ThreadStarted(
            val threadId: String,
            val model: String?
        ) : Line

        data class TurnStarted(
            val turnId: String?
        ) : Line

        data class NarrationDelta(
            val itemId: String,
            val text: String
        ) : Line

        data class ReasoningDelta(
            val itemId: String,
            val text: String
        ) : Line

        data class NarrationMessage(
            val itemId: String,
            val text: String
        ) : Line

        data class ReasoningMessage(
            val itemId: String,
            val text: String
        ) : Line

        data class ToolStarted(
            val itemId: String,
            val name: String,
            val summary: String
        ) : Line

        data class ToolFinished(
            val itemId: String,
            val ok: Boolean,
            val summary: String?
        ) : Line

        data class TokenUsage(
            val inputTokens: Int,
            val outputTokens: Int
        ) : Line

        data class TurnCompleted(
            val turnId: String?,
            val status: String?,
            val errorMessage: String?
        ) : Line

        data class Notice(val text: String) : Line
        data class Unknown(val method: String?) : Line
        data object Ignored : Line

        /** Subscription limits; arrives independently of turns, never empty. */
        data class RateLimits(
            val windows: List<CodexRateLimitWindow>
        ) : Line
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(rawLine: String): Line {
        val obj = runCatching { json.parseToJsonElement(rawLine).jsonObject }.getOrNull()
            ?: return Line.Unknown(null)

        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        if (id != null && obj["method"] == null) {
            return Line.Response(
                id = id,
                result = obj["result"],
                error = parseRpcError(obj["error"] as? JsonObject)
            )
        }

        val method = obj.str("method") ?: return Line.Unknown(null)
        val params = obj.obj("params") ?: JsonObject(emptyMap())
        return when (method) {
            "thread/started" -> parseThreadStarted(params)
            "turn/started" -> Line.TurnStarted(params.obj("turn")?.str("id") ?: params.str("turnId"))
            "item/agentMessage/delta" -> parseDelta(params, thinking = false)
            "item/reasoning/summaryTextDelta",
            "item/reasoning/textDelta" -> parseDelta(params, thinking = true)

            "item/started" -> parseItemStarted(params)
            "item/completed" -> parseItemCompleted(params)
            "thread/tokenUsage/updated",
            "turn/tokenUsage/updated" -> parseTokenUsage(params)

            "account/rateLimits/updated" -> parseRateLimits(params)

            "turn/completed" -> parseTurnCompleted(params)
            "error" -> Line.Notice(
                params.str("message")
                    ?: params.obj("error")?.str("message")
                    ?: "Codex App Server error"
            )

            "account/updated",
            "thread/name/updated" -> Line.Ignored

            else -> Line.Unknown(method)
        }
    }

    private fun parseRpcError(error: JsonObject?): Line.RpcError? {
        if (error == null) return null
        return Line.RpcError(
            code = error["code"]?.jsonPrimitive?.intOrNull,
            message = error.str("message") ?: "Unknown App Server error",
            data = error["data"]?.toString()
        )
    }

    private fun parseThreadStarted(params: JsonObject): Line {
        val thread = params.obj("thread")
        val id = thread?.str("id") ?: params.str("threadId")
        ?: return Line.Unknown("thread/started")
        return Line.ThreadStarted(
            threadId = id,
            model = thread?.str("model") ?: params.str("model")
        )
    }

    private fun parseDelta(params: JsonObject, thinking: Boolean): Line {
        val itemId = params.str("itemId")
            ?: params.obj("item")?.str("id")
            ?: "item-unknown"
        val text = params.str("delta")
            ?: params.obj("delta")?.str("text")
            ?: return Line.Ignored
        return if (thinking) {
            Line.ReasoningDelta(itemId, text)
        } else {
            Line.NarrationDelta(itemId, text)
        }
    }

    private fun parseItemStarted(params: JsonObject): Line {
        val item = params.obj("item") ?: return Line.Ignored
        val type = item.str("type") ?: return Line.Ignored
        if (type == "agentMessage" || type == "reasoning") return Line.Ignored
        val id = item.str("id") ?: "item-unknown"
        return Line.ToolStarted(
            itemId = id,
            name = type,
            summary = itemSummary(item)
        )
    }

    private fun parseItemCompleted(params: JsonObject): Line {
        val item = params.obj("item") ?: return Line.Ignored
        val type = item.str("type") ?: return Line.Ignored
        val id = item.str("id") ?: "item-unknown"
        return when (type) {
            "agentMessage" -> {
                val text = itemText(item).takeIf { it.isNotBlank() } ?: return Line.Ignored
                Line.NarrationMessage(id, text)
            }

            "reasoning" -> {
                val text = itemText(item).takeIf { it.isNotBlank() } ?: return Line.Ignored
                Line.ReasoningMessage(id, text)
            }

            else -> {
                val status = item.str("status")
                val ok = status == null || (status != "failed" && status != "error")
                Line.ToolFinished(id, ok, itemSummary(item).takeIf { it.isNotBlank() })
            }
        }
    }

    private fun parseTokenUsage(params: JsonObject): Line {
        val usageRoot = params.obj("tokenUsage")
            ?: params.obj("usage")
            ?: params
        val usage = usageRoot.obj("total") ?: usageRoot
        val input = usage.intAny("inputTokens", "input_tokens")
        val output = usage.intAny("outputTokens", "output_tokens")
        return Line.TokenUsage(input, output)
    }

    /**
     * `account/rateLimits/updated`. Two schemas carry these numbers: camelCase over the
     * App Server and snake_case in the CLI's own rollout files - both are accepted, since
     * they have already diverged once. Percents are read as doubles because the rollout
     * schema writes them fractionally (`27.0`), which an integer parse would drop to null.
     */
    private fun parseRateLimits(params: JsonObject): Line {
        val root = params.obj("rateLimits") ?: params.obj("rate_limits") ?: params

        fun num(obj: JsonObject, vararg keys: String): Double? =
            keys.firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.doubleOrNull }

        fun window(key: String): CodexRateLimitWindow? {
            val obj = root.obj(key) ?: return null
            val pct = num(obj, "usedPercent", "used_percent")?.roundToInt()
            val minutes = num(obj, "windowDurationMins", "window_minutes", "windowMinutes")?.toInt()
            val resets = num(obj, "resetsAt", "resets_at")?.toLong()
            if (pct == null && resets == null) return null
            return CodexRateLimitWindow(key, pct, minutes, resets)
        }

        val windows = listOfNotNull(window("primary"), window("secondary"))
        return if (windows.isEmpty()) Line.Ignored else Line.RateLimits(windows)
    }

    private fun parseTurnCompleted(params: JsonObject): Line {
        val turn = params.obj("turn")
        val error = turn?.obj("error") ?: params.obj("error")
        return Line.TurnCompleted(
            turnId = turn?.str("id") ?: params.str("turnId"),
            status = turn?.str("status") ?: params.str("status"),
            errorMessage = error?.str("message") ?: params.str("message")
        )
    }

    private fun itemText(item: JsonObject): String {
        item.str("text")?.let { return it }
        item.str("summary")?.let { return it }
        val content = item["content"]
        if (content is JsonPrimitive) return content.contentOrNull.orEmpty()
        if (content is JsonArray) {
            return content.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                obj.str("text") ?: obj.str("summary")
            }.joinToString(System.lineSeparator() + System.lineSeparator())
        }
        return ""
    }

    private fun itemSummary(item: JsonObject): String {
        for (key in listOf("command", "path", "query", "name", "server")) {
            item.str(key)?.takeIf { it.isNotBlank() }?.let { return oneLine(it) }
        }
        return oneLine(item.toString())
    }

    private fun oneLine(value: String): String =
        value.lineSequence().joinToString(" ") { it.trim() }.trim().take(120)

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String): JsonObject? =
        this[key] as? JsonObject

    private fun JsonObject.intAny(vararg keys: String): Int {
        for (key in keys) {
            val value = (this[key] as? JsonPrimitive)?.intOrNull
            if (value != null) return value
        }
        return 0
    }
}

/**
 * One rate-limit window from `account/rateLimits/updated`. [id] is the window's slot
 * name (`primary` / `secondary`) - Codex does not name windows by duration, so
 * [windowMinutes] is the only reliable way to tell a weekly window from a session one.
 */
internal data class CodexRateLimitWindow(
    val id: String,
    val usedPercent: Int?,
    val windowMinutes: Int?,
    val resetsAtEpochSec: Long?
)