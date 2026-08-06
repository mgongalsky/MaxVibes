package com.maxvibes.plugin.ui

import com.maxvibes.application.service.AgentStreamHub
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatRuntimeCoordinatorTest {

    @Test
    fun `start and dispose are idempotent`() {
        var startCount = 0
        var stopCount = 0
        val coordinator = ChatRuntimeCoordinator(
            streamHub = AgentStreamHub(),
            activeSessionId = { "session" },
            onActiveEvent = {},
            onRateLimit = {},
            startUsagePolling = { startCount++ },
            stopUsagePolling = { stopCount++ }
        )

        coordinator.start()
        coordinator.start()
        coordinator.dispose()
        coordinator.dispose()

        assertEquals(1, startCount)
        assertEquals(1, stopCount)
    }
}
