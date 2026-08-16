package com.maxvibes.application.port.output

/**
 * One rate-limit window as reported by an agent.
 *
 * [id] is the agent's own window identifier and doubles as the merge key between
 * pull snapshots and push events. [windowMinutes] carries the window length as
 * data, so the UI can label a window it has never seen before instead of relying
 * on a fixed vocabulary; null when the agent only names its windows.
 * [status] is set by push events only - a null means "leave the current status".
 */
data class UsageWindow(
    val id: String,
    val windowMinutes: Int?,
    val utilizationPct: Int?,
    val resetsAtEpochSec: Long?,
    val name: String? = null,
    val status: String? = null
)

/** Account-level subscription usage snapshot: whichever windows the agent reports. */
data class SubscriptionUsage(
    val windows: List<UsageWindow>
)

/**
 * Source of exact subscription usage for agents that expose it over a request
 * rather than pushing it into their event stream. Implemented in the plugin layer
 * against the Claude CLI's own OAuth credentials; agents that push usage (Codex)
 * need no implementation at all. Implementations fail soft: any error yields null.
 */
interface SubscriptionUsagePort {
    /** Cheap check: credentials are present so polling makes sense at all. */
    fun isConfigured(): Boolean

    /** Fetches the current usage snapshot, or null when unavailable/unparseable. */
    suspend fun fetchUsage(): SubscriptionUsage?
}
