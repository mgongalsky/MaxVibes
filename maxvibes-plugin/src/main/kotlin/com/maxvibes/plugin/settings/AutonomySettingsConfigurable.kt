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
        val previousPolicy = settings.load()
        val previousRetries = settings.loadMaxFormatRetries()
        editor?.apply()
        settingsPanel?.let { settings.saveMaxFormatRetries(it.formatRetries) }
        val currentPolicy = settings.load()
        val currentRetries = settings.loadMaxFormatRetries()
        if (previousPolicy.asMap() != currentPolicy.asMap() || previousRetries != currentRetries) {
            com.maxvibes.plugin.service.MaxVibesService.getInstance(project).loggerPort.info(
                "Autonomy", "settings_changed", mapOf(
                    "source" to "settings_apply",
                    "previousPolicy" to previousPolicy.asMap().entries.joinToString(",") { "${it.key}=${it.value}" },
                    "policy" to currentPolicy.asMap().entries.joinToString(",") { "${it.key}=${it.value}" },
                    "previousFormatRetries" to previousRetries,
                    "formatRetries" to currentRetries
                )
            )
        }
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
