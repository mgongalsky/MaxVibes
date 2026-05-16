package com.maxvibes.application.service

import com.maxvibes.domain.model.interaction.ClaudeCodeActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory store + observer hub for transient Claude Code live activity.
 *
 * Lifecycle:
 *  - [update] is called by [ClaudeCodeInteractionService] when its `onActivity`
 *    callback fires (from the transport thread).
 *  - [clear] is called by the service at the end of every send, in a `finally` block.
 *  - [currentFor] is polled by the UI Swing Timer (~200ms) to drive throttled rendering.
 *  - [addListener] / [removeListener] are used by the UI to schedule a render-soon
 *    when activity changes.
 *
 * Thread safety: all mutating operations and listener notifications are safe to
 * call from any thread. Listeners are invoked synchronously on the calling thread —
 * UI listeners must dispatch to EDT themselves (typically via `SwingUtilities.invokeLater`).
 *
 * Per-session isolation: the tracker holds independent state per sessionId so
 * parallel sends in different chat sessions cannot stomp each other.
 */
class ClaudeCodeActivityTracker {

    fun interface Listener {
        fun onChanged(sessionId: String, activity: ClaudeCodeActivity?)
    }

    private val activities = ConcurrentHashMap<String, ClaudeCodeActivity>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Stores or replaces the current activity for [sessionId] and notifies listeners. */
    fun update(sessionId: String, activity: ClaudeCodeActivity) {
        activities[sessionId] = activity
        notify(sessionId, activity)
    }

    /** Removes any activity for [sessionId] and notifies listeners with `null`. */
    fun clear(sessionId: String) {
        val removed = activities.remove(sessionId)
        if (removed != null) notify(sessionId, null)
    }

    /** Returns the current activity for [sessionId], or null if none is active. */
    fun currentFor(sessionId: String): ClaudeCodeActivity? = activities[sessionId]

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notify(sessionId: String, activity: ClaudeCodeActivity?) {
        // CopyOnWriteArrayList iterator is safe even if a listener self-removes.
        listeners.forEach { listener ->
            try {
                listener.onChanged(sessionId, activity)
            } catch (_: Exception) {
                // Listener exceptions never break other listeners or the transport.
            }
        }
    }
}
