package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.AttachedImage

internal data class PendingOneShot(
    val skillName: String?,
    val elementContext: String?,
    val label: String
)

internal data class PendingTurnSnapshot(
    val trace: String?,
    val errors: String?,
    val images: List<AttachedImage>,
    val oneShot: PendingOneShot?
)

/**
 * Mutable one-turn context consumed by the next send or approve action.
 *
 * This class deliberately has no IntelliJ or UI dependencies. It owns only the
 * pending trace, IDE errors, images and one-shot editor skill. Callers decide how
 * state changes are rendered and which mode-specific warnings should be shown.
 */
internal class PendingTurnContext(
    private val maxImages: Int
) {
    var trace: String? = null
        private set

    var errors: String? = null
        private set

    private val images = mutableListOf<AttachedImage>()

    var oneShot: PendingOneShot? = null
        private set

    fun attachTrace(value: String) {
        trace = value
    }

    fun clearTrace() {
        trace = null
    }

    fun attachErrors(value: String) {
        errors = value
    }

    fun clearErrors() {
        errors = null
    }

    fun attachImage(image: AttachedImage): Boolean {
        if (images.size >= maxImages) return false
        images.add(image)
        return true
    }

    fun removeImage(index: Int): Boolean {
        if (index !in images.indices) return false
        images.removeAt(index)
        return true
    }

    fun clearImages() {
        images.clear()
    }

    fun imagesSnapshot(): List<AttachedImage> = images.toList()

    fun armOneShot(skillName: String?, elementContext: String?, label: String) {
        oneShot = PendingOneShot(skillName, elementContext, label)
    }

    fun clearOneShot(): Boolean {
        val wasArmed = oneShot != null
        oneShot = null
        return wasArmed
    }

    fun snapshot(): PendingTurnSnapshot = PendingTurnSnapshot(
        trace = trace,
        errors = errors,
        images = images.toList(),
        oneShot = oneShot
    )

    /** Clears all one-turn state and reports whether a one-shot chip was armed. */
    fun clearAll(): Boolean {
        val hadOneShot = oneShot != null
        trace = null
        errors = null
        images.clear()
        oneShot = null
        return hadOneShot
    }
}
