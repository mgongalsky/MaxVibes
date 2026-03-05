package com.maxvibes.domain.model.chat

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TokenUsageTest {

    @Test
    fun `empty TokenUsage has zero totals`() {
        val usage = TokenUsage()
        assertEquals(0, usage.total)
        assertEquals(0, usage.totalInput)
        assertEquals(0, usage.totalOutput)
        assertTrue(usage.isEmpty())
    }

    @Test
    fun `addPlanning accumulates correctly`() {
        val usage = TokenUsage().addPlanning(100, 50)
        assertEquals(100, usage.planningInput)
        assertEquals(50, usage.planningOutput)
        assertEquals(150, usage.total)
        assertFalse(usage.isEmpty())
    }

    @Test
    fun `addChat accumulates correctly`() {
        val usage = TokenUsage().addChat(200, 80)
        assertEquals(200, usage.chatInput)
        assertEquals(80, usage.chatOutput)
        assertEquals(280, usage.total)
    }

    @Test
    fun `multiple additions accumulate`() {
        val usage = TokenUsage()
            .addPlanning(100, 50)
            .addChat(200, 80)
            .addChat(300, 120)
        assertEquals(100, usage.planningInput)
        assertEquals(50, usage.planningOutput)
        assertEquals(500, usage.chatInput)
        assertEquals(200, usage.chatOutput)
        assertEquals(850, usage.total)
    }

    @Test
    fun `totalInput sums planning and chat input`() {
        val usage = TokenUsage(planningInput = 100, chatInput = 200)
        assertEquals(300, usage.totalInput)
    }

    @Test
    fun `totalOutput sums planning and chat output`() {
        val usage = TokenUsage(planningOutput = 50, chatOutput = 80)
        assertEquals(130, usage.totalOutput)
    }

    @Test
    fun `formatDisplay returns empty string for zero usage`() {
        assertEquals("", TokenUsage.EMPTY.formatDisplay())
    }

    @Test
    fun `formatDisplay shows planning section when planning tokens present`() {
        val usage = TokenUsage(planningInput = 1000, planningOutput = 500)
        val display = usage.formatDisplay()
        assertTrue(display.contains("Plan:"))
        assertTrue(display.contains("1k"))
    }

    @Test
    fun `formatDisplay shows chat section when chat tokens present`() {
        val usage = TokenUsage(chatInput = 2000, chatOutput = 800)
        val display = usage.formatDisplay()
        assertTrue(display.contains("Chat:"))
        assertFalse(display.contains("Plan:"))
    }

    @Test
    fun `formatDisplay shows both sections when both present`() {
        val usage = TokenUsage(planningInput = 1000, planningOutput = 500, chatInput = 2000, chatOutput = 800)
        val display = usage.formatDisplay()
        assertTrue(display.contains("Plan:"))
        assertTrue(display.contains("Chat:"))
        assertTrue(display.contains("\$"))
    }

    @Test
    fun `formatDisplay calculates cost correctly`() {
        val usage = TokenUsage(chatInput = 1_000_000)
        val display = usage.formatDisplay(inputCostPerMillion = 3.0, outputCostPerMillion = 15.0)
        assertTrue(display.contains("\$3.000"))
    }

    @Test
    fun `TokenUsage is immutable - addPlanning returns new instance`() {
        val original = TokenUsage(planningInput = 100)
        val updated = original.addPlanning(50, 25)
        assertEquals(100, original.planningInput)
        assertEquals(150, updated.planningInput)
    }

    @Test
    fun `EMPTY companion is zero`() {
        assertEquals(TokenUsage(), TokenUsage.EMPTY)
        assertTrue(TokenUsage.EMPTY.isEmpty())
    }
}
