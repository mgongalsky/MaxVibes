package com.maxvibes.plugin.service

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.maxvibes.application.port.output.NotificationPort

class IdeNotificationService(private val project: Project) : NotificationPort {

    private var currentIndicator: ProgressIndicator? = null

    override fun showProgress(message: String, fraction: Double?) {
        currentIndicator?.let { indicator ->
            indicator.text = message
            fraction?.let { indicator.fraction = it }
        }
    }

    fun setProgressIndicator(indicator: ProgressIndicator) {
        currentIndicator = indicator
    }

    override fun showSuccess(message: String) {
        notify(message, NotificationType.INFORMATION)
    }

    override fun showError(message: String) {
        notify(message, NotificationType.ERROR)
    }

    override fun showWarning(message: String) {
        notify(message, NotificationType.WARNING)
    }

    override suspend fun askConfirmation(title: String, message: String): Boolean {
        var result = false
        ApplicationManager.getApplication().invokeAndWait {
            result = Messages.showYesNoDialog(
                project,
                message,
                title,
                Messages.getQuestionIcon()
            ) == Messages.YES
        }
        return result
    }

    private fun notify(message: String, type: NotificationType) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("MaxVibes Notifications")
                .createNotification(message, type)
                .notify(project)
        } catch (e: Exception) {
            // Fallback если группа не зарегистрирована
            ApplicationManager.getApplication().invokeLater {
                when (type) {
                    NotificationType.ERROR -> Messages.showErrorDialog(project, message, "MaxVibes")
                    NotificationType.WARNING -> Messages.showWarningDialog(project, message, "MaxVibes")
                    else -> Messages.showInfoMessage(project, message, "MaxVibes")
                }
            }
        }
    }
}