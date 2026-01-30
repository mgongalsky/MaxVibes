package com.maxvibes.application.service

import com.maxvibes.application.port.input.ContextAwareModifyUseCase
import com.maxvibes.application.port.input.ContextAwareRequest
import com.maxvibes.application.port.input.ContextAwareResult
import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.application.port.output.LLMService
import com.maxvibes.application.port.output.NotificationPort
import com.maxvibes.application.port.output.ProjectContextPort
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.shared.result.Result

/**
 * Сервис для модификации кода с автоматическим сбором контекста
 */
class ContextAwareModifyService(
    private val contextProvider: ProjectContextPort,
    private val llmService: LLMService,
    private val codeRepository: CodeRepository,
    private val notificationPort: NotificationPort
) : ContextAwareModifyUseCase {

    override suspend fun execute(request: ContextAwareRequest): ContextAwareResult {
        notificationPort.showProgress("Starting context-aware modification...", 0.0)

        // 1. Собираем контекст проекта
        notificationPort.showProgress("Gathering project context...", 0.1)
        val projectContextResult = contextProvider.getProjectContext()
        if (projectContextResult is Result.Failure) {
            notificationPort.showError("Failed to get project context")
            return ContextAwareResult(
                success = false,
                requestedFiles = emptyList(),
                gatheredFiles = emptyList(),
                modifications = emptyList(),
                error = "Failed to get project context: ${projectContextResult.error.message}"
            )
        }
        val projectContext = (projectContextResult as Result.Success).value

        // 2. Phase 1: Planning — LLM определяет нужные файлы
        notificationPort.showProgress("Planning: asking LLM which files are needed...", 0.2)
        val planResult = llmService.planContext(request.task, projectContext)
        if (planResult is Result.Failure) {
            notificationPort.showError("Planning failed")
            return ContextAwareResult(
                success = false,
                requestedFiles = emptyList(),
                gatheredFiles = emptyList(),
                modifications = emptyList(),
                error = "Planning failed: ${planResult.error.message}"
            )
        }
        val contextRequest = (planResult as Result.Success).value

        // Объединяем запрошенные файлы с дополнительными
        val filesToGather = (contextRequest.requestedFiles + request.additionalFiles)
            .distinct()
            .filterNot { it in request.excludeFiles }

        notificationPort.showProgress("LLM requested ${filesToGather.size} files", 0.3)

        // 3. Собираем содержимое файлов
        notificationPort.showProgress("Gathering file contents...", 0.4)
        val gatherResult = contextProvider.gatherFiles(filesToGather)
        if (gatherResult is Result.Failure) {
            notificationPort.showError("Failed to gather files")
            return ContextAwareResult(
                success = false,
                requestedFiles = filesToGather,
                gatheredFiles = emptyList(),
                modifications = emptyList(),
                planningReasoning = contextRequest.reasoning,
                error = "Failed to gather files: ${gatherResult.error.message}"
            )
        }
        val gatheredContext = (gatherResult as Result.Success).value

        notificationPort.showProgress("Gathered ${gatheredContext.files.size} files (~${gatheredContext.totalTokensEstimate} tokens)", 0.5)

        // Dry run — только показываем план
        if (request.dryRun) {
            notificationPort.showSuccess("Dry run completed")
            return ContextAwareResult(
                success = true,
                requestedFiles = filesToGather,
                gatheredFiles = gatheredContext.files.keys.toList(),
                modifications = emptyList(),
                planningReasoning = contextRequest.reasoning
            )
        }

        // 4. Phase 2: Coding — LLM генерирует модификации
        notificationPort.showProgress("Generating modifications...", 0.6)
        val modificationsResult = llmService.generateModifications(
            task = request.task,
            gatheredContext = gatheredContext,
            projectContext = projectContext
        )
        if (modificationsResult is Result.Failure) {
            notificationPort.showError("Code generation failed")
            return ContextAwareResult(
                success = false,
                requestedFiles = filesToGather,
                gatheredFiles = gatheredContext.files.keys.toList(),
                modifications = emptyList(),
                planningReasoning = contextRequest.reasoning,
                error = "Code generation failed: ${modificationsResult.error.message}"
            )
        }
        val modifications = (modificationsResult as Result.Success).value

        notificationPort.showProgress("Generated ${modifications.size} modifications, applying...", 0.8)

        // 5. Применяем изменения
        val results = codeRepository.applyModifications(modifications)

        val successCount = results.count { it is ModificationResult.Success }
        val failCount = results.size - successCount

        if (failCount > 0) {
            notificationPort.showWarning("Applied $successCount modifications, $failCount failed")
        } else {
            notificationPort.showSuccess("Successfully applied $successCount modifications")
        }

        return ContextAwareResult(
            success = failCount == 0,
            requestedFiles = filesToGather,
            gatheredFiles = gatheredContext.files.keys.toList(),
            modifications = results,
            planningReasoning = contextRequest.reasoning,
            error = if (failCount > 0) "$failCount modifications failed" else null
        )
    }
}