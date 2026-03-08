package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.plugin.settings.MaxVibesSettings
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class InteractionModeManagerTest {

    private val settings = mockk<MaxVibesSettings>(relaxed = true)
    private val modeChanges = mutableListOf<InteractionMode>()
    private fun createManager() = InteractionModeManager(
        settings = settings,
        onModeChanged = { modeChanges.add(it) }
    )

    @Test
    fun `initial mode is API`() {
        val manager = createManager()
        assertEquals(InteractionMode.API, manager.currentMode)
    }

    @Test
    fun `switchMode changes currentMode`() {
        val manager = createManager()
        manager.switchMode(InteractionMode.CLIPBOARD)
        assertEquals(InteractionMode.CLIPBOARD, manager.currentMode)
    }

    @Test
    fun `switchMode calls onModeChanged callback`() {
        val manager = createManager()
        manager.switchMode(InteractionMode.CLIPBOARD)
        assertEquals(1, modeChanges.size)
        assertEquals(InteractionMode.CLIPBOARD, modeChanges[0])
    }

    @Test
    fun `switchMode does not call callback if mode unchanged`() {
        val manager = createManager()
        manager.switchMode(InteractionMode.API) // same as initial
        assertEquals(0, modeChanges.size)
    }

    @Test
    fun `isClipboardMode returns true only for CLIPBOARD`() {
        val manager = createManager()
        assertFalse(manager.isClipboardMode())
        manager.switchMode(InteractionMode.CLIPBOARD)
        assertTrue(manager.isClipboardMode())
    }

    @Test
    fun `isApiMode returns true for API and CHEAP_API`() {
        val manager = createManager()
        assertTrue(manager.isApiMode()) // API by default
        manager.switchMode(InteractionMode.CHEAP_API)
        assertTrue(manager.isApiMode())
        manager.switchMode(InteractionMode.CLIPBOARD)
        assertFalse(manager.isApiMode())
    }

    @Test
    fun `isCheapApiMode returns true only for CHEAP_API`() {
        val manager = createManager()
        assertFalse(manager.isCheapApiMode())
        manager.switchMode(InteractionMode.CHEAP_API)
        assertTrue(manager.isCheapApiMode())
    }

    @Test
    fun `syncFromSettings applies CLIPBOARD mode from settings`() {
        every { settings.interactionMode } returns "CLIPBOARD"
        val manager = createManager()
        manager.syncFromSettings()
        assertEquals(InteractionMode.CLIPBOARD, manager.currentMode)
    }

    @Test
    fun `syncFromSettings applies CHEAP_API mode from settings`() {
        every { settings.interactionMode } returns "CHEAP_API"
        val manager = createManager()
        manager.syncFromSettings()
        assertEquals(InteractionMode.CHEAP_API, manager.currentMode)
    }

    @Test
    fun `syncFromSettings applies API mode when interactionMode is API`() {
        every { settings.interactionMode } returns "API"
        val manager = createManager()
        manager.syncFromSettings()
        assertEquals(InteractionMode.API, manager.currentMode)
    }

    @Test
    fun `syncFromSettings falls back to API on invalid value`() {
        every { settings.interactionMode } returns "INVALID_VALUE"
        val manager = createManager()
        manager.syncFromSettings()
        assertEquals(InteractionMode.API, manager.currentMode)
    }

    @Test
    fun `switchMode from API to CLIPBOARD then back to API`() {
        val manager = createManager()
        manager.switchMode(InteractionMode.CLIPBOARD)
        manager.switchMode(InteractionMode.API)
        assertEquals(InteractionMode.API, manager.currentMode)
        assertEquals(2, modeChanges.size)
    }
}
