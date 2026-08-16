package com.maxvibes.plugin.ui

internal object TaskContextFormatter {
    fun build(task: String, trace: String?, errors: String?): String {
        val separator = System.lineSeparator()
        return buildString {
            append(task)
            if (!trace.isNullOrBlank()) {
                append(separator).append(separator)
                append("--- Text attachment ---").append(separator).append(trace)
            }
            if (!errors.isNullOrBlank()) {
                append(separator).append(separator)
                append("--- IDE Errors ---").append(separator).append(errors)
            }
        }
    }
}
