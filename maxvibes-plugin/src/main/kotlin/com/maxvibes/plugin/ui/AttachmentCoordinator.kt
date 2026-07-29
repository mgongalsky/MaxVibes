package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.AttachedImage

/**
 * Owns pending one-turn attachments and keeps their UI representation in sync.
 */
internal class AttachmentCoordinator(
    private val context: PendingTurnContext,
    private val attachmentView: AttachmentView,
    private val inputStatusView: InputStatusView,
    private val maxImages: Int
) {
    val trace: String?
        get() = context.trace

    val errors: String?
        get() = context.errors

    fun snapshot(): PendingTurnSnapshot = context.snapshot()

    /** Returns the current snapshot and clears all one-turn state and UI chips. */
    fun consume(): PendingTurnSnapshot {
        val snapshot = context.snapshot()
        clearAfterSend()
        return snapshot
    }

    fun attachTrace(traceContent: String) {
        context.attachTrace(traceContent)
        publishTextAttachments()
    }

    fun clearTrace() {
        context.clearTrace()
        publishTextAttachments()
    }

    fun attachErrors(errorsContent: String) {
        context.attachErrors(errorsContent)
        publishTextAttachments()
    }

    fun clearErrors() {
        context.clearErrors()
        publishTextAttachments()
    }

    fun attachImage(image: AttachedImage): Boolean {
        if (!context.attachImage(image)) {
            inputStatusView.setStatus("🖼 Max $maxImages images per message")
            return false
        }

        val images = context.imagesSnapshot()
        attachmentView.onImagesChanged(images)
        inputStatusView.setStatus("🖼 Image attached (${images.size})")
        return true
    }

    fun clearImages() {
        context.clearImages()
        attachmentView.onImagesChanged(emptyList())
    }

    fun removeImage(index: Int) {
        if (context.removeImage(index)) {
            attachmentView.onImagesChanged(context.imagesSnapshot())
        }
    }

    fun armOneShot(skillName: String?, elementContext: String?, label: String) {
        context.armOneShot(skillName, elementContext, label)
        attachmentView.onOneShotChanged(label)
    }

    fun clearOneShot() {
        context.clearOneShot()
        attachmentView.onOneShotChanged(null)
    }

    fun clearAfterSend() {
        val hadOneShot = context.clearAll()
        attachmentView.onAttachmentsChanged(null, null)
        attachmentView.onImagesChanged(emptyList())
        if (hadOneShot) attachmentView.onOneShotChanged(null)
    }

    private fun publishTextAttachments() {
        attachmentView.onAttachmentsChanged(context.trace, context.errors)
    }
}
