package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.plugin.settings.MaxVibesSettings

interface InteractionModeState {
    val currentMode: InteractionMode
    fun switchMode(newMode: InteractionMode)
    fun syncFromSettings()
}

/**
 * Manages the persisted interaction mode state.
 */
class InteractionModeManager(
    private val settings: MaxVibesSettings,
    private val onModeChanged: (InteractionMode) -> Unit
) : InteractionModeState {

    override var currentMode: InteractionMode = InteractionMode.API
        private set

    override fun switchMode(newMode: InteractionMode) {
        if (currentMode == newMode) return
        currentMode = newMode
        onModeChanged(newMode)
    }

    override fun syncFromSettings() {
        val savedMode = readModeFromSettings()
        currentMode = savedMode
        onModeChanged(savedMode)
    }

    fun isClipboardMode(): Boolean = currentMode == InteractionMode.CLIPBOARD

    fun isApiMode(): Boolean =
        currentMode == InteractionMode.API || currentMode == InteractionMode.CHEAP_API

    fun isCheapApiMode(): Boolean = currentMode == InteractionMode.CHEAP_API

    fun isClaudeCodeMode(): Boolean = currentMode == InteractionMode.CLAUDE_CODE

    private fun readModeFromSettings(): InteractionMode {
        return try {
            InteractionMode.valueOf(settings.interactionMode)
        } catch (_: Exception) {
            InteractionMode.API
        }
    }
}
