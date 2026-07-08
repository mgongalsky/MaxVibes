package com.maxvibes.application.service

import com.maxvibes.application.port.output.AgentStreamEvent
import com.maxvibes.application.port.output.AgentStreamSink
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Routes transport-emitted [AgentStreamEvent]s to UI listeners, attributing them to the
 * active chat session. Mirrors the ClaudeCodeSessionLogPort contract: the interaction
 * service calls [begin] before any transport call; the adapter never learns the chat
 * session id. Events arriving with no active session are dropped.
 *
 * Thread safety: [emit] arrives from the transport reader thread; listeners are invoked
 * on that thread and must dispatch to EDT themselves. Listener exceptions are swallowed.
 */
class AgentStreamHub : AgentStreamSink {

    fun interface Listener {
        fun onEvent(chatSessionId: String, event: AgentStreamEvent)
    }

    @Volatile
    private var activeSessionId: String? = null
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Marks [chatSessionId] as the active dialog for event attribution. */
    fun begin(chatSessionId: String) {
        activeSessionId = chatSessionId
    }

    override fun emit(event: AgentStreamEvent) {
        val sid = activeSessionId ?: return
        listeners.forEach { l ->
            try {
                l.onEvent(sid, event)
            } catch (_: Exception) {
                // Listener failures never break the transport reader.
            }
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }
}