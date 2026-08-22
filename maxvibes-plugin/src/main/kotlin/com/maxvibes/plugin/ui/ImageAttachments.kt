package com.maxvibes.plugin.ui

import com.intellij.openapi.project.Project
import com.maxvibes.domain.model.interaction.AttachedImage
import java.awt.Color
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Capture and normalization of images attached to a chat message (Claude Code mode).
 *
 * Rules: PNG/JPEG only; if the longest side exceeds [MAX_SIDE] or the raw file is
 * larger than [MAX_RAW_BYTES], the image is downscaled to [MAX_SIDE] and re-encoded
 * as PNG; otherwise original bytes pass through untouched.
 */
object ImageAttachments {

    const val MAX_IMAGES = 3

    /** Anthropic resizes anything larger anyway — bigger images only burn tokens. */
    private const val MAX_SIDE = 1568

    /** Safety margin under the API's ~5MB per-image limit. */
    private const val MAX_RAW_BYTES = 4_000_000

    /** Image from the system clipboard: a raw image or a copied PNG/JPEG file. Null otherwise. */
    fun fromClipboard(): AttachedImage? = try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        when {
            clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) ->
                (clipboard.getData(DataFlavor.imageFlavor) as? Image)?.let { fromAwtImage(it) }

            clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) -> {
                @Suppress("UNCHECKED_CAST")
                val files = clipboard.getData(DataFlavor.javaFileListFlavor) as? List<java.io.File>
                files?.firstNotNullOfOrNull { fromDiskFile(it) }
            }

            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /** Image from an on-disk file (drag-and-drop / copied file). Null if not a readable PNG/JPEG. */
    fun fromDiskFile(file: java.io.File): AttachedImage? = try {
        val ext = file.extension.lowercase()
        if (ext !in setOf("png", "jpg", "jpeg")) null else fromBytes(file.readBytes(), ext)
    } catch (e: Exception) {
        null
    }

    /** Image from a raw AWT image (clipboard or DnD image flavor). */
    fun fromAwtImage(img: Image): AttachedImage = encode(toBuffered(img))

    private fun fromBytes(bytes: ByteArray, ext: String): AttachedImage? {
        val buffered = ImageIO.read(bytes.inputStream()) ?: return null
        val needsRework = maxOf(buffered.width, buffered.height) > MAX_SIDE || bytes.size > MAX_RAW_BYTES
        return if (!needsRework) AttachedImage(
            mediaType = if (ext == "png") "image/png" else "image/jpeg",
            base64Data = Base64.getEncoder().encodeToString(bytes)
        ) else encode(buffered)
    }

    /** Scaled thumbnail fitting a maxW x maxH box; null if the payload is not decodable. */
    fun thumbnail(image: AttachedImage, maxW: Int = 160, maxH: Int = 96): javax.swing.ImageIcon? = try {
        val bytes = Base64.getDecoder().decode(image.base64Data)
        val buffered = ImageIO.read(bytes.inputStream())
        if (buffered == null) null else {
            val k = minOf(maxW.toDouble() / buffered.width, maxH.toDouble() / buffered.height, 1.0)
            if (k >= 1.0) javax.swing.ImageIcon(buffered)
            else javax.swing.ImageIcon(
                buffered.getScaledInstance(
                    (buffered.width * k).toInt().coerceAtLeast(1),
                    (buffered.height * k).toInt().coerceAtLeast(1),
                    Image.SCALE_SMOOTH
                )
            )
        }
    } catch (e: Exception) {
        null
    }

    /** Opens the original in IntelliJ's image editor via a temp file (zoom, pan — for free). */
    fun openInViewer(project: Project, image: AttachedImage) {
        try {
            val ext = if (image.mediaType == "image/jpeg") ".jpg" else ".png"
            val tmp = java.nio.file.Files.createTempFile("maxvibes-img-", ext)
            java.nio.file.Files.write(tmp, Base64.getDecoder().decode(image.base64Data))
            tmp.toFile().deleteOnExit()
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmp.toFile())
            if (vf != null) com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
        } catch (e: Exception) {
            // best-effort viewer
        }
    }

    // ── internals ──

    private fun toBuffered(img: Image): BufferedImage {
        if (img is BufferedImage) return img
        val b = BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_RGB)
        val g = b.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, b.width, b.height)
        g.drawImage(img, 0, 0, null)
        g.dispose()
        return b
    }

    /** Downscale to [MAX_SIDE] when needed, encode as PNG, base64. */
    private fun encode(src: BufferedImage): AttachedImage {
        val scaled = scaleDown(src)
        val out = ByteArrayOutputStream()
        ImageIO.write(scaled, "png", out)
        return AttachedImage(
            mediaType = "image/png",
            base64Data = Base64.getEncoder().encodeToString(out.toByteArray())
        )
    }

    private fun scaleDown(src: BufferedImage): BufferedImage {
        val longest = maxOf(src.width, src.height)
        if (longest <= MAX_SIDE) return src
        val k = MAX_SIDE.toDouble() / longest
        // Truncation loses a pixel whenever the ratio has no exact binary form,
        // so the longest side would never actually reach MAX_SIDE.
        val w = (src.width * k).roundToInt().coerceAtLeast(1)
        val h = (src.height * k).roundToInt().coerceAtLeast(1)
        val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = dst.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return dst
    }
}
