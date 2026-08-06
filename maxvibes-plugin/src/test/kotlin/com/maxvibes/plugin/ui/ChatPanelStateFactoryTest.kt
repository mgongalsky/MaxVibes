package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatPanelStateFactoryTest {

    @Test
    fun `build collects session mode attachments prompts and context`() {
        val session = ChatSession(
            id = "session",
            clipboardStatus = ClipboardSessionStatus.AWAITING_APPROVE,
            selectedSpecificPromptName = "review"
        )
        val factory = ChatPanelStateFactory(
            activeSession = { session },
            sessionPath = { listOf(session) },
            currentMode = { InteractionMode.CLAUDE_CODE },
            attachedTrace = { "trace" },
            attachedErrors = { "errors" },
            contextFilesCount = { 3 },
            availablePrompts = { listOf("review", "tests") },
            validatePromptName = { it }
        )

        val state = factory.build()

        assertEquals(session, state.currentSession)
        assertEquals(listOf(session), state.sessionPath)
        assertEquals(InteractionMode.CLAUDE_CODE, state.mode)
        assertEquals("trace", state.attachedTrace)
        assertEquals("errors", state.attachedErrors)
        assertEquals(3, state.contextFilesCount)
        assertEquals(listOf("review", "tests"), state.availablePrompts)
        assertEquals("review", state.selectedSpecificPromptName)
        assertTrue(state.claudeCodeApproveVisible)
    }

    @Test
    fun `approve remains hidden outside Claude Code mode and invalid prompt is cleared`() {
        val session = ChatSession(
            clipboardStatus = ClipboardSessionStatus.AWAITING_APPROVE,
            selectedSpecificPromptName = "missing"
        )
        val factory = ChatPanelStateFactory(
            activeSession = { session },
            sessionPath = { emptyList() },
            currentMode = { InteractionMode.API },
            attachedTrace = { null },
            attachedErrors = { null },
            contextFilesCount = { 0 },
            availablePrompts = { emptyList() },
            validatePromptName = { null }
        )

        val state = factory.build()

        assertFalse(state.claudeCodeApproveVisible)
        assertEquals(null, state.selectedSpecificPromptName)
    }
}
