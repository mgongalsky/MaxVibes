package com.maxvibes.application.service

import com.maxvibes.domain.model.interaction.SkillEditorSpec

/**
 * Pure SKILL.md frontmatter parser. Supports single-line `key: value` plus a minimal
 * YAML-style block scalar `key: |` (following lines indented by two spaces; blank
 * lines inside the block are preserved). Unknown keys are ignored — forward and
 * backward compatible with existing skills.
 *
 * Extracted from FileSpecificPromptRepository.parseSkillMd so the frontmatter logic
 * is unit-testable via Gradle (the repository lives in the plugin module, whose
 * tests cannot run under Gradle — coroutines-debug javaagent crash).
 */
object SkillMdParser {

    data class Parsed(
        /** `name:` value; null when absent — caller falls back to the directory name. */
        val name: String?,
        val description: String,
        /** Editor-integration spec; null when `applies-to` is absent or empty. */
        val editorSpec: SkillEditorSpec?,
        /** Skill body with the frontmatter stripped. */
        val body: String
    )

    fun parse(text: String): Parsed {
        if (!text.startsWith("---")) return Parsed(null, "", null, text)
        val end = text.indexOf("\n---", startIndex = 3)
        if (end <= 0) return Parsed(null, "", null, text)
        val frontmatter = text.substring(3, end)
        val body = text.substring(end + 4).trimStart('\n', '\r')

        var name: String? = null
        var description = ""
        var appliesTo: Set<String>? = null
        var template: String? = null
        var attach = false

        val lines = frontmatter.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val idx = line.indexOf(':')
            if (idx <= 0 || line.startsWith(" ")) {
                i++
                continue
            }
            val key = line.substring(0, idx).trim()
            var value = line.substring(idx + 1).trim()
            if (value == "|") {
                val block = StringBuilder()
                i++
                while (i < lines.size && (lines[i].startsWith("  ") || lines[i].isBlank())) {
                    block.append(lines[i].removePrefix("  ")).append('\n')
                    i++
                }
                value = block.toString().trimEnd('\n')
            } else {
                value = value.trim('"', '\'')
                i++
            }
            when (key) {
                "name" -> if (value.isNotBlank()) name = value
                "description" -> description = value
                "applies-to" -> appliesTo = value.split(',')
                    .map { it.trim().lowercase() }
                    .filter { it.isNotBlank() }
                    .toSet()

                "attach-element" -> attach = value.equals("true", ignoreCase = true)
                "editor-template" -> template = value.ifBlank { null }
            }
        }
        val spec = appliesTo?.takeIf { it.isNotEmpty() }?.let { SkillEditorSpec(it, template, attach) }
        return Parsed(name, description, spec, body)
    }
}
