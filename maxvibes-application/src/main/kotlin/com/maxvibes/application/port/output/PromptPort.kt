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
}