package com.maxvibes.application.port.input

import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.domain.model.modification.ModificationResult

/**
 * Use case для чата с автоматическим сбором контекста и применением модификаций
 */
interface ContextAwareModifyUseCase {

    /**
     * Выполняет задачу с автоматическим сбором контекста:
     * 1. Собирает метаданные проекта
     * 2. LLM определяет нужные файлы (planning)
     * 3. Собирает содержимое файлов
     * 4. LLM отвечает текстом + генерирует модификации
     * 5. Применяет изменения (если не planOnly)
     */
    suspend fun execute(request: ContextAwareRequest): ContextAwareResult
}

data class ContextAwareRequest(
    val task: String,
    val history: List<ChatMessageDTO> = emptyList(),
    val additionalFiles: List<String> = emptyList(),
    val excludeFiles: List<String> = emptyList(),
    val dryRun: Boolean = false,

    /** Plan-only mode: LLM discusses but does NOT generate or apply modifications */
    val planOnly: Boolean = false,

    /** Global context files — always included in LLM context (relative paths in project) */
    val globalContextFiles: List<String> = emptyList()
)

data class ContextAwareResult(
    val success: Boolean,
    val message: String,
    val requestedFiles: List<String>,
    val gatheredFiles: List<String>,
    val modifications: List<ModificationResult>,
    val error: String? = null
)