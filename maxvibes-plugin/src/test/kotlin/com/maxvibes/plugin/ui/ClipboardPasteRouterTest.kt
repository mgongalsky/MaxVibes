package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.AttachedImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.IOException

class ClipboardPasteRouterTest {

    private val screenshot = AttachedImage(mediaType = "image/png", base64Data = "c2NyZWVuc2hvdA==")
    private val longText = "x".repeat(500)

    @Test
    fun `screenshot on the clipboard becomes an image attachment`() {
        val route = router().route(clipboard(DataFlavor.imageFlavor to bitmap()), autoAttachText = true)

        assertEquals(PasteRoute.AttachImage(screenshot), route)
    }

    @Test
    fun `image wins over text when the clipboard carries both`() {
        val route = router(readText = { longText }).route(
            clipboard(DataFlavor.imageFlavor to bitmap(), DataFlavor.stringFlavor to longText),
            autoAttachText = true
        )

        assertEquals(PasteRoute.AttachImage(screenshot), route)
    }

    @Test
    fun `large text without an image becomes a text attachment`() {
        val route = router(readText = { longText }).route(
            clipboard(DataFlavor.stringFlavor to longText),
            autoAttachText = true
        )

        assertEquals(PasteRoute.AttachText(longText), route)
    }

    @Test
    fun `short text is pasted normally`() {
        val route = router(readText = { "hi" }).route(
            clipboard(DataFlavor.stringFlavor to "hi"),
            autoAttachText = true
        )

        assertEquals(PasteRoute.PassThrough, route)
    }

    @Test
    fun `suppressed auto attach still lets an image through`() {
        val route = router().route(clipboard(DataFlavor.imageFlavor to bitmap()), autoAttachText = false)

        assertEquals(PasteRoute.AttachImage(screenshot), route)
    }

    @Test
    fun `suppressed auto attach skips large text`() {
        val route = router(readText = { longText }).route(
            clipboard(DataFlavor.stringFlavor to longText),
            autoAttachText = false
        )

        assertEquals(PasteRoute.PassThrough, route)
    }

    @Test
    fun `missing clipboard content is pasted normally`() {
        assertEquals(PasteRoute.PassThrough, router().route(null, autoAttachText = true))
    }

    @Test
    fun `undecodable image falls back to the text branch`() {
        val route = router(decodeImage = { null }, readText = { longText }).route(
            clipboard(DataFlavor.imageFlavor to bitmap(), DataFlavor.stringFlavor to longText),
            autoAttachText = true
        )

        assertEquals(PasteRoute.AttachText(longText), route)
    }

    @Test
    fun `crashing image decoder does not lose the paste`() {
        val route = router(
            decodeImage = { throw IllegalStateException("decoder exploded") },
            readText = { longText }
        ).route(clipboard(DataFlavor.imageFlavor to bitmap()), autoAttachText = true)

        assertEquals(PasteRoute.AttachText(longText), route)
    }

    @Test
    fun `unreadable image data does not break the paste`() {
        val route = router().route(
            clipboard(DataFlavor.imageFlavor to bitmap(), failing = setOf(DataFlavor.imageFlavor)),
            autoAttachText = true
        )

        assertEquals(PasteRoute.PassThrough, route)
    }

    @Test
    fun `failing flavor probe does not break the paste`() {
        val route = router().route(clipboard(probeFails = true), autoAttachText = true)

        assertEquals(PasteRoute.PassThrough, route)
    }

    @Test
    fun `failing text read does not break the paste`() {
        val route = router(readText = { throw IOException("clipboard busy") }).route(
            clipboard(DataFlavor.stringFlavor to longText),
            autoAttachText = true
        )

        assertEquals(PasteRoute.PassThrough, route)
    }

    private fun router(
        decodeImage: (Image) -> AttachedImage? = { screenshot },
        readText: (Transferable) -> String? = { null },
        shouldAttachText: (String) -> Boolean = { it.length >= 100 }
    ) = ClipboardPasteRouter(decodeImage, readText, shouldAttachText)

    private fun clipboard(
        vararg payload: Pair<DataFlavor, Any>,
        failing: Set<DataFlavor> = emptySet(),
        probeFails: Boolean = false
    ): Transferable = FakeTransferable(payload.toMap(), failing, probeFails)

    private fun bitmap(): BufferedImage = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
}

private class FakeTransferable(
    private val payload: Map<DataFlavor, Any>,
    private val failing: Set<DataFlavor>,
    private val probeFails: Boolean
) : Transferable {

    override fun getTransferDataFlavors(): Array<DataFlavor> = payload.keys.toTypedArray()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean {
        if (probeFails) throw IllegalStateException("clipboard is owned by another application")
        return payload.containsKey(flavor)
    }

    override fun getTransferData(flavor: DataFlavor): Any {
        if (flavor in failing) throw IOException("clipboard data unavailable")
        return payload[flavor] ?: throw UnsupportedFlavorException(flavor)
    }
}
