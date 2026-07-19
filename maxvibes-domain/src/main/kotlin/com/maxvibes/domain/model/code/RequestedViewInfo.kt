package com.maxvibes.domain.model.code

/**
 * Describes a single code view requested by the LLM in its response.
 *
 * Carries granularity so the UI can colour-code requests by "weight":
 * FULL (heavy) → blue, SIGNATURES/OUTLINE (medium) → yellow, ELEMENT (light) → green.
 *
 * @param path          File path as returned in the LLM response (e.g. "src/main/kotlin/Foo.kt").
 * @param granularity   How much of the file was requested.
 * @param elementPath   Non-null only when [granularity] is [CodeGranularity.ELEMENT] or [CodeGranularity.USAGES];
 *                      identifies the specific element (e.g. "class[Foo]/function[bar]").
 */
data class RequestedViewInfo(
    val path: String,
    val granularity: CodeGranularity,
    val elementPath: String? = null
)
