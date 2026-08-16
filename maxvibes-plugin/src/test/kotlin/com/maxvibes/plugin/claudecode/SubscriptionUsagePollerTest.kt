package com.maxvibes.plugin.claudecode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SubscriptionUsagePollerTest {

    private val base = 300_000L
    private val max = 1_800_000L

    @Test
    fun `backoff doubles the wait and stops at the cap`() {
        assertEquals(600_000L, backedOff(base, base, max))
        assertEquals(1_200_000L, backedOff(600_000L, base, max))
        assertEquals(max, backedOff(1_200_000L, base, max))
        assertEquals(max, backedOff(max, base, max))
    }

    @Test
    fun `backoff never drops below the base interval`() {
        assertEquals(600_000L, backedOff(1_000L, base, max))
    }
}
