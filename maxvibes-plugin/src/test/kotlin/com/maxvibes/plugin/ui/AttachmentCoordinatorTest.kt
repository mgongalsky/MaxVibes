package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.plugin.testsupport.FakeChatPanelCallbacks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AttachmentCoordinatorTest {
    private lateinit var callbacks: FakeChatPanelCallbacks
    private lateinit var coordinator: AttachmentCoordinator

    @BeforeEach
    fun setUp() {
        callbacks = FakeChatPanelCallbacks()
        coordinator = AttachmentCoordinator(
            context = PendingTurnContext(maxImages = 2),
            attachmentView = callbacks,
            inputStatusView = callbacks,
            maxImages = 2
        )
    }

    private fun image(id: String) = AttachedImage(
        mediaType = "image/png",
        base64Data = id
    )

    @Test
    fun `trace and errors changes are published together`() {
        coordinator.attachTrace("trace")
        coordinator.attachErrors("errors")
        coordinator.clearTrace()

        assertEquals("errors", coordinator.errors)
        assertNull(coordinator.trace)
        assertEquals(null to "errors", callbacks.attachmentsChanges.last())
    }

    @Test
    fun `image limit rejects overflow without publishing another image snapshot`() {
        assertTrue(coordinator.attachImage(image("first")))
        assertTrue(coordinator.attachImage(image("second")))
        val publishedCount = callbacks.imagesChanges.size

        assertFalse(coordinator.attachImage(image("overflow")))

        assertEquals(publishedCount, callbacks.imagesChanges.size)
        assertTrue(callbacks.statusUpdates.last().contains("Max 2"))
    }

    @Test
    fun `removeImage publishes only for valid index`() {
        val first = image("first")
        val second = image("second")
        coordinator.attachImage(first)
        coordinator.attachImage(second)
        val publishedCount = callbacks.imagesChanges.size

        coordinator.removeImage(10)
        assertEquals(publishedCount, callbacks.imagesChanges.size)

        coordinator.removeImage(0)
        assertEquals(listOf(second), callbacks.imagesChanges.last())
    }

    @Test
    fun `one-shot lifecycle updates its chip`() {
        coordinator.armOneShot("skill", "context", "Write test")
        coordinator.clearOneShot()

        assertEquals(listOf("Write test", null), callbacks.oneShotLabels)
    }

    @Test
    fun `consume returns independent snapshot and clears all state`() {
        val attachedImage = image("first")
        coordinator.attachTrace("trace")
        coordinator.attachErrors("errors")
        coordinator.attachImage(attachedImage)
        coordinator.armOneShot("skill", "context", "label")

        val snapshot = coordinator.consume()

        assertEquals("trace", snapshot.trace)
        assertEquals("errors", snapshot.errors)
        assertEquals(listOf(attachedImage), snapshot.images)
        assertEquals("skill", snapshot.oneShot?.skillName)
        assertNull(coordinator.trace)
        assertNull(coordinator.errors)
        assertEquals(null to null, callbacks.attachmentsChanges.last())
        assertEquals(emptyList<AttachedImage>(), callbacks.imagesChanges.last())
        assertEquals(null, callbacks.oneShotLabels.last())
    }

    @Test
    fun `clearAfterSend does not publish one-shot change when none is armed`() {
        coordinator.clearAfterSend()

        assertTrue(callbacks.oneShotLabels.isEmpty())
    }

    @Test
    fun `clearAfterSend publishes one-shot removal only once`() {
        coordinator.armOneShot("skill", "context", "label")

        coordinator.clearAfterSend()
        coordinator.clearAfterSend()

        assertEquals(listOf("label", null), callbacks.oneShotLabels)
        assertEquals(2, callbacks.attachmentsChanges.size)
        assertEquals(2, callbacks.imagesChanges.size)
    }

    @Test
    fun `explicit clearOneShot hides chip even when no one-shot is armed`() {
        coordinator.clearOneShot()

        assertEquals(listOf<String?>(null), callbacks.oneShotLabels)
    }

    @Test
    fun `clearImages clears state and publishes empty preview`() {
        coordinator.attachImage(image("first"))

        coordinator.clearImages()

        assertTrue(coordinator.snapshot().images.isEmpty())
        assertEquals(emptyList<AttachedImage>(), callbacks.imagesChanges.last())
    }

    @Test
    fun `successful image attachment publishes exact status and snapshot`() {
        val attached = image("first")

        assertTrue(coordinator.attachImage(attached))

        assertEquals(listOf(attached), callbacks.imagesChanges.single())
        assertEquals("🖼 Image attached (1)", callbacks.statusUpdates.single())
    }

    @Test
    fun `clearErrors preserves trace and publishes combined attachment state`() {
        coordinator.attachTrace("trace")
        coordinator.attachErrors("errors")

        coordinator.clearErrors()

        assertEquals("trace", coordinator.trace)
        assertNull(coordinator.errors)
        assertEquals("trace" to null, callbacks.attachmentsChanges.last())
    }

    @Test
    fun `snapshot preserves coordinator state and owns its image list`() {
        val first = image("first")
        coordinator.attachTrace("trace")
        coordinator.attachErrors("errors")
        coordinator.attachImage(first)

        val snapshot = coordinator.snapshot()
        coordinator.attachImage(image("second"))

        assertEquals("trace", coordinator.trace)
        assertEquals("errors", coordinator.errors)
        assertEquals(listOf(first), snapshot.images)
        assertEquals(2, coordinator.snapshot().images.size)
    }
}
