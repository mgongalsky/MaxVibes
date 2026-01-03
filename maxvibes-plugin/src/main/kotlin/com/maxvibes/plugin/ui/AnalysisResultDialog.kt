package com.maxvibes.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.maxvibes.application.port.input.AnalyzeCodeResponse
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

class AnalysisResultDialog(
    project: Project,
    private val response: AnalyzeCodeResponse
) : DialogWrapper(project) {

    init {
        title = "MaxVibes - Analysis Result"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        val textArea = JBTextArea().apply {
            text = buildString {
                appendLine(response.answer)

                if (response.suggestions.isNotEmpty()) {
                    appendLine()
                    appendLine("Suggestions:")
                    response.suggestions.forEach { suggestion ->
                        appendLine("  • $suggestion")
                    }
                }
            }
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }

        val scrollPane = JBScrollPane(textArea).apply {
            preferredSize = Dimension(600, 400)
        }

        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }
}