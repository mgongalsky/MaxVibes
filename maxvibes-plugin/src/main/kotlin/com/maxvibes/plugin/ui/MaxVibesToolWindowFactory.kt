package com.maxvibes.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

class MaxVibesToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MaxVibesToolPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class MaxVibesToolPanel(private val project: Project) : JPanel(BorderLayout()) {

    init {
        // Header
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10)

            add(JBLabel("<html><h2>MaxVibes</h2></html>"))
            add(JBLabel("<html><p>AI-powered code assistant for Kotlin</p></html>"))
        }

        // Actions panel
        val actionsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10)

            add(JButton("Modify Code").apply {
                addActionListener {
                    // Trigger action
                    val action = ModifyCodeActionButton(project)
                    action.performAction()
                }
            })

            add(JButton("Analyze Code").apply {
                addActionListener {
                    val action = AnalyzeCodeActionButton(project)
                    action.performAction()
                }
            })
        }

        // Status
        val statusPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(JBLabel("<html><small>Status: Ready</small></html>"), BorderLayout.CENTER)
        }

        // Layout
        val contentPanel = JPanel(BorderLayout()).apply {
            add(headerPanel, BorderLayout.NORTH)
            add(actionsPanel, BorderLayout.CENTER)
            add(statusPanel, BorderLayout.SOUTH)
        }

        add(JBScrollPane(contentPanel), BorderLayout.CENTER)
    }
}

// Простые обёртки для кнопок
class ModifyCodeActionButton(private val project: Project) {
    fun performAction() {
        com.intellij.openapi.ui.Messages.showInfoMessage(
            project,
            "Use right-click menu on a Kotlin file to modify code",
            "MaxVibes"
        )
    }
}

class AnalyzeCodeActionButton(private val project: Project) {
    fun performAction() {
        com.intellij.openapi.ui.Messages.showInfoMessage(
            project,
            "Use right-click menu on a Kotlin file to analyze code",
            "MaxVibes"
        )
    }
}