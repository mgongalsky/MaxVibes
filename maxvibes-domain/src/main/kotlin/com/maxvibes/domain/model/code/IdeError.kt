package com.maxvibes.domain.model.code

/**
 * Представляет ошибку компиляции или анализа, полученную из IDE.
 */
data class IdeError(
    val filePath: String,
    val line: Int,
    val column: Int,
    val message: String
) {
    fun formatForLlm(): String = "File: $filePath:$line:$column\nError: $message"
}