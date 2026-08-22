package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.AttachedImage
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

/** What a paste into the chat input should do with the clipboard payload. */
internal sealed interface PasteRoute {
    data class AttachImage(val image: AttachedImage) : PasteRoute
    data class AttachText(val text: String) : PasteRoute
    object PassThrough : PasteRoute
}

/**
 * Decides what a paste means, free of Swing and of the IntelliJ action system.
 *
 * Image attachment silently disappeared twice because this decision lived inside the paste
 * plumbing and could only be verified by hand. Collaborators are injectable so that every
 * clipboard shape is reachable from a plain unit test.
 */
internal class ClipboardPasteRouter(
    private val decodeImage: (Image) -> AttachedImage? = { ImageAttachments.fromAwtImage(it) },
    private val readText: (Transferable) -> String? = { TextClipboardAttachments.readText(it) },
    private val shouldAttachText: (String) -> Boolean = { TextClipboardAttachments.shouldAutoAttach(it) }
) {

    /**
     * An image always wins over text: a screenshot on the clipboard usually carries both flavors.
     * [autoAttachText] only gates the text branch — programmatic edits must never swallow images.
     */
    fun route(transferable: Transferable?, autoAttachText: Boolean): PasteRoute {
        if (transferable == null) return PasteRoute.PassThrough
        imageOf(transferable)?.let { return PasteRoute.AttachImage(it) }
        if (!autoAttachText) return PasteRoute.PassThrough
        val text = runCatching { readText(transferable) }.getOrNull() ?: return PasteRoute.PassThrough
        return if (shouldAttachText(text)) PasteRoute.AttachText(text) else PasteRoute.PassThrough
    }

    private fun imageOf(transferable: Transferable): AttachedImage? {
        val supported = runCatching { transferable.isDataFlavorSupported(DataFlavor.imageFlavor) }
            .getOrDefault(false)
        if (!supported) return null
        return runCatching { transferable.getTransferData(DataFlavor.imageFlavor) as? Image }
            .getOrNull()
            ?.let { image -> runCatching { decodeImage(image) }.getOrNull() }
    }
}
