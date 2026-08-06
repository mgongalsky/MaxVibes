package com.maxvibes.plugin.ui

/** Pixel size of an attachment preview after fitting. */
data class ThumbnailSize(val width: Int, val height: Int)

/**
 * Fits an image into a square box, never upscaling: an image already smaller than the
 * box keeps its original size.
 */
object ThumbnailScale {

    const val MAX_SIZE = 40

    /**
     * Both dimensions are clamped to at least 1: a very elongated image scales its short
     * side below one pixel, and `toInt()` would otherwise truncate it to a zero-sized icon.
     *
     * A zero [width] or [height] makes the ratio infinite, which the 1.0 cap absorbs — the
     * result stays valid instead of throwing.
     */
    fun fit(width: Int, height: Int, maxSize: Int = MAX_SIZE): ThumbnailSize {
        val scale = minOf(
            maxSize.toDouble() / width.toDouble(),
            maxSize.toDouble() / height.toDouble(),
            1.0
        )
        return ThumbnailSize(
            width = maxOf(1, (width * scale).toInt()),
            height = maxOf(1, (height * scale).toInt())
        )
    }
}
