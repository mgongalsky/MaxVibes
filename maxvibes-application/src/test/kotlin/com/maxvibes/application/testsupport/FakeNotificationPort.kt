package com.maxvibes.application.testsupport

import com.maxvibes.application.port.output.NotificationPort

/**
 * Recording [NotificationPort]. [askConfirmation] answers [confirmationAnswer]
 * (true by default).
 */
class FakeNotificationPort : NotificationPort {

    val progressMessages = mutableListOf<String>()
    val successMessages = mutableListOf<String>()
    val errorMessages = mutableListOf<String>()
    val warningMessages = mutableListOf<String>()

    var confirmationAnswer = true

    override fun showProgress(message: String, fraction: Double?) {
        progressMessages += message
    }

    override fun showSuccess(message: String) {
        successMessages += message
    }

    override fun showError(message: String) {
        errorMessages += message
    }

    override fun showWarning(message: String) {
        warningMessages += message
    }

    override suspend fun askConfirmation(title: String, message: String): Boolean = confirmationAnswer
}
