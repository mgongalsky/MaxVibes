package com.maxvibes.domain.model.interaction

/**
 * A named task-scoped prompt loaded from the project's `.maxvibes/prompts/specific/` directory.
 *
 * @param name    Display name (file name without extension).
 * @param content Full text content of the prompt file.
 */
data class SpecificPrompt(
    val name: String,
    val content: String
)
