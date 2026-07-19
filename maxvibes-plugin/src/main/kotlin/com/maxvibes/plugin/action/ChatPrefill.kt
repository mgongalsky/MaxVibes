package com.maxvibes.plugin.action

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.maxvibes.plugin.ui.ChatInputListener
import com.maxvibes.plugin.ui.EditorPrefill

/**
 * Activates the MaxVibes tool window, then publishes the prefill on the project
 * message bus. EDT only (called from AnAction.actionPerformed).
 */
object ChatPrefill {
    fun publish(project: Project, prefill: EditorPrefill) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("MaxVibes")
        if (toolWindow == null) {
            Messages.showWarningDialog(project, "MaxVibes tool window is not available", "MaxVibes")
            return
        }
        toolWindow.activate({
            project.messageBus.syncPublisher(ChatInputListener.TOPIC).onPrefill(prefill)
        }, true)
    }
}
