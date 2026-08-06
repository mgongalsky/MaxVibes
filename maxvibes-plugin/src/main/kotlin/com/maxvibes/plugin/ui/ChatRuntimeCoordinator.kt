package com.maxvibes.plugin.ui

import com.maxvibes.application.port.output.AgentStreamEvent
import com.maxvibes.application.service.AgentStreamHub
import com.maxvibes.plugin.service.MaxVibesLogger

/** Owns the live stream subscription and subscription-usage polling lifecycle. */
class ChatRuntimeCoordinator(
    private val streamHub: AgentStreamHub,
    private val activeSessionId: () -> String,
    private val onActiveEvent: (AgentStreamEvent) -> Unit,
    private val onRateLimit: (AgentStreamEvent.RateLimitUpdate) -> Unit,
    private val startUsagePolling: () -> Unit,
    private val stopUsagePolling: () -> Unit
) {

    private var started = false

    private val listener = AgentStreamHub.Listener { sessionId, event ->
        if (sessionId == activeSessionId()) onActiveEvent(event)
        if (event is AgentStreamEvent.RateLimitUpdate) {
            MaxVibesLogger.info(
                "ChatPanel",
                "limits event",
                mapOf(
                    "kind" to event.kind,
                    "pct" to (event.utilizationPct ?: -1),
                    "status" to event.status
                )
            )
            onRateLimit(event)
        }
    }

    fun start() {
        if (started) return
        started = true
        streamHub.addListener(listener)
        startUsagePolling()
    }

    fun dispose() {
        if (!started) return
        started = false
        streamHub.removeListener(listener)
        stopUsagePolling()
    }
}
