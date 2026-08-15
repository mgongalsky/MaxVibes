package com.maxvibes.plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.maxvibes.plugin.service.MaxVibesService
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/** IntelliJ Settings page under Tools → MaxVibes. */
class MaxVibesSettingsConfigurable : Configurable {
    private var settingsPanel: MaxVibesSettingsPanel? = null
    private var voiceSettingsPanel: VoiceTranscriptionSettingsPanel? = null

    override fun getDisplayName(): String = "MaxVibes"

    override fun getHelpTopic(): String = "maxvibes.settings"

    override fun createComponent(): JComponent {
        val mainPanel = MaxVibesSettingsPanel()
        val voicePanel = VoiceTranscriptionSettingsPanel()
        settingsPanel = mainPanel
        voiceSettingsPanel = voicePanel

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4)
            add(voicePanel.panel)
            add(mainPanel.panel)
        }
        return JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
    }

    override fun isModified(): Boolean {
        val mainModified = settingsPanel?.isModified(MaxVibesSettings.getInstance()) ?: false
        val voiceModified = voiceSettingsPanel?.isModified(VoiceTranscriptionSettings.getInstance()) ?: false
        return mainModified || voiceModified
    }

    override fun apply() {
        settingsPanel?.saveSettings(MaxVibesSettings.getInstance())
        voiceSettingsPanel?.saveSettings(VoiceTranscriptionSettings.getInstance())

        ProjectManager.getInstance().openProjects.forEach { project ->
            if (!project.isDisposed) {
                runCatching { MaxVibesService.getInstance(project).refreshLLMService() }
            }
        }
    }

    override fun reset() {
        settingsPanel?.loadSettings(MaxVibesSettings.getInstance())
        voiceSettingsPanel?.loadSettings(VoiceTranscriptionSettings.getInstance())
    }

    override fun disposeUIResources() {
        settingsPanel = null
        voiceSettingsPanel = null
    }
}
