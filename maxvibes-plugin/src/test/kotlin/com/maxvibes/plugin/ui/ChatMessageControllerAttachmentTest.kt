package com.maxvibes.plugin.ui

import com.intellij.openapi.project.Project
import com.maxvibes.plugin.service.MaxVibesService
import com.maxvibes.plugin.testsupport.FakeChatPanelCallbacks
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ChatMessageControllerAttachmentTest {

    private val mockProject = mockk<Project>(relaxed = true)
    private val mockService = mockk<MaxVibesService>(relaxed = true)
    private lateinit var callbacks: FakeChatPanelCallbacks
    private lateinit var controller: ChatMessageController

    @BeforeEach
    fun setup() {
        callbacks = FakeChatPanelCallbacks()
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
        assertEquals(Pair("some trace", null), callbacks.attachmentsChanges.last())
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
        assertEquals(Pair(null, null), callbacks.attachmentsChanges.last())
    }

    @Test
    fun `clearErrors sets attachedErrors to null`() {
        controller.clearErrors()
        assertNull(controller.attachedErrors)
    }

    @Test
    fun `clearErrors calls onAttachmentsChanged`() {
        controller.clearErrors()
        assertEquals(Pair(null, null), callbacks.attachmentsChanges.last())
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
        assertEquals(Pair(null, null), callbacks.attachmentsChanges.last())
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
