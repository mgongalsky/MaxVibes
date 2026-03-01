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
    private val notificationPort: NotificationPort,
    private val logger: LoggerPort? = null
) : ModifyCodeUseCase {

    override suspend fun execute(request: ModifyCodeRequest): ModifyCodeResponse {
        logger?.info(TAG, "execute() started, target='${request.targetPath}'")
        notificationPort.showProgress("Analyzing code...", 0.1)

        val codeElement = codeRepository.getElement(request.targetPath).getOrElse { error ->
            val msg = "Failed to read code: ${error.message}"
            logger?.error(TAG, msg)
            notificationPort.showError(error.message)
            return ModifyCodeResponse(
                success = false,
                results = emptyList(),
                summary = msg
            )
        }
        logger?.debug(TAG, "Code element obtained: ${codeElement}")

        notificationPort.showProgress("Generating modifications...", 0.3)

        val context = LLMContext(
            relevantCode = listOf(codeElement),
            projectInfo = null
        )

        val modifications = llmService.generateModifications(
            instruction = request.instruction,
            context = context
        ).getOrElse { error ->
            val msg = "LLM error: ${error.message}"
            logger?.error(TAG, msg)
            notificationPort.showError(error.message)
            return ModifyCodeResponse(
                success = false,
                results = emptyList(),
                summary = msg
            )
        }
        logger?.debug(TAG, "Generated ${modifications.size} modifications")

        if (modifications.isEmpty()) {
            logger?.info(TAG, "No modifications generated")
            notificationPort.showWarning("No modifications generated")
            return ModifyCodeResponse(
                success = true,
                results = emptyList(),
                summary = "No changes needed"
            )
        }

        notificationPort.showProgress("Applying ${modifications.size} modifications...", 0.6)
        logger?.debug(TAG, "Applying ${modifications.size} modifications")

        val results = codeRepository.applyModifications(modifications)

        val successCount = results.count { it is ModificationResult.Success }
        val failureCount = results.count { it is ModificationResult.Failure }

        logger?.info(TAG, "Modifications done: success=$successCount, failed=$failureCount")

        val summary = buildString {
            appendLine("Applied $successCount of ${results.size} modifications")
            if (failureCount > 0) appendLine("Failed: $failureCount")
        }

        notificationPort.showProgress("Done", 1.0)

        if (failureCount == 0) notificationPort.showSuccess(summary)
        else notificationPort.showWarning(summary)

        return ModifyCodeResponse(
            success = failureCount == 0,
            results = results,
            summary = summary
        )
    }

    companion object {
        private const val TAG = "ModifyCodeService"
    }
}