package com.maxvibes.plugin.ui

import java.io.File

/**
 * Coordinates create, edit and delete operations for task-specific prompt files.
 *
 * Filesystem storage is supplied by [SpecificPromptFiles]; IntelliJ file opening,
 * confirmation dialogs and UI updates enter through callbacks.
 */
class SpecificPromptFileActions(
    private val files: SpecificPromptFiles?,
    private val selectedPromptName: () -> String?,
    private val persistedPromptName: () -> String?,
    private val openFile: (File) -> Unit,
    private val confirmDelete: (String) -> Boolean,
    private val onClearSelection: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onRefresh: () -> Unit
) {

    fun create() {
        val promptFiles = files ?: return
        promptFiles.create().fold(
            onSuccess = { file ->
                openFile(file)
                onStatus("Created: ${file.name} — add your prompt text and save")
                onRefresh()
            },
            onFailure = { error ->
                onStatus("Failed to create prompt file: ${error.message}")
            }
        )
    }

    fun edit() {
        val name = selectedPromptName() ?: return
        val file = files?.resolve(name) ?: return
        openFile(file)
    }

    fun delete() {
        val name = selectedPromptName() ?: return
        if (!confirmDelete(name)) return

        if (files?.delete(name) != true) {
            onStatus("Failed to delete prompt file")
            return
        }

        if (persistedPromptName() == name) onClearSelection()
        onStatus("Deleted prompt: $name")
        onRefresh()
    }
}
