package com.maxvibes.domain.model.interaction

/**
 * Editor-integration spec parsed from SKILL.md frontmatter.
 * Null on a [SpecificPrompt] means the skill is chat-only (not editor-visible).
 */
data class SkillEditorSpec(
    /** Kind labels the skill applies to; "any" matches every element. */
    val appliesTo: Set<String>,
    /** Prefill template; null = default. Placeholders: {{elementPath}}, {{elementName}}, {{filePath}}. */
    val template: String? = null,
    /** Attach the element body as one-shot context on invoke. */
    val attachElement: Boolean = false
) {
    companion object {
        /** Vocabulary = kind labels produced by ElementAtCaretResolver + "any". */
        val KNOWN_KINDS = setOf(
            "function", "property", "class", "interface",
            "object", "companion_object", "enum_entry", "any"
        )
    }
}
