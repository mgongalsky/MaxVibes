package com.maxvibes.plugin.ui

import com.intellij.openapi.project.Project
import com.maxvibes.application.service.ChatTreeService
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.plugin.service.MaxVibesService
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ChatMessageControllerSessionTest {

    private val mockProject = mockk<Project>(relaxed = true)
    private val mockChatTreeService = mockk<ChatTreeService>(relaxed = true)
    private val mockService = mockk<MaxVibesService>(relaxed = true)
    private val mockCallbacks = mockk<ChatPanelCallbacks>(relaxed = true)
    private lateinit var controller: ChatMessageController

    @BeforeEach
    fun setup() {
        every { mockService.chatTreeService } returns mockChatTreeService
        controller = ChatMessageController(mockProject, mockService, mockCallbacks)
    }

    @Test
    fun `createNewSession calls chatTreeService createNewSession`() {
        val session = ChatSession()
        every { mockChatTreeService.createNewSession() } returns session

        controller.createNewSession()

        verify { mockChatTreeService.createNewSession() }
    }

    @Test
    fun `createNewSession calls onSessionChanged callback with new session`() {
        val session = ChatSession()
        every { mockChatTreeService.createNewSession() } returns session

        controller.createNewSession()

        verify { mockCallbacks.onSessionChanged(session) }
    }

    @Test
    fun `deleteCurrentSession calls chatTreeService deleteSession`() {
        val next = ChatSession()
        every { mockChatTreeService.getActiveSession() } returns next

        controller.deleteCurrentSession("session-id")

        verify { mockChatTreeService.deleteSession("session-id") }
    }

    @Test
    fun `deleteCurrentSession calls onSessionChanged with next active session`() {
        val next = ChatSession()
        every { mockChatTreeService.getActiveSession() } returns next

        controller.deleteCurrentSession("session-id")

        verify { mockCallbacks.onSessionChanged(next) }
    }

    @Test
    fun `renameSession calls chatTreeService renameSession`() {
        every { mockChatTreeService.renameSession("session-id", "New Title") } returns ChatSession()

        controller.renameSession("session-id", "New Title")

        verify { mockChatTreeService.renameSession("session-id", "New Title") }
    }

    @Test
    fun `renameSession calls onSessionRenamed callback`() {
        val renamed = ChatSession(title = "New Title")
        every { mockChatTreeService.renameSession("session-id", "New Title") } returns renamed

        controller.renameSession("session-id", "New Title")

        verify { mockCallbacks.onSessionRenamed(renamed) }
    }

    @Test
    fun `renameSession does not call onSessionRenamed when chatTreeService returns null`() {
        every { mockChatTreeService.renameSession(any(), any()) } returns null

        controller.renameSession("session-id", "New Title")

        verify(exactly = 0) { mockCallbacks.onSessionRenamed(any()) }
    }

    @Test
    fun `branchSession calls chatTreeService createBranch`() {
        every { mockChatTreeService.createBranch("parent-id", "Branch Title") } returns ChatSession()

        controller.branchSession("parent-id", "Branch Title")

        verify { mockChatTreeService.createBranch("parent-id", "Branch Title") }
    }

    @Test
    fun `branchSession calls onSessionChanged with new branch`() {
        val branch = ChatSession(title = "Branch Title")
        every { mockChatTreeService.createBranch("parent-id", "Branch Title") } returns branch

        controller.branchSession("parent-id", "Branch Title")

        verify { mockCallbacks.onSessionChanged(branch) }
    }

    @Test
    fun `loadSession sets active session and calls onSessionChanged`() {
        val session = ChatSession()
        every { mockChatTreeService.getSessionById(session.id) } returns session

        controller.loadSession(session.id)

        verify { mockChatTreeService.setActiveSession(session.id) }
        verify { mockCallbacks.onSessionChanged(session) }
    }

    @Test
    fun `loadSession does not call onSessionChanged when session not found`() {
        every { mockChatTreeService.getSessionById(any()) } returns null

        controller.loadSession("nonexistent-id")

        verify(exactly = 0) { mockCallbacks.onSessionChanged(any()) }
    }
}
