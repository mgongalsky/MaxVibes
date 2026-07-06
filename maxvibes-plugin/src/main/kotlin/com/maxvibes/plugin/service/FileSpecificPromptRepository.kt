package com.maxvibes.plugin.service

import com.maxvibes.application.port.output.SpecificPromptRepository
import com.maxvibes.domain.model.interaction.PromptSource
import com.maxvibes.domain.model.interaction.SpecificPrompt
import java.io.File

class FileSpecificPromptRepository(
private val projectSkillsDir: File,
private val legacyPromptsDir: File,
private val globalSkillsDir: File
) : SpecificPromptRepository {

companion object {
private val LEGACY_EXTENSIONS = setOf("md", "txt")

fun forProject(projectBasePath: String): FileSpecificPromptRepository =
FileSpecificPromptRepository(
projectSkillsDir = File(projectBasePath, ".claude/skills"),
legacyPromptsDir = File(projectBasePath, ".maxvibes/prompts/specific"),
globalSkillsDir = File(System.getProperty("user.home"), ".claude/skills")
)
}

override fun loadAll(): List<SpecificPrompt> {
val result = mutableListOf<SpecificPrompt>()
result += loadSkillDir(projectSkillsDir, PromptSource.PROJECT_SKILL)
result += loadLegacy()
result += loadSkillDir(globalSkillsDir, PromptSource.GLOBAL_SKILL)
// Higher-precedence sources were added first; distinctBy keeps the first occurrence.
return result.distinctBy { it.name }.sortedBy { it.name.lowercase() }
}

override fun loadByName(name: String): SpecificPrompt? =
loadAll().firstOrNull { it.name == name }

// ── sources ──

private fun loadSkillDir(root: File, source: PromptSource): List<SpecificPrompt> {
if (!root.isDirectory) return emptyList()
return root.listFiles { f -> f.isDirectory }.orEmpty()
.sortedBy { it.name }
.mapNotNull { dir ->
val md = File(dir, "SKILL.md")
if (md.isFile) parseSkillMd(md, source, fallbackName = dir.name) else null
}
}

private fun loadLegacy(): List<SpecificPrompt> {
if (!legacyPromptsDir.isDirectory) return emptyList()
return legacyPromptsDir
.listFiles { f -> f.isFile && f.extension in LEGACY_EXTENSIONS }
.orEmpty()
.sortedBy { it.nameWithoutExtension }
.mapNotNull { readLegacyFile(it) }
}

private fun readLegacyFile(file: File): SpecificPrompt? = try {
val content = file.readText(Charsets.UTF_8)
SpecificPrompt(
name = file.nameWithoutExtension,
content = content,
description = content.lineSequence()
.firstOrNull { it.isNotBlank() }
?.trim()?.trimStart('#')?.trim()
?.take(100)
?: "",
filePath = file.absolutePath,
source = PromptSource.LEGACY
)
} catch (e: Exception) {
null
}

private fun parseSkillMd(file: File, source: PromptSource, fallbackName: String): SpecificPrompt? = try {
val text = file.readText(Charsets.UTF_8)
var name = fallbackName
var description = ""
var body = text
if (text.startsWith("---")) {
val end = text.indexOf("\n---", startIndex = 3)
if (end > 0) {
val frontmatter = text.substring(3, end)
body = text.substring(end + 4).trimStart('\n', '\r')
frontmatter.lines().forEach { line ->
val idx = line.indexOf(':')
if (idx > 0) {
val key = line.substring(0, idx).trim()
val value = line.substring(idx + 1).trim().trim('"', '\'')
when (key) {
"name" -> if (value.isNotBlank()) name = value
"description" -> description = value
}
}
}
}
}
SpecificPrompt(name, body, description, file.absolutePath, source)
} catch (e: Exception) {
null
}
}