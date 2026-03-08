package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.plugin.settings.MaxVibesSettings

/**
 * Управляет состоянием режима взаимодействия (API / Clipboard / CheapAPI).
 * Инкапсулирует логику переключения и синхронизации с настройками.
 *
 * @param settings настройки плагина для чтения сохранённого режима
 * @param onModeChanged колбек, вызываемый при каждом изменении режима
 */
class InteractionModeManager(
    private val settings: MaxVibesSettings,
    private val onModeChanged: (InteractionMode) -> Unit
) {

    var currentMode: InteractionMode = InteractionMode.API
        private set

    /**
     * Переключает режим на указанный. Вызывает onModeChanged если режим изменился.
     */
    fun switchMode(newMode: InteractionMode) {
        if (currentMode == newMode) return
        currentMode = newMode
        onModeChanged(newMode)
    }

    /**
     * Читает сохранённый режим из настроек и применяет его.
     * Вызывается при инициализации панели.
     */
    fun syncFromSettings() {
        val savedMode = readModeFromSettings()
        currentMode = savedMode
        onModeChanged(savedMode)
    }

    /**
     * Возвращает true если текущий режим — Clipboard.
     */
    fun isClipboardMode(): Boolean = currentMode == InteractionMode.CLIPBOARD

    /**
     * Возвращает true если текущий режим — API (обычный или дешёвый).
     */
    fun isApiMode(): Boolean = currentMode == InteractionMode.API || currentMode == InteractionMode.CHEAP_API

    /**
     * Возвращает true если текущий режим — CheapAPI.
     */
    fun isCheapApiMode(): Boolean = currentMode == InteractionMode.CHEAP_API

    private fun readModeFromSettings(): InteractionMode {
        return try {
            InteractionMode.valueOf(settings.interactionMode)
        } catch (_: Exception) {
            InteractionMode.API
        }
    }
}
