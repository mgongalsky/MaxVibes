package com.maxvibes.application.service

import com.maxvibes.application.port.input.ContextAwareModifyUseCase
import com.maxvibes.application.port.input.ContextAwareRequest
import com.maxvibes.application.port.input.ContextAwareResult
import com.maxvibes.application.port.output.*
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.shared.result.Result

/**
 * Сервис для чата с автоматическим сбором контекста.
 * Поддерживает planOnly mode (обсуждение без модификаций) и глобальные контекстные файлы.
 */
class ContextAwareModifyService(
    private val contextProvider: ProjectContextPort,
    private val llmService: LLMService,
    private val codeRepository: CodeRepository,
    private val notificationPort: NotificationPort,
    private val promptPort: PromptPort? = null
) : ContextAwareModifyUseCase {

    override suspend fun execute(request: ContextAwareRequest): ContextAwareResult {
        notificationPort.showProgress("Starting...", 0.0)

        val prompts = promptPort?.getPrompts() ?: PromptTemplates.EMPTY

        notificationPort.showProgress("Gathering project context...", 0.1)
        val projectContextResult = contextProvider.getProjectContext()
        if (projectContextResult is Result.Failure) {
            return errorResult("Failed to get project context: ${projectContextResult.error.message}")
        }
        val projectContext = (projectContextResult as Result.Success).value

        notificationPort.showProgress("Analyzing task...", 0.2)
        val planResult = llmService.planContext(request.task, projectContext, prompts)
        if (planResult is Result.Failure) {
            return errorResult("Planning failed: ${planResult.error.message}")
        }
        val contextRequest = (planResult as Result.Success).value

        val filesToGather = (contextRequest.requestedFiles + request.additionalFiles + request.globalContextFiles)
            .distinct()
            .filterNot { it in request.excludeFiles }

        notificationPort.showProgress("Gathering ${filesToGather.size} files...", 0.4)
        val gatherResult = contextProvider.gatherFiles(filesToGather)
        if (gatherResult is Result.Failure) {
            return errorResult(
                message = "Failed to gather files: ${gatherResult.error.message}",
                requestedFiles = filesToGather
            )
        }
        val gatheredContext = (gatherResult as Result.Success).value

        if (request.dryRun) {
            notificationPort.showSuccess("Dry run completed")
            return ContextAwareResult(
                success = true,
                message = "Dry run: would gather ${gatheredContext.files.size} files and process task.",
                requestedFiles = filesToGather,
                gatheredFiles = gatheredContext.files.keys.toList(),
                modifications = emptyList()
            )
        }

        val modeLabel = if (request.planOnly) "Discussing (plan only)..." else "Generating response..."
        notificationPort.showProgress(modeLabel, 0.6)

        val chatContext = ChatContext(
            projectContext = projectContext,
            gatheredFiles = gatheredContext.files,
            totalTokensEstimate = gatheredContext.totalTokensEstimate,
            prompts = prompts,
            planOnly = request.planOnly
        )

        val chatResult = llmService.chat(
            message = request.task,
            history = request.history,
            context = chatContext
        )

        if (chatResult is Result.Failure) {
            return errorResult(
                message = "Chat failed: ${chatResult.error.message}",
                requestedFiles = filesToGather,
                gatheredFiles = gatheredContext.files.keys.toList()
            )
        }

        val chatResponse = (chatResult as Result.Success).value

        val modificationResults = if (chatResponse.modifications.isNotEmpty() && !request.planOnly) {
            notificationPort.showProgress("Applying ${chatResponse.modifications.size} changes...", 0.8)
            codeRepository.applyModifications(chatResponse.modifications)
        } else {
            emptyList()
        }

        val planOnlyNote = if (request.planOnly && chatResponse.modifications.isNotEmpty()) {
            "\n\n\u26A0\uFE0F Plan-only mode: ${chatResponse.modifications.size} modification(s) were suggested but NOT applied."
        } else ""

        val successCount = modificationResults.count { it is ModificationResult.Success }
        val failCount = modificationResults.size - successCount

        if (modificationResults.isNotEmpty()) {
            if (failCount > 0) notificationPort.showWarning("Applied $successCount changes, $failCount failed")
            else notificationPort.showSuccess("Applied $successCount changes")
        } else {
            notificationPort.showSuccess(if (request.planOnly) "Plan complete" else "Done")
        }

        return ContextAwareResult(
            success = failCount == 0,
            message = chatResponse.message + planOnlyNote,
            requestedFiles = filesToGather,
            gatheredFiles = gatheredContext.files.keys.toList(),
            modifications = modificationResults,
            error = if (failCount > 0) "$failCount modifications failed" else null,
            planningInputTokens = 0,
            planningOutputTokens = 0,
            chatInputTokens = chatResponse.tokenUsage?.inputTokens ?: 0,
            chatOutputTokens = chatResponse.tokenUsage?.outputTokens ?: 0
        )
    }

    private fun errorResult(
        message: String,
        requestedFiles: List<String> = emptyList(),
        gatheredFiles: List<String> = emptyList()
    ): ContextAwareResult {
        notificationPort.showError(message)
        return ContextAwareResult(
            success = false,
            message = "Error: $message",
            requestedFiles = requestedFiles,
            gatheredFiles = gatheredFiles,
            modifications = emptyList(),
            error = message
        )
    }
}