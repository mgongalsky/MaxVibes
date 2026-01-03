package com.maxvibes.application.port.output

/**
 * Порт для уведомлений UI
 */
interface NotificationPort {

    fun showProgress(message: String, fraction: Double? = null)

    fun showSuccess(message: String)

    fun showError(message: String)

    fun showWarning(message: String)

    suspend fun askConfirmation(title: String, message: String): Boolean
}