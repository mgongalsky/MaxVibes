package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
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
    fun `clipboardStatus defaults to IDLE`() {
        val state = ChatPanelState(currentSession = null)
        assertEquals(ClipboardSessionStatus.IDLE, state.clipboardStatus)
    }

    @Test
    fun `copy creates modified state without changing original`() {
        val original = ChatPanelState(currentSession = null, clipboardStatus = ClipboardSessionStatus.IDLE)
        val waiting = original.copy(clipboardStatus = ClipboardSessionStatus.AWAITING_PASTE)
        assertEquals(ClipboardSessionStatus.IDLE, original.clipboardStatus)
        assertEquals(ClipboardSessionStatus.AWAITING_PASTE, waiting.clipboardStatus)
    }
}
