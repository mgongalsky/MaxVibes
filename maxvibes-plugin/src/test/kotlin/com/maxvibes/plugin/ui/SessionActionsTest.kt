package com.maxvibes.plugin.ui

import com.maxvibes.application.service.ChatTreeService
import com.maxvibes.domain.model.chat.ChatSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SessionActionsTest {
    private val chatTreeService = mockk<ChatTreeService>(relaxed = true)
    private val changedSessions = mutableListOf<ChatSession?>()
    private val renamedSessions = mutableListOf<ChatSession>()
    private lateinit var actions: SessionActions

    @BeforeEach
    fun setUp() {
        changedSessions.clear()
        renamedSessions.clear()
        actions = SessionActions(
            chatTreeService = chatTreeService,
            onSessionChanged = changedSessions::add,
            onSessionRenamed = renamedSessions::add
        )
    }

    @Test
    fun `createNewSession publishes the created session`() {
        val session = ChatSession()
        every { chatTreeService.createNewSession() } returns session

        actions.createNewSession()

        verify { chatTreeService.createNewSession() }
        assertEquals(listOf(session), changedSessions)
    }

    @Test
    fun `deleteCurrentSession deletes and publishes the next active session`() {
        val next = ChatSession()
        every { chatTreeService.getActiveSession() } returns next

        actions.deleteCurrentSession("session-id")

        verify { chatTreeService.deleteSession("session-id") }
        assertEquals(listOf(next), changedSessions)
    }

    @Test
    fun `renameSession publishes successful rename`() {
        val renamed = ChatSession(title = "New title")
        every { chatTreeService.renameSession("session-id", "New title") } returns renamed

        actions.renameSession("session-id", "New title")

        assertEquals(listOf(renamed), renamedSessions)
    }

    @Test
    fun `renameSession ignores unknown session`() {
        every { chatTreeService.renameSession(any(), any()) } returns null

        actions.renameSession("missing", "New title")

        assertTrue(renamedSessions.isEmpty())
    }

    @Test
    fun `branchSession publishes successful branch`() {
        val branch = ChatSession(title = "Branch")
        every { chatTreeService.createBranch("parent", "Branch") } returns branch

        actions.branchSession("parent", "Branch")

        assertEquals(listOf(branch), changedSessions)
    }

    @Test
    fun `branchSession ignores unknown parent`() {
        every { chatTreeService.createBranch(any(), any()) } returns null

        actions.branchSession("missing", "Branch")

        assertTrue(changedSessions.isEmpty())
    }

    @Test
    fun `loadSession activates and publishes known session`() {
        val session = ChatSession()
        every { chatTreeService.getSessionById(session.id) } returns session

        actions.loadSession(session.id)

        verify { chatTreeService.setActiveSession(session.id) }
        assertEquals(listOf(session), changedSessions)
    }

    @Test
    fun `loadSession activates but does not publish unknown session`() {
        every { chatTreeService.getSessionById("missing") } returns null

        actions.loadSession("missing")

        verify { chatTreeService.setActiveSession("missing") }
        assertTrue(changedSessions.isEmpty())
    }

    @Test
    fun `selectSpecificPrompt saves and publishes updated active session`() {
        // withSelectedPrompt refreshes updatedAt (Instant.now()), so full-equality
        // matching against a reference copy is flaky — capture and compare fields.
        val session = ChatSession()
        every { chatTreeService.getActiveSession() } returns session

        actions.selectSpecificPrompt("write-unittest")

        val saved = slot<ChatSession>()
        verify { chatTreeService.saveSession(capture(saved)) }
        assertEquals("write-unittest", saved.captured.selectedSpecificPromptName)
        assertEquals(session.id, saved.captured.id)
        assertEquals(listOf<ChatSession?>(saved.captured), changedSessions)
    }

    @Test
    fun `selectSpecificPrompt supports Just Code null selection`() {
        val session = ChatSession().withSelectedPrompt("write-unittest")
        every { chatTreeService.getActiveSession() } returns session

        actions.selectSpecificPrompt(null)

        val saved = slot<ChatSession>()
        verify { chatTreeService.saveSession(capture(saved)) }
        assertNull(saved.captured.selectedSpecificPromptName)
        assertEquals(session.id, saved.captured.id)
        assertEquals(listOf<ChatSession?>(saved.captured), changedSessions)
    }
}
