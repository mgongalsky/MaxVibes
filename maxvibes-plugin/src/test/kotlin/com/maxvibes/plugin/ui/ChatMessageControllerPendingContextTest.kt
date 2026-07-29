package com.maxvibes.plugin.ui

import com.intellij.openapi.project.Project
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.plugin.service.MaxVibesService
import com.maxvibes.plugin.testsupport.FakeChatPanelCallbacks
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ChatMessageControllerPendingContextTest {

    private val project = mockk<Project>(relaxed = true)
    private val service = mockk<MaxVibesService>(relaxed = true)
    private lateinit var callbacks: FakeChatPanelCallbacks
    private lateinit var controller: ChatMessageController

    @BeforeEach
    fun setUp() {
        callbacks = FakeChatPanelCallbacks()
        controller = ChatMessageController(project, service, callbacks)
    }

    private fun image(id: String) = AttachedImage(
        mediaType = "image/png",
        base64Data = id
    )

    @Test
    fun `attachImage publishes an independent snapshot after every attachment`() {
        val first = image("first")
        val second = image("second")

        assertTrue(controller.attachImage(first))
        val firstSnapshot = callbacks.imagesChanges.single()

        assertTrue(controller.attachImage(second))

        assertEquals(listOf(first), firstSnapshot)
        assertEquals(listOf(first, second), callbacks.imagesChanges.last())
    }

    @Test
    fun `attachImage rejects images above the per-message limit`() {
        repeat(ImageAttachments.MAX_IMAGES) { index ->
            assertTrue(controller.attachImage(image("image-$index")))
        }

        val acceptedChangeCount = callbacks.imagesChanges.size
        val accepted = controller.attachImage(image("overflow"))

        assertFalse(accepted)
        assertEquals(acceptedChangeCount, callbacks.imagesChanges.size)
        assertTrue(
            callbacks.statusUpdates.last().contains(
                "Max ${ImageAttachments.MAX_IMAGES}"
            )
        )
    }

    @Test
    fun `removeImage removes a valid index and publishes the remaining images`() {
        val first = image("first")
        val second = image("second")
        controller.attachImage(first)
        controller.attachImage(second)

        controller.removeImage(0)

        assertEquals(listOf(second), callbacks.imagesChanges.last())
    }

    @Test
    fun `removeImage ignores an invalid index without publishing a change`() {
        controller.attachImage(image("first"))
        val changeCount = callbacks.imagesChanges.size

        controller.removeImage(10)

        assertEquals(changeCount, callbacks.imagesChanges.size)
    }

    @Test
    fun `clearImages publishes an empty image list`() {
        controller.attachImage(image("first"))

        controller.clearImages()

        assertEquals(emptyList<AttachedImage>(), callbacks.imagesChanges.last())
    }

    @Test
    fun `armOneShot publishes its label and clearOneShot publishes null`() {
        controller.armOneShot(
            skillName = "write-unittest",
            elementContext = "class Example",
            label = "Write unit test"
        )

        controller.clearOneShot()

        assertEquals(listOf("Write unit test", null), callbacks.oneShotLabels)
    }

    @Test
    fun `clearAttachmentsAfterSend clears images attachments and armed one-shot`() {
        controller.attachTrace("trace")
        controller.attachImage(image("first"))
        controller.armOneShot(
            skillName = "write-unittest",
            elementContext = "class Example",
            label = "Write unit test"
        )

        controller.clearAttachmentsAfterSend()

        assertEquals(null to null, callbacks.attachmentsChanges.last())
        assertEquals(emptyList<AttachedImage>(), callbacks.imagesChanges.last())
        assertEquals(listOf("Write unit test", null), callbacks.oneShotLabels)
    }

    @Test
    fun `clearAttachmentsAfterSend does not publish one-shot change when none is armed`() {
        controller.clearAttachmentsAfterSend()

        assertTrue(callbacks.oneShotLabels.isEmpty())
    }
}
