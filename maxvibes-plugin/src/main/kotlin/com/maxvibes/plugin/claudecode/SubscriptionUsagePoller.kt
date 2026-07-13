package com.maxvibes.plugin.claudecode

import com.maxvibes.application.port.output.SubscriptionUsage
import com.maxvibes.application.port.output.SubscriptionUsagePort
import com.maxvibes.plugin.service.MaxVibesLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Periodic driver for [SubscriptionUsagePort]: polls every [intervalMs] (first
 * poll after [initialDelayMs]) and hands each non-null snapshot to [onUsage].
 * [onUsage] is invoked on an IO dispatcher thread - consumers hop to EDT
 * themselves (LimitsBarPanel already does). Owns its own scope; [stop] is
 * terminal. Cheap no-op cycles while the port reports not configured, so the
 * poller survives the credentials file appearing later.
 */
class SubscriptionUsagePoller(
    private val port: SubscriptionUsagePort,
    private val intervalMs: Long = 60_000L,
    private val initialDelayMs: Long = 2_000L,
    private val onUsage: (SubscriptionUsage) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            delay(initialDelayMs)
            while (isActive) {
                pollOnce()
                delay(intervalMs)
            }
        }
    }

    /** One out-of-schedule poll (e.g. right after a turn burned some quota). */
    fun pollNow() {
        if (started) scope.launch { pollOnce() }
    }

    private suspend fun pollOnce() {
        try {
            if (!port.isConfigured()) return
            port.fetchUsage()?.let(onUsage)
        } catch (e: Exception) {
            MaxVibesLogger.warn(TAG, "usage poll failed", ex = e)
        }
    }

    /** Terminal: cancels the polling scope. */
    fun stop() {
        scope.cancel()
    }

    private companion object {
        private const val TAG = "UsagePoller"
    }
}
