package com.maxvibes.plugin.claudecode

import com.intellij.ide.util.PropertiesComponent
import com.maxvibes.application.port.output.SubscriptionUsage
import com.maxvibes.application.port.output.UsageWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Persists the last successful Claude usage snapshot; OAuth credentials are never stored here. */
class ClaudeUsageSnapshotCache(
    private val properties: PropertiesComponent = PropertiesComponent.getInstance()
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): SubscriptionUsage? {
        val raw = properties.getValue(KEY) ?: return null
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val windows = (root["windows"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            UsageWindow(
                id = id,
                windowMinutes = obj.intOrNull("windowMinutes"),
                utilizationPct = obj.intOrNull("utilizationPct"),
                resetsAtEpochSec = obj.longOrNull("resetsAtEpochSec"),
                name = obj.stringOrNull("name"),
                status = null
            )
        }
        return windows.takeIf { it.isNotEmpty() }?.let(::SubscriptionUsage)
    }

    fun save(usage: SubscriptionUsage) {
        val payload = buildJsonObject {
            put("windows", buildJsonArray {
                usage.windows.forEach { window ->
                    add(buildJsonObject {
                        put("id", JsonPrimitive(window.id))
                        putNullable("windowMinutes", window.windowMinutes)
                        putNullable("utilizationPct", window.utilizationPct)
                        putNullable("resetsAtEpochSec", window.resetsAtEpochSec)
                        putNullable("name", window.name)
                    })
                }
            })
        }
        properties.setValue(KEY, payload.toString())
    }

    private fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.longOrNull(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Number?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private companion object {
        private const val KEY = "maxvibes.claude.usage.snapshot.v1"
    }
}
