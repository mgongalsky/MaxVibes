package com.maxvibes.plugin.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChatPanelCallbacksAdapterTest {

    @Test
    fun `normal system text is trimmed`() {
        assertEquals("Ready", normalizeSystemMessage("  Ready  "))
    }

    @Test
    fun `protocol noise and separators are hidden`() {
        assertNull(normalizeSystemMessage("────────"))
        assertNull(normalizeSystemMessage("Paste this into ChatGPT"))
        assertNull(normalizeSystemMessage("JSON copied"))
        assertNull(normalizeSystemMessage("📋 clipboard payload"))
    }
}
