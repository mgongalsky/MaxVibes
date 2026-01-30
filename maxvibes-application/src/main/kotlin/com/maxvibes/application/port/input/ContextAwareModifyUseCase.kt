package com.maxvibes.application.port.input

import com.maxvibes.domain.model.modification.ModificationResult

/**
 * Use case для модификации кода с автоматическим сбором контекста
 */
interface ContextAwareModifyUseCase {

    /**
     * Выполняет задачу с автоматическим сбором контекста:
     * 1. Собирает метаданные проекта
     * 2. LLM определяет нужные файлы
     * 3. Собирает содержимое файлов
     * 4. LLM генерирует модификации
     * 5. Применяет изменения
     */
    suspend fun execute(request: ContextAwareRequest): ContextAwareResult
}

data class ContextAwareRequest(
    val task: String,
    val additionalFiles: List<String> = emptyList(),  // Файлы, которые точно включить
    val excludeFiles: List<String> = emptyList(),      // Файлы, которые исключить
    val dryRun: Boolean = false                         // Только показать план, не применять
)

data class ContextAwareResult(
    val success: Boolean,
    val requestedFiles: List<String>,           // Какие файлы запросил LLM
    val gatheredFiles: List<String>,            // Какие файлы реально собрали
    val modifications: List<ModificationResult>,
    val planningReasoning: String? = null,      // Почему LLM выбрал эти файлы
    val error: String? = null
)