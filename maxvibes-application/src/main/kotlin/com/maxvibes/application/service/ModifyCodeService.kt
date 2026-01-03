package com.maxvibes.application.service

import com.maxvibes.application.port.input.ModifyCodeRequest
import com.maxvibes.application.port.input.ModifyCodeResponse
import com.maxvibes.application.port.input.ModifyCodeUseCase
import com.maxvibes.application.port.output.*
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.shared.result.getOrElse

class ModifyCodeService(
    private val codeRepository: CodeRepository,
    private val llmService: LLMService,
    private val notificationPort: NotificationPort
) : ModifyCodeUseCase {

    override suspend fun execute(request: ModifyCodeRequest): ModifyCodeResponse {
        notificationPort.showProgress("Analyzing code...", 0.1)

        // 1. Получаем код для контекста
        val codeElement = codeRepository.getElement(request.targetPath).getOrElse { error ->
            notificationPort.showError(error.message)
            return ModifyCodeResponse(
                success = false,
                results = emptyList(),
                summary = "Failed to read code: ${error.message}"
            )
        }

        notificationPort.showProgress("Generating modifications...", 0.3)

        // 2. Создаём контекст для LLM
        val context = LLMContext(
            relevantCode = listOf(codeElement),
            projectInfo = null // TODO: получать из проекта
        )

        // 3. Генерируем модификации через LLM
        val modifications = llmService.generateModifications(
            instruction = request.instruction,
            context = context
        ).getOrElse { error ->
            notificationPort.showError(error.message)
            return ModifyCodeResponse(
                success = false,
                results = emptyList(),
                summary = "LLM error: ${error.message}"
            )
        }

        if (modifications.isEmpty()) {
            notificationPort.showWarning("No modifications generated")
            return ModifyCodeResponse(
                success = true,
                results = emptyList(),
                summary = "No changes needed"
            )
        }

        notificationPort.showProgress("Applying ${modifications.size} modifications...", 0.6)

        // 4. Применяем модификации
        val results = codeRepository.applyModifications(modifications)

        // 5. Формируем результат
        val successCount = results.count { it is ModificationResult.Success }
        val failureCount = results.count { it is ModificationResult.Failure }

        val summary = buildString {
            appendLine("Applied $successCount of ${results.size} modifications")
            if (failureCount > 0) {
                appendLine("Failed: $failureCount")
            }
        }

        notificationPort.showProgress("Done", 1.0)

        if (failureCount == 0) {
            notificationPort.showSuccess(summary)
        } else {
            notificationPort.showWarning(summary)
        }

        return ModifyCodeResponse(
            success = failureCount == 0,
            results = results,
            summary = summary
        )
    }
}