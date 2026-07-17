package com.maxvibes.application.service

import com.maxvibes.application.port.output.SpecificPromptRepository
import com.maxvibes.domain.model.interaction.SpecificPrompt

/**
 * Application service for skills (task-scoped prompts).
 *
 * Wraps [SpecificPromptRepository] for the UI, the prompt builder and the
 * interaction services. No IntelliJ dependencies — unit-testable via Gradle.
 *
 * "Just Code" is represented as null throughout — no sentinel object.
 */
class SpecificPromptService(private val repository: SpecificPromptRepository) {

    companion object {
        /** Skills up to this size are inlined into the turn; larger ones are fetched on demand. */
        const val INLINE_THRESHOLD_CHARS = 4000
    }

    /** All skills with descriptions and file locations — for the manager dialog. */
    fun loadAll(): List<SpecificPrompt> = repository.loadAll()

    /** Names for the UI dropdown. "Just Code" is prepended by the UI, not here. */
    fun getAvailablePromptNames(): List<String> = repository.loadAll().map { it.name }

    /**
     * Per-turn prompt for the selected skill. Small skills are inlined verbatim;
     * large ones become a stub instructing the model to fetch the full instructions
     * via a SKILL-granularity view request. Null when [name] is null (Just Code)
     * or the skill no longer exists on disk.
     */
    fun resolvePromptContent(name: String?): String? {
        if (name == null) return null
        val prompt = repository.loadByName(name) ?: return null
        if (prompt.content.length <= INLINE_THRESHOLD_CHARS) return prompt.content
        return buildString {
            appendLine("ACTIVE SKILL: ${prompt.name} — ${prompt.description.ifBlank { "(no description)" }}")
            append(
                "Its full instructions are too large to inline. BEFORE doing the task, request them via " +
                        "requestedViews: { \"path\": \"${prompt.name}\", \"granularity\": \"SKILL\" } " +
                        "(alone, in its own turn). Then follow those instructions as binding."
            )
        }
    }

    /** Full body of a skill for a SKILL-granularity view request. Null if the name is unknown. */
    fun resolveSkillBody(name: String): String? = repository.loadByName(name)?.content

    /**
     * "## Skills" section for the Claude Code system prompt: the catalog of names and
     * descriptions plus the rule for requesting a skill body. Null when no skills exist.
     */
    fun skillCatalogSection(): String? {
        val all = repository.loadAll()
        if (all.isEmpty()) return null
        return buildString {
            appendLine("## Skills")
            appendLine()
            appendLine(
                "Reusable instruction sets available in this project. When a task matches a skill, request its " +
                        "full instructions via requestedViews: { \"path\": \"<skill name>\", \"granularity\": \"SKILL\" } " +
                        "(alone, not mixed with modifications or commands), then follow them as binding."
            )
            appendLine()
            all.forEach { appendLine("- ${it.name}: ${it.description.ifBlank { "(no description)" }}") }
        }.trimEnd()
    }

    /** Validates that a previously selected name still exists. Returns it, or null (Just Code). */
    fun validatePromptName(name: String?): String? {
        if (name == null) return null
        return if (repository.loadByName(name) != null) name else null
    }

    /** Skills visible in the editor menu for an element of [kind]; empty when kind is null. */
    fun editorSkillsFor(kind: String?): List<SpecificPrompt> {
        if (kind == null) return emptyList()
        return repository.loadAll().filter { p ->
            val spec = p.editorSpec ?: return@filter false
            "any" in spec.appliesTo || kind in spec.appliesTo
        }
    }

    /** Renders the prefill text for an editor-skill invocation. */
    fun renderEditorTemplate(
        prompt: SpecificPrompt,
        elementPath: String,
        elementName: String,
        filePath: String
    ): String {
        val template = prompt.editorSpec?.template
            ?: "Apply the '${prompt.name}' skill to {{elementPath}}."
        return template
            .replace("{{elementPath}}", elementPath)
            .replace("{{elementName}}", elementName)
            .replace("{{filePath}}", filePath)
    }
}