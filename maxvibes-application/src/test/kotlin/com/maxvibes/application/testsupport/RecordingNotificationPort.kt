package com.maxvibes.application.testsupport

import com.maxvibes.application.port.output.NotificationPort
import java.util.ArrayDeque

class RecordingNotificationPort : NotificationPort {
    data class Progress(
        val message: String,
        val fraction: Double?
    )

    val progress = mutableListOf<Progress>()
    val successes = mutableListOf<String>()
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val confirmations = mutableListOf<Pair<String, String>>()

    private val confirmationResults = ArrayDeque<Boolean>()

    fun enqueueConfirmation(result: Boolean) {
        confirmationResults.addLast(result)
    }

    override fun showProgress(message: String, fraction: Double?) {
        progress += Progress(message, fraction)
    }

    override fun showSuccess(message: String) {
        successes += message
    }

    override fun showError(message: String) {
        errors += message
    }

    override fun showWarning(message: String) {
        warnings += message
    }

    override suspend fun askConfirmation(
        title: String,
        message: String
    ): Boolean {
        confirmations += title to message
        return if (confirmationResults.isEmpty()) {
            true
        } else {
            confirmationResults.removeFirst()
        }
    }
}
