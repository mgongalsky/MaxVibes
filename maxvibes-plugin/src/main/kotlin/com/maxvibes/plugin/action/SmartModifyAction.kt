package com.maxvibes.plugin.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.maxvibes.application.port.input.ContextAwareRequest
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.plugin.service.MaxVibesService
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.BoxLayout

/**
 * Smart Modify Action - uses context-aware workflow with automatic file gathering
 */
class SmartModifyAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Show task dialog
        val dialog = SmartModifyDialog(project)
        if (!dialog.showAndGet()) return

        val task = dialog.getTask()
        val dryRun = dialog.isDryRun()

        if (task.isBlank()) {
            Messages.showWarningDialog(project, "Please enter a task description", "MaxVibes")
            return
        }

        val service = MaxVibesService.getInstance(project)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "MaxVibes: Smart Modify...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                service.notificationService.setProgressIndicator(indicator)

                runBlocking {
                    val request = ContextAwareRequest(
                        task = task,
                        dryRun = dryRun
                    )

                    val result = service.contextAwareModifyUseCase.execute(request)

                    // Show results
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        showResultDialog(project, result, dryRun)
                    }
                }
            }
        })
    }

    private fun showResultDialog(
        project: com.intellij.openapi.project.Project,
        result: com.maxvibes.application.port.input.ContextAwareResult,
        wasDryRun: Boolean
    ) {
        val title = if (wasDryRun) "MaxVibes - Dry Run Results" else "MaxVibes - Results"

        val message = buildString {
            if (result.success) {
                appendLine("✅ ${if (wasDryRun) "Plan created" else "Modifications applied"} successfully!")
            } else {
                appendLine("❌ ${result.error ?: "Unknown error"}")
            }
            appendLine()

            if (result.planningReasoning != null) {
                appendLine("📋 LLM Reasoning:")
                appendLine(result.planningReasoning)
                appendLine()
            }

            appendLine("📁 Files requested: ${result.requestedFiles.size}")
            result.requestedFiles.take(10).forEach { appendLine("  • $it") }
            if (result.requestedFiles.size > 10) {
                appendLine("  ... and ${result.requestedFiles.size - 10} more")
            }
            appendLine()

            appendLine("📄 Files gathered: ${result.gatheredFiles.size}")
            appendLine()

            if (!wasDryRun && result.modifications.isNotEmpty()) {
                val successCount = result.modifications.count { it is ModificationResult.Success }
                val failCount = result.modifications.size - successCount
                appendLine("🔧 Modifications: $successCount succeeded, $failCount failed")

                result.modifications.forEach { mod ->
                    when (mod) {
                        is ModificationResult.Success -> appendLine("  ✅ ${mod.affectedPath.value}")
                        is ModificationResult.Failure -> appendLine("  ❌ ${mod.error.message}")
                    }
                }
            }
        }

        Messages.showInfoMessage(project, message, title)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}

/**
 * Dialog for Smart Modify task input
 */
class SmartModifyDialog(
    private val project: com.intellij.openapi.project.Project
) : DialogWrapper(project) {

    private val taskArea = JBTextArea(8, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        text = ""
    }

    private val dryRunCheckbox = JBCheckBox("Dry run (show plan without applying changes)").apply {
        isSelected = false
    }

    init {
        title = "MaxVibes - Smart Modify"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(10)
            preferredSize = Dimension(500, 300)

            // Header
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JBLabel("<html><b>Describe your task</b></html>"))
                add(JBLabel("<html><small>MaxVibes will analyze your project and gather the needed context automatically.</small></html>"))
            }, BorderLayout.NORTH)

            // Task input
            add(JBScrollPane(taskArea).apply {
                border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1)
            }, BorderLayout.CENTER)

            // Options
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(dryRunCheckbox)
                add(JBLabel("<html><small>💡 Tip: Use dry run first to see what files LLM wants to modify</small></html>"))
            }, BorderLayout.SOUTH)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = taskArea

    fun getTask(): String = taskArea.text.trim()
    fun isDryRun(): Boolean = dryRunCheckbox.isSelected
}