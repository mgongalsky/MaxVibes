package com.maxvibes.plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.maxvibes.application.service.approval.ApprovalPolicyEditor
import javax.swing.JComponent

/**
 * Project-level page for the approval policy.
 *
 * Project-level on purpose: the trust granted to the agent belongs to a codebase,
 * not to the IDE installation.
 */
class AutonomySettingsConfigurable(private val project: Project) : Configurable {

    private var editor: ApprovalPolicyEditor? = null
    private var settingsPanel: AutonomySettingsPanel? = null

    private val settings: ApprovalPolicySettings
        get() = ApprovalPolicySettings.getInstance(project)

    override fun getDisplayName(): String = "Autonomy"

    override fun createComponent(): JComponent {
        val policyEditor = ApprovalPolicyEditor(settings)
        val panel = AutonomySettingsPanel(policyEditor)
        editor = policyEditor
        settingsPanel = panel
        reset()
        return panel.panel
    }

    override fun isModified(): Boolean {
        val panel = settingsPanel ?: return false
        return editor?.isModified() == true || panel.formatRetries != settings.loadMaxFormatRetries()
    }

    override fun apply() {
        editor?.apply()
        settingsPanel?.let { settings.saveMaxFormatRetries(it.formatRetries) }
    }

    override fun reset() {
        editor?.reset()
        settingsPanel?.refresh()
        settingsPanel?.formatRetries = settings.loadMaxFormatRetries()
    }

    override fun disposeUIResources() {
        editor = null
        settingsPanel = null
    }
}
