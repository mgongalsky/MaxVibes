package com.maxvibes.domain.model.code

/**
 * The result of processing a [CodeViewRequest]: ready-to-use text
 * that will be embedded in the next LLM prompt.
 *
 * Produced by the PSI renderer ([PsiCodeViewRenderer]) and returned
 * to the LLM as part of the "requested views" response payload.
 *
 * @param filePath project-relative path to the source file
 * @param granularity the granularity level that was rendered
 *                    (preserved for debugging and logging purposes)
 * @param content the rendered text matching the requested granularity
 */
data class CodeView(
    val filePath: String,
    val granularity: CodeGranularity,
    val content: String
)
