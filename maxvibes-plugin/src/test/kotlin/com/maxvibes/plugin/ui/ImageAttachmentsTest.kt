package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.AttachedImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO

class ImageAttachmentsTest {

    @Test
    fun `raw awt image is encoded as png`() {
        val attached = ImageAttachments.fromAwtImage(image(120, 80))

        assertEquals("image/png", attached.mediaType)
        assertEquals(120, decode(attached).width)
        assertEquals(80, decode(attached).height)
    }

    @Test
    fun `oversized image is downscaled to the api limit`() {
        val decoded = decode(ImageAttachments.fromAwtImage(image(3000, 1500)))

        assertEquals(1568, decoded.width)
        assertEquals(784, decoded.height)
    }

    @Test
    fun `image within the limit keeps its size`() {
        val decoded = decode(ImageAttachments.fromAwtImage(image(800, 600)))

        assertEquals(800, decoded.width)
        assertEquals(600, decoded.height)
    }

    @Test
    fun `thumbnail fits the requested box`() {
        val icon = requireNotNull(ImageAttachments.thumbnail(ImageAttachments.fromAwtImage(image(400, 200))))

        assertEquals(160, icon.iconWidth)
        assertEquals(80, icon.iconHeight)
    }

    @Test
    fun `thumbnail of an undecodable payload is null`() {
        val broken = AttachedImage(
            mediaType = "image/png",
            base64Data = Base64.getEncoder().encodeToString("not an image".toByteArray())
        )

        assertNull(ImageAttachments.thumbnail(broken))
    }

    @Test
    fun `small png file is attached without re-encoding`() {
        val file = pngFile(image(50, 50))

        val attached = requireNotNull(ImageAttachments.fromDiskFile(file))

        assertEquals("image/png", attached.mediaType)
        assertEquals(Base64.getEncoder().encodeToString(file.readBytes()), attached.base64Data)
    }

    @Test
    fun `oversized png file is re-encoded within the limit`() {
        val decoded = decode(requireNotNull(ImageAttachments.fromDiskFile(pngFile(image(3000, 1500)))))

        assertEquals(1568, decoded.width)
        assertEquals(784, decoded.height)
    }

    @Test
    fun `non image file is not attached`() {
        val file = Files.createTempFile("maxvibes-test-", ".txt").toFile()
        file.deleteOnExit()
        file.writeText("plain text, not a screenshot")

        assertNull(ImageAttachments.fromDiskFile(file))
    }

    private fun image(width: Int, height: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { buffered ->
            val graphics = buffered.createGraphics()
            graphics.color = Color.BLUE
            graphics.fillRect(0, 0, width, height)
            graphics.dispose()
        }

    private fun pngFile(source: BufferedImage): File =
        Files.createTempFile("maxvibes-test-", ".png").toFile().also { file ->
            file.deleteOnExit()
            ImageIO.write(source, "png", file)
        }

    private fun decode(attached: AttachedImage): BufferedImage =
        ImageIO.read(Base64.getDecoder().decode(attached.base64Data).inputStream())
}
