package com.maxvibes.plugin.ui

/**
 * Visibility and captions of the attachment bar.
 *
 * A null [traceText] or [errorsText] means the corresponding label keeps its previous
 * caption, matching the original behaviour of only writing text while an item is attached.
 */
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
            traceText = if (hasTrace) "\uD83D\uDCCE Trace: ${trace!!.lines().size}L" else null,
            errorsVisible = hasErrors,
            errorsText = if (hasErrors) "\uD83D\uDC1E Errors: ${countErrors(errors!!)}" else null,
            barVisible = hasTrace || hasErrors || hasImages
        )
    }

    /** Counts the `File:` block markers the IDE-errors formatter puts before each file. */
    private fun countErrors(errors: String): Int = errors.split(ERROR_BLOCK_MARKER).size - 1

    private const val ERROR_BLOCK_MARKER = "File:"
}
