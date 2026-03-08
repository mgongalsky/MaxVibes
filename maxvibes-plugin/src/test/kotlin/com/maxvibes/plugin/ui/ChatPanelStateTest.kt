package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.InteractionMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ChatPanelStateTest {

    @Test
    fun `default state has no session`() {
        val state = ChatPanelState(currentSession = null)
        assertNull(state.currentSession)
    }

    @Test
    fun `default mode is API`() {
        val state = ChatPanelState(currentSession = null)
        assertEquals(InteractionMode.API, state.mode)
    }

    @Test
    fun `hasAttachments is false when no attachments`() {
        val state = ChatPanelState(currentSession = null)
        assertFalse(state.hasAttachments)
    }

    @Test
    fun `hasAttachments is true when trace attached`() {
        val state = ChatPanelState(currentSession = null, attachedTrace = "some trace")
        assertTrue(state.hasAttachments)
    }

    @Test
    fun `hasAttachments is true when errors attached`() {
        val state = ChatPanelState(currentSession = null, attachedErrors = "some errors")
        assertTrue(state.hasAttachments)
    }

    @Test
    fun `isWaitingResponse defaults to false`() {
        val state = ChatPanelState(currentSession = null)
        assertFalse(state.isWaitingResponse)
    }

    @Test
    fun `copy creates modified state without changing original`() {
        val original = ChatPanelState(currentSession = null, isWaitingResponse = false)
        val waiting = original.copy(isWaitingResponse = true)
        assertFalse(original.isWaitingResponse)
        assertTrue(waiting.isWaitingResponse)
    }
}
