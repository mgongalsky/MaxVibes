// maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/action/OpenSettingsAction.kt
package com.maxvibes.plugin.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

/**
 * Action to open MaxVibes settings dialog.
 */
class OpenSettingsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            "com.maxvibes.plugin.settings"
        )
    }
}