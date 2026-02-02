package com.maxvibes.application.port.output

import com.maxvibes.domain.model.code.CodeElement
import com.maxvibes.domain.model.context.ContextRequest
import com.maxvibes.domain.model.context.GatheredContext
import com.maxvibes.domain.model.context.ProjectContext
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.shared.result.Result

/**
 * Порт для работы с LLM
 */
interface LLMService {

    /**
     * Основной метод: чат с историей + генерация модификаций
     * LLM отвечает текстом и опционально JSON с модификациями
     */
    suspend fun chat(
        message: String,
        history: List<ChatMessageDTO>,
        context: ChatContext
    ): Result<ChatResponse, LLMError>

    /**
     * Phase 1: Planning — анализирует задачу и определяет нужные файлы
     */
    suspend fun planContext(
        task: String,
        projectContext: ProjectContext
    ): Result<ContextRequest, LLMError>

    /**
     * Анализ кода (без модификаций)
     */
    suspend fun analyzeCode(
        question: String,
        codeElements: List<CodeElement>
    ): Result<AnalysisResponse, LLMError>

    /**
     * Legacy: генерация модификаций (two-phase)
     */
    suspend fun generateModifications(
        task: String,
        gatheredContext: GatheredContext,
        projectContext: ProjectContext
    ): Result<List<Modification>, LLMError>

    /**
     * Legacy: генерация модификаций (old API)
     */
    suspend fun generateModifications(
        instruction: String,
        context: LLMContext
    ): Result<List<Modification>, LLMError>
}

// ==================== Chat DTOs ====================

/**
 * Сообщение для передачи в LLM (DTO без привязки к plugin layer)
 */
data class ChatMessageDTO(
    val role: ChatRole,
    val content: String
)

enum class ChatRole {
    USER, ASSISTANT, SYSTEM
}

/**
 * Контекст для чата — собранные файлы и инфо о проекте
 */
data class ChatContext(
    val projectContext: ProjectContext,
    val gatheredFiles: Map<String, String> = emptyMap(),
    val totalTokensEstimate: Int = 0
)

/**
 * Ответ от LLM: текст + опциональные модификации
 */
data class ChatResponse(
    val message: String,
    val modifications: List<Modification> = emptyList(),
    val requestedFiles: List<String> = emptyList()
)

// ==================== Legacy DTOs ====================

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

// ==================== Errors ====================

sealed class LLMError(val message: String) {
    class NetworkError(details: String) : LLMError("Network error: $details")
    class RateLimitError : LLMError("Rate limit exceeded")
    class InvalidResponse(details: String) : LLMError("Invalid response: $details")
    class ConfigurationError(details: String) : LLMError("Configuration error: $details")
    class ParsingError(details: String) : LLMError("Failed to parse LLM response: $details")
}