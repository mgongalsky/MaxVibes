package com.maxvibes.plugin.ui

import com.maxvibes.application.port.output.AgentStreamEvent
import com.maxvibes.application.port.output.SessionStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import javax.swing.Timer

class LiveTurnPanelTest {
    private fun completed() = AgentStreamEvent.Completed("done", SessionStats(0.0, 1, 1, 0, 0))

    // Drain deterministically on EDT; no sleeps or dependence on Swing timer scheduling.
    private fun drain(panel: LiveTurnPanel) {
        LiveTurnPanel::class.java.getDeclaredMethod("tick").apply { isAccessible = true }.invoke(panel)
    }

    private fun timer(panel: LiveTurnPanel): Timer =
        LiveTurnPanel::class.java.getDeclaredField("drainTimer").apply { isAccessible = true }.get(panel) as Timer

    @Test
    fun `late stderr and deltas cannot reopen completed turn`() {
        SwingUtilities.invokeAndWait {
            val panel = LiveTurnPanel({}, { _, _ -> error("Unexpected failure") })
            try {
                panel.onEvent(AgentStreamEvent.SessionStarted("one", "model"))
                drain(panel)
                assertTrue(panel.isVisible)
                panel.onEvent(completed())
                panel.onEvent(AgentStreamEvent.Notice("stderr: worker quit"))
                panel.onEvent(AgentStreamEvent.NarrationDelta("old", "late", false))
                drain(panel)
                drain(panel)
                assertFalse(panel.isVisible)
                assertFalse(timer(panel).isRunning)
            } finally { panel.dispose() }
        }
    }

    @Test
    fun `queued next turn starts after previous completion`() {
        SwingUtilities.invokeAndWait {
            val panel = LiveTurnPanel({}, { _, _ -> })
            try {
                panel.onEvent(AgentStreamEvent.SessionStarted("one", "model"))
                panel.onEvent(completed())
                panel.onEvent(AgentStreamEvent.Notice("late diagnostic"))
                panel.onEvent(AgentStreamEvent.SessionStarted("two", "model"))
                panel.onEvent(AgentStreamEvent.NarrationDelta("new", "working", false))
                drain(panel)
                drain(panel)
                assertTrue(panel.isVisible)
                panel.onEvent(completed())
                drain(panel)
                assertFalse(panel.isVisible)
                assertFalse(timer(panel).isRunning)
            } finally { panel.dispose() }
        }
    }

    @Test
    fun `failure flushes once and late events stay hidden`() {
        SwingUtilities.invokeAndWait {
            val flushed = mutableListOf<Pair<String, String>>()
            val panel = LiveTurnPanel({}, { text, reason -> flushed.add(text to reason) })
            try {
                panel.onEvent(AgentStreamEvent.SessionStarted("one", "model"))
                panel.onEvent(AgentStreamEvent.NarrationDelta("message", "partial", false))
                panel.onEvent(AgentStreamEvent.Failed("failed", null))
                panel.onEvent(AgentStreamEvent.Failed("duplicate", null))
                panel.onEvent(AgentStreamEvent.Notice("stderr"))
                drain(panel)
                drain(panel)
                assertEquals(listOf("partial" to "failed"), flushed)
                assertFalse(panel.isVisible)
                assertFalse(timer(panel).isRunning)
            } finally { panel.dispose() }
        }
    }

    @Test
    fun `telemetry and notices do not activate idle panel and disposal rejects events`() {
        SwingUtilities.invokeAndWait {
            val panel = LiveTurnPanel({}, { _, _ -> })
            try {
                panel.onEvent(AgentStreamEvent.RateLimitUpdate("week", "allowed", 16, null))
                panel.onEvent(AgentStreamEvent.Notice("diagnostic"))
                drain(panel)
                assertFalse(panel.isVisible)
                assertFalse(timer(panel).isRunning)
                panel.dispose()
                panel.onEvent(AgentStreamEvent.SessionStarted("late", "model"))
                drain(panel)
                assertFalse(panel.isVisible)
                assertFalse(timer(panel).isRunning)
            } finally { panel.dispose() }
        }
    }

    @Test
    fun `controller completion closes panel without transport terminal event`() {
        SwingUtilities.invokeAndWait {
            val panel = LiveTurnPanel({}, { _, _ -> error("Result is already handled by controller") })
            try {
                panel.updateActivity(ChatActivity.RUNNING)
                panel.onEvent(AgentStreamEvent.SessionStarted("one", "auto"))
                panel.onEvent(AgentStreamEvent.Notice("stderr: worker quit"))
                drain(panel)
                assertTrue(panel.isVisible)
                // These events have not yet reached the Swing timer.
                panel.onEvent(AgentStreamEvent.SessionStarted("queued", "auto"))
                panel.onEvent(AgentStreamEvent.NarrationDelta("old", "queued text", false))
                panel.updateActivity(ChatActivity.FINISHED)
                assertFalse(panel.isVisible)
                assertFalse(timer(panel).isRunning)
                // Even a delayed start cannot resurrect a controller-completed operation.
                panel.onEvent(AgentStreamEvent.SessionStarted("late", "auto"))
                panel.onEvent(AgentStreamEvent.Notice("late stderr"))
                drain(panel)
                assertFalse(panel.isVisible)
                assertFalse(timer(panel).isRunning)
            } finally {
                panel.dispose()
            }
        }
    }

    @Test
    fun `new work reopens stream after controller close and repeated running preserves it`() {
        SwingUtilities.invokeAndWait {
            val panel = LiveTurnPanel({}, { _, _ -> })
            try {
                for (closed in listOf(
                    ChatActivity.FINISHED,
                    ChatActivity.WAITING,
                    ChatActivity.ERROR,
                    ChatActivity.IDLE
                )) {
                    panel.updateActivity(closed)
                    panel.onEvent(AgentStreamEvent.SessionStarted("ignored", "auto"))
                    drain(panel)
                    assertFalse(panel.isVisible)
                    panel.updateActivity(ChatActivity.RUNNING)
                    panel.onEvent(AgentStreamEvent.SessionStarted("next", "auto"))
                    panel.onEvent(AgentStreamEvent.NarrationDelta("new", "working", false))
                    panel.updateActivity(ChatActivity.RUNNING)
                    drain(panel)
                    assertTrue(panel.isVisible)
                }
                panel.updateActivity(ChatActivity.FINISHED)
                assertFalse(panel.isVisible)
                assertFalse(timer(panel).isRunning)
            } finally {
                panel.dispose()
            }
        }
    }
}
