package com.maxvibes.plugin.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ThumbnailScaleTest {

    @Test
    @DisplayName("an image smaller than the box keeps its size")
    fun `does not upscale`() {
        assertEquals(ThumbnailSize(30, 20), ThumbnailScale.fit(30, 20))
    }

    @Test
    @DisplayName("an image exactly the box size is untouched")
    fun `exact fit`() {
        assertEquals(ThumbnailSize(40, 40), ThumbnailScale.fit(40, 40))
    }

    @Test
    fun `square image scales down to the box`() {
        assertEquals(ThumbnailSize(40, 40), ThumbnailScale.fit(80, 80))
    }

    @Test
    fun `landscape image is bounded by its width`() {
        assertEquals(ThumbnailSize(40, 20), ThumbnailScale.fit(200, 100))
    }

    @Test
    fun `portrait image is bounded by its height`() {
        assertEquals(ThumbnailSize(20, 40), ThumbnailScale.fit(100, 200))
    }

    @Test
    @DisplayName("an extremely elongated image never collapses to zero height")
    fun `clamps the short side to one pixel`() {
        assertEquals(ThumbnailSize(40, 1), ThumbnailScale.fit(1000, 3))
    }

    @Test
    @DisplayName("a zero dimension yields a valid size instead of throwing")
    fun `survives a zero dimension`() {
        assertEquals(ThumbnailSize(1, 1), ThumbnailScale.fit(0, 0))
    }

    @Test
    fun `honours a custom box size`() {
        assertEquals(ThumbnailSize(100, 50), ThumbnailScale.fit(200, 100, maxSize = 100))
    }
}
