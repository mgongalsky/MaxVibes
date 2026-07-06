package com.maxvibes.domain.model.interaction


data class SpecificPrompt(
val name: String,
val content: String,
val description: String = "",
val filePath: String? = null,
val source: PromptSource = PromptSource.LEGACY
)

/** Skill origin. Precedence on name clashes: PROJECT_SKILL > LEGACY > GLOBAL_SKILL. */
enum class PromptSource { PROJECT_SKILL, LEGACY, GLOBAL_SKILL }