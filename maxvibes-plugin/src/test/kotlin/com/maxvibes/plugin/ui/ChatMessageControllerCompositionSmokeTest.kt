package com.maxvibes.plugin.ui

import com.intellij.openapi.project.Project
import com.maxvibes.application.service.ChatTreeService
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.plugin.service.MaxVibesService
import com.maxvibes.plugin.testsupport.FakeChatPanelCallbacks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChatMessageControllerCompositionSmokeTest {
    @Test
    fun `construction does not initialize the lazy service graph`() {
        val project = mockk<Project>(relaxed = true)
        val service = mockk<MaxVibesService>(relaxed = true)

        ChatMessageController(project, service, FakeChatPanelCallbacks())

        verifyLazyServicesWereNotRead(service)
    }

    @Test
    fun `attachment-only facade path remains independent from application services`() {
        val project = mockk<Project>(relaxed = true)
        val service = mockk<MaxVibesService>(relaxed = true)
        val callbacks = FakeChatPanelCallbacks()
        val controller = ChatMessageController(project, service, callbacks)
        val image = AttachedImage(
            mediaType = "image/png",
            base64Data = "image-data"
        )

        controller.attachTrace("trace")
        controller.attachImage(image)
        controller.armOneShot("skill", "class Example", "Write test")

        assertEquals("trace", controller.attachedTrace)
        assertEquals(listOf(image), callbacks.imagesChanges.last())
        assertEquals("Write test", callbacks.oneShotLabels.last())

        controller.clearAttachmentsAfterSend()

        assertNull(controller.attachedTrace)
        assertNull(controller.attachedErrors)
        assertEquals(null to null, callbacks.attachmentsChanges.last())
        assertEquals(emptyList<AttachedImage>(), callbacks.imagesChanges.last())
        assertEquals(null, callbacks.oneShotLabels.last())
        verifyLazyServicesWereNotRead(service)
    }

    @Test
    fun `session facade initializes only ChatTreeService`() {
        val project = mockk<Project>(relaxed = true)
        val service = mockk<MaxVibesService>(relaxed = true)
        val chatTreeService = mockk<ChatTreeService>(relaxed = true)
        val callbacks = FakeChatPanelCallbacks()
        val created = ChatSession(title = "Created")
        every { service.chatTreeService } returns chatTreeService
        every { chatTreeService.createNewSession() } returns created
        val controller = ChatMessageController(project, service, callbacks)

        controller.createNewSession()

        verify(exactly = 1) { service.chatTreeService }
        verify(exactly = 1) { chatTreeService.createNewSession() }
        assertEquals(listOf(created), callbacks.sessionChanges)
        verifyHeavyServicesWereNotRead(service)
    }

    @Test
    fun `selected prompt passes through public facade and composition wiring`() {
        val project = mockk<Project>(relaxed = true)
        val service = mockk<MaxVibesService>(relaxed = true)
        val chatTreeService = mockk<ChatTreeService>(relaxed = true)
        val callbacks = FakeChatPanelCallbacks()
        val session = ChatSession(title = "Session")
        every { service.chatTreeService } returns chatTreeService
        every { chatTreeService.getActiveSession() } returns session
        val controller = ChatMessageController(project, service, callbacks)

        controller.selectSpecificPrompt("write-unittest")

        verify {
            chatTreeService.saveSession(
                match {
                    it.id == session.id &&
                            it.title == session.title &&
                            it.selectedSpecificPromptName == "write-unittest"
                }
            )
        }
        assertEquals("write-unittest", callbacks.sessionChanges.single()?.selectedSpecificPromptName)
        verifyHeavyServicesWereNotRead(service)
    }

    @Test
    fun `compatibility task formatter is independent from controller construction`() {
        val separator = System.lineSeparator()

        val result = ChatMessageController.buildTaskWithContext(
            task = "task",
            trace = "trace",
            errs = "errors"
        )

        assertEquals(
            "task" + separator + separator +
                    "--- Error/Trace/Logs ---" + separator + "trace" +
                    separator + separator +
                    "--- IDE Errors ---" + separator + "errors",
            result
        )
    }

    private fun verifyLazyServicesWereNotRead(service: MaxVibesService) {
        verify(exactly = 0) { service.chatTreeService }
        verifyHeavyServicesWereNotRead(service)
    }

    private fun verifyHeavyServicesWereNotRead(service: MaxVibesService) {
        verify(exactly = 0) { service.notificationService }
        verify(exactly = 0) { service.clipboardService }
        verify(exactly = 0) { service.claudeCodeService }
        verify(exactly = 0) { service.ideErrorsPort }
        verify(exactly = 0) { service.executeCommandUseCase }
        verify(exactly = 0) { service.specificPromptService }
        verify(exactly = 0) { service.contextAwareModifyUseCase }
        verify(exactly = 0) { service.cheapContextAwareModifyUseCase }
    }
}
