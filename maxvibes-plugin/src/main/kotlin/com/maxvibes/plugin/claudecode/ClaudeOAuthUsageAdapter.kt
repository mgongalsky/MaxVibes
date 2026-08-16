package com.maxvibes.plugin.claudecode

import com.maxvibes.application.port.output.SubscriptionUsage
import com.maxvibes.application.port.output.SubscriptionUsagePort
import com.maxvibes.application.port.output.UsageWindow
import com.maxvibes.plugin.service.MaxVibesLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * [SubscriptionUsagePort] over the Claude CLI's own OAuth session.
 *
 * Reads the access token from `~/.claude/.credentials.json` (the file the CLI
 * itself maintains and refreshes) and calls the usage endpoint the official
 * statusline tooling uses. UNOFFICIAL endpoint: it may change or vanish without
 * notice, so everything here fails soft - any error returns null and the usage
 * bars keep living on CLI rate_limit_events alone.
 *
 * Security: the token is never logged, never persisted anywhere else, and is
 * sent only to api.anthropic.com over HTTPS. Response bodies contain no secrets,
 * so short previews may be logged for schema diagnostics.
 *
 * Schema tolerance: `utilization` accepted as percent or 0..1 fraction;
 * `resets_at`/`resetsAt` accepted as epoch seconds, epoch millis, or ISO-8601;
 * window objects located at top level or one level deep.
 */
class ClaudeOAuthUsageAdapter : SubscriptionUsagePort {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private fun credentialsPath(): Path =
        Paths.get(System.getProperty("user.home"), ".claude", ".credentials.json")

    override fun isConfigured(): Boolean = Files.isRegularFile(credentialsPath())

    override suspend fun fetchUsage(): SubscriptionUsage? {
        val token = readAccessToken() ?: return null
        val body = try {
            runInterruptible(Dispatchers.IO) {
                val request = HttpRequest.newBuilder(URI.create(USAGE_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer $token")
                    .header("anthropic-beta", OAUTH_BETA)
                    .header("anthropic-version", "2023-06-01")
                    .header("User-Agent", "claude-code")
                    .header("x-app", "cli")
                    .header("Accept", "application/json")
                    .GET()
                    .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) {
                    MaxVibesLogger.warn(
                        TAG, "usage endpoint non-200",
                        data = mapOf(
                            "status" to response.statusCode(),
                            "bodyPreview" to response.body().take(200)
                        )
                    )
                    null
                } else {
                    response.body()
                }
            }
        } catch (e: Exception) {
            MaxVibesLogger.warn(TAG, "usage fetch failed", ex = e)
            null
        } ?: return null

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (root == null) {
            MaxVibesLogger.info(
                TAG, "usage response not a JSON object",
                mapOf("preview" to body.take(300))
            )
            return null
        }
        val windows = listOfNotNull(
            window(root, "five_hour", windowMinutes = 300),
            window(root, "seven_day", windowMinutes = 10_080),
            window(root, "seven_day_opus", windowMinutes = 10_080, name = "Opus")
        )
        if (windows.isEmpty()) {
            MaxVibesLogger.info(
                TAG, "usage schema unrecognized",
                mapOf("preview" to body.take(300))
            )
            return null
        }
        return SubscriptionUsage(windows)
    }

    /** Token from the CLI credentials file; null (with a quiet log) when unusable. */
    private fun readAccessToken(): String? {
        val path = credentialsPath()
        if (!Files.isRegularFile(path)) return null
        val root = runCatching { json.parseToJsonElement(Files.readString(path)).jsonObject }
            .getOrNull() ?: return null
        val oauth = (root["claudeAiOauth"] as? JsonObject) ?: root
        val token = (oauth["accessToken"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = (oauth["expiresAt"] as? JsonPrimitive)?.longOrNull
        if (expiresAt != null && expiresAt < System.currentTimeMillis()) {
            // The CLI refreshes this file itself whenever it runs; just skip this cycle.
            MaxVibesLogger.info(TAG, "oauth token expired - waiting for CLI to refresh it")
            return null
        }
        return token
    }

    /** Extracts one window; tolerant to naming and nesting variations. */
    private fun window(
        root: JsonObject,
        key: String,
        windowMinutes: Int,
        name: String? = null
    ): UsageWindow? {
        val obj = locate(root, key) ?: return null
        val rawUtilization = (obj["utilization"] as? JsonPrimitive)?.doubleOrNull
        val pct = rawUtilization?.let { if (it > 0.0 && it < 1.0) (it * 100).toInt() else it.toInt() }
        val resets = parseResets(obj["resets_at"] ?: obj["resetsAt"])
        if (pct == null && resets == null) return null
        return UsageWindow(
            id = key,
            windowMinutes = windowMinutes,
            utilizationPct = pct,
            resetsAtEpochSec = resets,
            name = name
        )
    }

    /** Finds an object under [key] at top level or nested one level deep. */
    private fun locate(root: JsonObject, key: String): JsonObject? {
        (root[key] as? JsonObject)?.let { return it }
        for ((_, value) in root) {
            val nested = (value as? JsonObject)?.get(key) as? JsonObject
            if (nested != null) return nested
        }
        return null
    }

    /** resets_at as epoch seconds, epoch millis, or ISO-8601 string. */
    private fun parseResets(el: JsonElement?): Long? {
        val prim = el as? JsonPrimitive ?: return null
        prim.longOrNull?.let { return if (it > 1_000_000_000_000L) it / 1000 else it }
        val s = prim.contentOrNull ?: return null
        return runCatching { OffsetDateTime.parse(s).toEpochSecond() }.getOrNull()
            ?: runCatching { Instant.parse(s).epochSecond }.getOrNull()
    }

    private companion object {
        private const val TAG = "OAuthUsage"
        private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
        private const val OAUTH_BETA = "oauth-2025-04-20"
    }
}
