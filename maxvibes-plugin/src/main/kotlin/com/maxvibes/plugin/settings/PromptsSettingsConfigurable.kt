package com.maxvibes.plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.maxvibes.plugin.service.PromptService
import com.maxvibes.plugin.service.PromptSyncReport
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Project-level page for the prompt files under `.maxvibes/prompts`.
 *
 * Holds no settings: the button is a maintenance action and runs the moment it is
 * pressed, so [isModified] and [apply] stay empty on purpose. Making it obey OK
 * would mean a press with no OK silently does nothing.
 */
class PromptsSettingsConfigurable(private val project: Project) : Configurable {

    private var statusLabel: JBLabel? = null
    private var legacyLabel: JBLabel? = null

    private val service: PromptService
        get() = PromptService.getInstance(project)

    override fun getDisplayName(): String = "Prompts"

    override fun createComponent(): JComponent {
        val status = JBLabel("")
        val legacy = JBLabel("")
        statusLabel = status
        legacyLabel = legacy
        val button = JButton("Resync prompt files").apply { addActionListener { resync() } }
        reset()
        return FormBuilder.createFormBuilder()
            .addComponent(JBLabel("<html><b>Prompt files</b></html>"))
            .addComponent(
                JBLabel(
                    "<html>The prompts MaxVibes sends ship with the plugin and are mirrored into<br>" +
                            "<code>.maxvibes/prompts/base/</code> for reference only. Your own instructions go into<br>" +
                            "the <code>*.local.md</code> files next to that folder: they are appended last and<br>" +
                            "override everything above them. MaxVibes never rewrites them.</html>"
                )
            )
            .addComponent(button)
            .addComponent(
                JBLabel(
                    "<html>Refreshes the mirror to this plugin version and creates any missing<br>" +
                            "<code>*.local.md</code>. Use it when prompts look stale or inconsistent.</html>"
                )
            )
            .addComponent(legacy)
            .addComponent(status)
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .apply { border = JBUI.Borders.empty(8) }
    }

    override fun isModified(): Boolean = false

    override fun apply() = Unit

    override fun reset() {
        statusLabel?.text = ""
        refreshLegacyWarning()
    }

    override fun disposeUIResources() {
        statusLabel = null
        legacyLabel = null
    }

    private fun resync() {
        val legacyNames = service.legacyPromptFiles().map { it.name }
        if (!confirm(legacyNames)) return
        statusLabel?.text = describe(service.resyncPrompts(archiveLegacy = true))
        refreshLegacyWarning()
    }

    /**
     * Spells out the rules rather than a dry run of the sync.
     *
     * A true preview would need a second implementation of the sync logic, and the
     * day the two drift apart this dialog starts lying. Only the legacy files are
     * listed by name — that part is genuinely unpredictable and can be read off disk
     * without reimplementing anything.
     */
    private fun confirm(legacyNames: List<String>): Boolean {
        val text = buildString {
            appendLine("MaxVibes will:")
            appendLine("  • rewrite .maxvibes/prompts/base/*.md with the prompts of this plugin version;")
            appendLine("  • create the *.local.md files that are missing — existing ones are left alone.")
            if (legacyNames.isNotEmpty()) {
                appendLine()
                appendLine("Prompts of the old single-file layout will be renamed so they stop being read:")
                legacyNames.forEach { appendLine("  • $it") }
                appendLine("Their text is preserved — the files are renamed, not deleted.")
            }
            appendLine()
            append("Your own instructions live in the *.local.md files and are never overwritten.")
        }
        return Messages.showYesNoDialog(
            project, text, "Resync Prompt Files", "Resync", "Cancel", Messages.getQuestionIcon()
        ) == Messages.YES
    }

    private fun describe(report: PromptSyncReport): String {
        if (report.isEmpty()) return "<html>Already up to date — nothing was changed.</html>"
        return buildString {
            append("<html>")
            if (report.baseWritten.isNotEmpty()) {
                append("Refreshed: ").append(report.baseWritten.joinToString(", ")).append("<br>")
            }
            if (report.localCreated.isNotEmpty()) {
                append("Created: ").append(report.localCreated.joinToString(", ")).append("<br>")
            }
            if (report.legacyArchived.isNotEmpty()) {
                append("Moved aside, text kept: ")
                    .append(report.legacyArchived.joinToString(", ")).append("<br>")
            }
            append("</html>")
        }
    }

    private fun refreshLegacyWarning() {
        val label = legacyLabel ?: return
        val names = service.legacyPromptFiles().map { it.name }
        label.isVisible = names.isNotEmpty()
        if (names.isNotEmpty()) {
            label.text = "<html>⚠ Prompts of the old layout are still here: " +
                    names.joinToString(", ") +
                    ".<br>They are no longer read. Resync moves them aside without losing their text.</html>"
        }
    }
}
