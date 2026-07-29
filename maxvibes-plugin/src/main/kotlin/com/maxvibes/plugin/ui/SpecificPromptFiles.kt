package com.maxvibes.plugin.ui

import java.io.File

/**
 * Owns the task-specific prompt files under `.maxvibes/prompts/specific`.
 *
 * Takes a base path rather than a Project so it can be tested against a temp directory.
 */
class SpecificPromptFiles(private val projectBasePath: String) {

    private val directory: File get() = File(projectBasePath, RELATIVE_DIR)

    /** Probes [EXTENSIONS] in order, so a `.md` file shadows a `.txt` of the same name. */
    fun resolve(name: String): File? =
        EXTENSIONS.map { File(directory, "$name.$it") }.firstOrNull { it.exists() }

    fun create(): Result<File> {
        val dir = directory
        if (!dir.exists()) dir.mkdirs()

        var candidate = File(dir, "$BASE_NAME.md")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${BASE_NAME}_$counter.md")
            counter++
        }

        return runCatching {
            candidate.writeText("# ${candidate.nameWithoutExtension}\n\nDescribe your task-specific prompt here.\n")
            candidate
        }
    }

    fun delete(name: String): Boolean = resolve(name)?.delete() == true

    private companion object {
        const val RELATIVE_DIR = ".maxvibes/prompts/specific"
        const val BASE_NAME = "new_prompt"
        val EXTENSIONS = listOf("md", "txt")
    }
}
