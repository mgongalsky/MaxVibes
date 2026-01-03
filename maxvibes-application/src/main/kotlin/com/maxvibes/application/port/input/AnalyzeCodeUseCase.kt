package com.maxvibes.application.port.input

import com.maxvibes.domain.model.code.ElementPath

/**
 * Use Case: анализ кода через LLM
 */
interface AnalyzeCodeUseCase {

    suspend fun execute(request: AnalyzeCodeRequest): AnalyzeCodeResponse
}

data class AnalyzeCodeRequest(
    val question: String,
    val targetPath: ElementPath
)

data class AnalyzeCodeResponse(
    val success: Boolean,
    val answer: String,
    val suggestions: List<String> = emptyList()
)