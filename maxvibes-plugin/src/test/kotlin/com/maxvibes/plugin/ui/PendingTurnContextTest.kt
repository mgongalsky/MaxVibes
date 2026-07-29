package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.AttachedImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PendingTurnContextTest {

    private fun image(id: String) = AttachedImage(
        mediaType = "image/png",
        base64Data = id
    )

    @Test
    fun `snapshot contains all pending values`() {
        val context = PendingTurnContext(maxImages = 3)
        val attachedImage = image("first")
        context.attachTrace("trace")
        context.attachErrors("errors")
        context.attachImage(attachedImage)
        context.armOneShot("write-unittest", "class Example", "Write test")

        val snapshot = context.snapshot()

        assertEquals("trace", snapshot.trace)
        assertEquals("errors", snapshot.errors)
        assertEquals(listOf(attachedImage), snapshot.images)
        assertEquals("write-unittest", snapshot.oneShot?.skillName)
        assertEquals("class Example", snapshot.oneShot?.elementContext)
        assertEquals("Write test", snapshot.oneShot?.label)
    }

    @Test
    fun `snapshot owns an independent image list`() {
        val context = PendingTurnContext(maxImages = 3)
        val first = image("first")
        context.attachImage(first)

        val snapshot = context.snapshot()
        context.attachImage(image("second"))

        assertEquals(listOf(first), snapshot.images)
    }

    @Test
    fun `attachImage enforces configured limit`() {
        val context = PendingTurnContext(maxImages = 1)

        assertTrue(context.attachImage(image("first")))
        assertFalse(context.attachImage(image("second")))
        assertEquals(1, context.imagesSnapshot().size)
    }

    @Test
    fun `removeImage changes state only for valid index`() {
        val context = PendingTurnContext(maxImages = 3)
        val first = image("first")
        val second = image("second")
        context.attachImage(first)
        context.attachImage(second)

        assertFalse(context.removeImage(10))
        assertTrue(context.removeImage(0))
        assertEquals(listOf(second), context.imagesSnapshot())
    }

    @Test
    fun `clearAll clears every value and reports armed one-shot`() {
        val context = PendingTurnContext(maxImages = 3)
        context.attachTrace("trace")
        context.attachErrors("errors")
        context.attachImage(image("first"))
        context.armOneShot("skill", "context", "label")

        assertTrue(context.clearAll())

        assertNull(context.trace)
        assertNull(context.errors)
        assertTrue(context.imagesSnapshot().isEmpty())
        assertNull(context.oneShot)
        assertFalse(context.clearAll())
    }
}
