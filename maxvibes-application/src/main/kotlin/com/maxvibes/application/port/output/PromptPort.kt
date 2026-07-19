package com.maxvibes.application.port.output

/**
 * Набор промптов для LLM
 */
data class PromptTemplates(
    val chatSystem: String,
    val planningSystem: String
) {
    companion object {
        val EMPTY = PromptTemplates(
            chatSystem = "",
            planningSystem = ""
        )
    }
}

/**
 * Порт для получения промптов
 */
interface PromptPort {

    /**
     * Загрузить промпты (из проекта или дефолтные)
     */
    fun getPrompts(): PromptTemplates

    /**
     * Проверить, есть ли кастомные промпты в проекте
     */
    fun hasCustomPrompts(): Boolean

    /**
     * Создать/открыть файлы промптов в проекте для редактирования
     */
    fun openOrCreatePrompts()

    /**
     * System prompt for Claude Code mode.
     *
     * Distinct from [getPrompts]'s `chatSystem` because Claude Code runs in CLI/headless mode
     * with built-in tools (Read/Write/Edit/Bash/etc.) enabled by default — the prompt must
     * explicitly forbid them and instruct the model to respond with raw JSON only.
     *
     * Loaded from `.maxvibes/prompts/claude-code-system.md` if present, otherwise from the
     * packaged classpath resource `/prompts/claude-code-system.md`.
     */
    fun claudeCodeSystem(): String
}