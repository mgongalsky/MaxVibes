package com.maxvibes.plugin.ui

data class AttachmentIndicatorsState(
    val traceVisible: Boolean,
    val traceText: String?,
    val errorsVisible: Boolean,
    val errorsText: String?,
    val barVisible: Boolean
)

object AttachmentIndicators {
    fun describe(trace: String?, errors: String?, hasImages: Boolean): AttachmentIndicatorsState {
        val hasTrace = !trace.isNullOrBlank()
        val hasErrors = !errors.isNullOrBlank()
        return AttachmentIndicatorsState(
            traceVisible = hasTrace,
            traceText = if (hasTrace) describeTrace(trace!!) else null,
            errorsVisible = hasErrors,
            errorsText = if (hasErrors) "\uD83D\uDC1E Errors: ${countOccurrences(errors!!, "File:")}" else null,
            barVisible = hasTrace || hasErrors || hasImages
        )
    }

    private fun describeTrace(trace: String): String {
        val chars = trace.length
        val size = if (chars >= 1_000) "%.1fk".format(chars / 1_000.0) else chars.toString()
        return "\uD83D\uDCCE Text: $size chars \u00B7 ${TextClipboardAttachments.countLines(trace)}L"
    }

    private fun countOccurrences(text: String, token: String): Int {
        var count = 0
        var index = text.indexOf(token)
        while (index >= 0) {
            count++
            index = text.indexOf(token, index + token.length)
        }
        return count
    }
}
