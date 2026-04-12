package com.maxvibes.application.service

import com.maxvibes.application.port.output.SpecificPromptRepository
import com.maxvibes.domain.model.interaction.SpecificPrompt

/**
 * Application service for managing task-scoped specific prompts.
 *
 * Wraps [SpecificPromptRepository] with convenience methods for the UI layer.
 * Has no IntelliJ dependencies — fully unit-testable via Gradle.
 *
 * "Just Code" is represented as null throughout this service — no special sentinel object.
 */
class SpecificPromptService(private val repository: SpecificPromptRepository) {

    /**
     * Returns all available prompt names for display in the UI dropdown.
     * Does NOT include "Just Code" — that is the UI's responsibility to prepend.
     */
    fun getAvailablePromptNames(): List<String> =
        repository.loadAll().map { it.name }

    /**
     * Resolves a prompt's content by name.
     *
     * @param name Prompt name, or null for "Just Code" mode.
     * @return Prompt content string, or null if name is null or prompt not found.
     *         Null → the `specificPrompt` field is omitted from the JSON request.
     */
    fun resolvePromptContent(name: String?): String? {
        if (name == null) return null
        return repository.loadByName(name)?.content
    }

    /**
     * Validates that a previously selected prompt name still exists on disk.
     * Returns the name if valid, null (Just Code) if the file has been removed.
     */
    fun validatePromptName(name: String?): String? {
        if (name == null) return null
        return if (repository.loadByName(name) != null) name else null
    }
}
