package com.maxvibes.application.port.output

/** One rate-limit window as reported by the usage endpoint. */
data class UsageWindow(
    val utilizationPct: Int?,
    val resetsAtEpochSec: Long?
)

/** Account-level subscription usage snapshot (windows may be individually absent). */
data class SubscriptionUsage(
    val fiveHour: UsageWindow?,
    val sevenDay: UsageWindow?,
    val sevenDayOpus: UsageWindow? = null
)

/**
 * Source of exact subscription usage (five_hour / seven_day utilization and reset
 * times). Implemented in the plugin layer against the Claude CLI's own OAuth
 * credentials; the CLI rate_limit_event stream stays a complementary source for
 * status escalation. Implementations fail soft: any error yields null.
 */
interface SubscriptionUsagePort {
    /** Cheap check: credentials are present so polling makes sense at all. */
    fun isConfigured(): Boolean

    /** Fetches the current usage snapshot, or null when unavailable/unparseable. */
    suspend fun fetchUsage(): SubscriptionUsage?
}
