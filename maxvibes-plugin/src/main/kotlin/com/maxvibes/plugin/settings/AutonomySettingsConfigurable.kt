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

    override fun getDisplayName(): String = "Autonomy"

    override fun createComponent(): JComponent {
        val policyEditor = ApprovalPolicyEditor(ApprovalPolicySettings.getInstance(project))
        val panel = AutonomySettingsPanel(policyEditor)
        panel.refresh()
        editor = policyEditor
        settingsPanel = panel
        return panel.panel
    }

    override fun isModified(): Boolean = editor?.isModified() == true

    override fun apply() {
        editor?.apply()
    }

    override fun reset() {
        editor?.reset()
        settingsPanel?.refresh()
    }

    override fun disposeUIResources() {
        editor = null
        settingsPanel = null
    }
}
