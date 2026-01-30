package com.maxvibes.application.port.output

import com.maxvibes.domain.model.code.CodeElement
import com.maxvibes.domain.model.context.ContextRequest
import com.maxvibes.domain.model.context.FileTree
import com.maxvibes.domain.model.context.GatheredContext
import com.maxvibes.domain.model.context.ProjectContext
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.shared.result.Result

/**
 * Порт для работы с LLM
 */
interface LLMService {

    /**
     * Phase 1: Planning — анализирует задачу и определяет нужные файлы
     */
    suspend fun planContext(
        task: String,
        projectContext: ProjectContext
    ): Result<ContextRequest, LLMError>

    /**
     * Phase 2: Coding — генерирует модификации на основе собранного контекста
     */
    suspend fun generateModifications(
        task: String,
        gatheredContext: GatheredContext,
        projectContext: ProjectContext
    ): Result<List<Modification>, LLMError>

    /**
     * Анализ кода (без модификаций)
     */
    suspend fun analyzeCode(
        question: String,
        codeElements: List<CodeElement>
    ): Result<AnalysisResponse, LLMError>

    /**
     * Legacy метод для обратной совместимости
     */
    suspend fun generateModifications(
        instruction: String,
        context: LLMContext
    ): Result<List<Modification>, LLMError>
}

data class LLMContext(
    val relevantCode: List<CodeElement>,
    val projectInfo: ProjectInfo? = null,
    val additionalInstructions: String? = null
)

data class ProjectInfo(
    val name: String,
    val rootPath: String,
    val language: String = "Kotlin"
)

data class AnalysisResponse(
    val answer: String,
    val suggestions: List<String> = emptyList(),
    val referencedPaths: List<String> = emptyList()
)

sealed class LLMError(val message: String) {
    class NetworkError(details: String) : LLMError("Network error: $details")
    class RateLimitError : LLMError("Rate limit exceeded")
    class InvalidResponse(details: String) : LLMError("Invalid response: $details")
    class ConfigurationError(details: String) : LLMError("Configuration error: $details")
    class ParsingError(details: String) : LLMError("Failed to parse LLM response: $details")
}