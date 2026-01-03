package com.maxvibes.application.service

import com.maxvibes.application.port.input.AnalyzeCodeRequest
import com.maxvibes.application.port.input.AnalyzeCodeResponse
import com.maxvibes.application.port.input.AnalyzeCodeUseCase
import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.application.port.output.LLMService
import com.maxvibes.application.port.output.NotificationPort
import com.maxvibes.shared.result.getOrElse

class AnalyzeCodeService(
    private val codeRepository: CodeRepository,
    private val llmService: LLMService,
    private val notificationPort: NotificationPort
) : AnalyzeCodeUseCase {

    override suspend fun execute(request: AnalyzeCodeRequest): AnalyzeCodeResponse {
        notificationPort.showProgress("Reading code...", 0.2)

        // 1. Получаем код
        val codeElement = codeRepository.getElement(request.targetPath).getOrElse { error ->
            notificationPort.showError(error.message)
            return AnalyzeCodeResponse(
                success = false,
                answer = "Failed to read code: ${error.message}"
            )
        }

        notificationPort.showProgress("Analyzing with AI...", 0.5)

        // 2. Анализируем через LLM
        val analysis = llmService.analyzeCode(
            question = request.question,
            codeElements = listOf(codeElement)
        ).getOrElse { error ->
            notificationPort.showError(error.message)
            return AnalyzeCodeResponse(
                success = false,
                answer = "LLM error: ${error.message}"
            )
        }

        notificationPort.showProgress("Done", 1.0)
        notificationPort.showSuccess("Analysis complete")

        return AnalyzeCodeResponse(
            success = true,
            answer = analysis.answer,
            suggestions = analysis.suggestions
        )
    }
}