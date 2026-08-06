package com.maxvibes.plugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import java.awt.BorderLayout
import javax.swing.JPanel

/** Thin IntelliJ-facing facade for the composed chat UI. */
class ChatPanel(
    project: Project,
    toolWindow: ToolWindow,
    onShowSessions: () -> Unit
) : JPanel(BorderLayout()), Disposable {

    private val composition = ChatPanelComposition(
        project = project,
        toolWindow = toolWindow,
        onShowSessions = onShowSessions,
        parent = this
    )

    init {
        add(composition.view, BorderLayout.CENTER)
        composition.initialize()
        Disposer.register(toolWindow.disposable, this)
    }

    fun refreshHeader() {
        composition.refreshHeader()
    }

    fun loadCurrentSession() {
        composition.loadCurrentSession()
    }

    fun acceptPrefill(prefill: EditorPrefill) {
        composition.acceptPrefill(prefill)
    }

    override fun dispose() {
        composition.dispose()
    }
}
