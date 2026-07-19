package com.maxvibes.plugin.ui

import com.intellij.openapi.project.Project
import com.maxvibes.plugin.service.MaxVibesService
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ChatMessageControllerAttachmentTest {

    private val mockProject = mockk<Project>(relaxed = true)
    private val mockService = mockk<MaxVibesService>(relaxed = true)
    private val callbacks = mockk<ChatPanelCallbacks>(relaxed = true)
    private lateinit var controller: ChatMessageController

    @BeforeEach
    fun setup() {
        controller = ChatMessageController(mockProject, mockService, callbacks)
    }

    @Test
    fun `attachTrace stores trace content`() {
        controller.attachTrace("some trace")
        assertEquals("some trace", controller.attachedTrace)
    }

    @Test
    fun `attachTrace calls onAttachmentsChanged`() {
        controller.attachTrace("some trace")
        verify { callbacks.onAttachmentsChanged("some trace", null) }
    }

    @Test
    fun `clearTrace sets attachedTrace to null`() {
        controller.attachTrace("some trace")
        controller.clearTrace()
        assertNull(controller.attachedTrace)
    }

    @Test
    fun `clearTrace calls onAttachmentsChanged with null trace`() {
        controller.attachTrace("some trace")
        controller.clearTrace()
        verify { callbacks.onAttachmentsChanged(null, null) }
    }

    @Test
    fun `clearErrors sets attachedErrors to null`() {
        controller.clearErrors()
        assertNull(controller.attachedErrors)
    }

    @Test
    fun `clearErrors calls onAttachmentsChanged`() {
        controller.clearErrors()
        verify { callbacks.onAttachmentsChanged(null, null) }
    }

    @Test
    fun `clearAttachmentsAfterSend clears both attachments`() {
        controller.attachTrace("trace")
        controller.clearAttachmentsAfterSend()
        assertNull(controller.attachedTrace)
        assertNull(controller.attachedErrors)
    }

    @Test
    fun `clearAttachmentsAfterSend calls onAttachmentsChanged with nulls`() {
        controller.clearAttachmentsAfterSend()
        verify { callbacks.onAttachmentsChanged(null, null) }
    }

    @Test
    fun `attachedTrace is null by default`() {
        assertNull(controller.attachedTrace)
    }

    @Test
    fun `attachedErrors is null by default`() {
        assertNull(controller.attachedErrors)
    }
}
