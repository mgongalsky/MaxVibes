package com.maxvibes.application.port.output

import com.maxvibes.domain.model.code.CodeElement
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.shared.result.Result

/**
 * Порт для работы с LLM (реализуется Koog адаптером)
 */
interface LLMService {

    suspend fun generateModifications(
        instruction: String,
        context: LLMContext
    ): Result<List<Modification>, LLMError>

    suspend fun analyzeCode(
        question: String,
        codeElements: List<CodeElement>
    ): Result<AnalysisResponse, LLMError>
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
}