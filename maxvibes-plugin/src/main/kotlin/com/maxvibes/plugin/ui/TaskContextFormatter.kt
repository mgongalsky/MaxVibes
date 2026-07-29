package com.maxvibes.plugin.ui

/** Builds the full task text used by modes that embed attached context verbatim. */
internal object TaskContextFormatter {
    fun build(task: String, trace: String?, errors: String?): String {
        val separator = System.lineSeparator()
        return buildString {
            append(task)
            if (!trace.isNullOrBlank()) {
                append(separator)
                append(separator)
                append("--- Error/Trace/Logs ---")
                append(separator)
                append(trace)
            }
            if (!errors.isNullOrBlank()) {
                append(separator)
                append(separator)
                append("--- IDE Errors ---")
                append(separator)
                append(errors)
            }
        }
    }
}
