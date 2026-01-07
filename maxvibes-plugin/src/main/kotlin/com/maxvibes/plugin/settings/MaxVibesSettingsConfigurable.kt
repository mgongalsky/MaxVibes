// maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/settings/MaxVibesSettingsConfigurable.kt
package com.maxvibes.plugin.settings

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

/**
 * Configurable for MaxVibes settings.
 * Integrates with IntelliJ Settings dialog under Tools → MaxVibes.
 */
class MaxVibesSettingsConfigurable : Configurable {

    private var settingsPanel: MaxVibesSettingsPanel? = null

    override fun getDisplayName(): String = "MaxVibes"

    override fun getHelpTopic(): String = "maxvibes.settings"

    override fun createComponent(): JComponent {
        settingsPanel = MaxVibesSettingsPanel()
        return settingsPanel!!.panel
    }

    override fun isModified(): Boolean {
        val settings = MaxVibesSettings.getInstance()
        return settingsPanel?.isModified(settings) ?: false
    }

    override fun apply() {
        val settings = MaxVibesSettings.getInstance()
        settingsPanel?.saveSettings(settings)
    }

    override fun reset() {
        val settings = MaxVibesSettings.getInstance()
        settingsPanel?.loadSettings(settings)
    }

    override fun disposeUIResources() {
        settingsPanel = null
    }
}