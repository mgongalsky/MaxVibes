package com.maxvibes.domain.model.code

/**
 * A request for a particular "view" of a file at a specified granularity.
 *
 * Created by the LLM (via the clipboard protocol) or internally by the
 * application layer when resolving [requestedViews] from a JSON response.
 *
 * @param filePath project-relative path to the file (e.g. "src/main/kotlin/…/Foo.kt")
 * @param granularity how much of the file to return; defaults to [CodeGranularity.FULL]
 * @param elementPath PSI element path (e.g. "class[Foo]/function[bar]") —
 *                    **required** when [granularity] is [CodeGranularity.ELEMENT]
 */
data class CodeViewRequest(
    val filePath: String,
    val granularity: CodeGranularity = CodeGranularity.FULL,
    val elementPath: String? = null
)
