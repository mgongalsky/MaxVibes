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
    private val promptPort: PromptPort? = null,
    private val logger: LoggerPort? = null
) : ContextAwareModifyUseCase {

    override suspend fun execute(request: ContextAwareRequest): ContextAwareResult {
        logger?.info(TAG, "execute() started, task='${request.task.take(80)}', planOnly=${request.planOnly}")
        notificationPort.showProgress("Starting...", 0.0)

        val prompts = promptPort?.getPrompts() ?: PromptTemplates.EMPTY

        notificationPort.showProgress("Gathering project context...", 0.1)
        logger?.debug(TAG, "Requesting project context")
        val projectContextResult = contextProvider.getProjectContext()
        if (projectContextResult is Result.Failure) {
            val errMsg = "Failed to get project context: ${projectContextResult.error.message}"
            logger?.error(TAG, errMsg)
            return errorResult(errMsg)
        }
        val projectContext = (projectContextResult as Result.Success).value
        logger?.debug(TAG, "Project context obtained")

        notificationPort.showProgress("Analyzing task...", 0.2)
        logger?.debug(TAG, "Starting LLM planContext call")
        val planResult = llmService.planContext(request.task, projectContext, prompts)
        if (planResult is Result.Failure) {
            val errMsg = "Planning failed: ${planResult.error.message}"
            logger?.error(TAG, errMsg)
            return errorResult(errMsg)
        }
        val contextRequest = (planResult as Result.Success).value
        logger?.debug(TAG, "planContext succeeded, requestedFiles=${contextRequest.requestedFiles}")

        val filesToGather = (contextRequest.requestedFiles + request.additionalFiles + request.globalContextFiles)
            .distinct()
            .filterNot { it in request.excludeFiles }

        notificationPort.showProgress("Gathering ${filesToGather.size} files...", 0.4)
        logger?.debug(TAG, "Gathering ${filesToGather.size} files: $filesToGather")
        val gatherResult = contextProvider.gatherFiles(filesToGather)
        if (gatherResult is Result.Failure) {
            val errMsg = "Failed to gather files: ${gatherResult.error.message}"
            logger?.error(TAG, errMsg)
            return errorResult(
                message = errMsg,
                requestedFiles = filesToGather
            )
        }
        val gatheredContext = (gatherResult as Result.Success).value
        logger?.debug(
            TAG,
            "Gathered ${gatheredContext.files.size} files, tokens~${gatheredContext.totalTokensEstimate}"
        )

        if (request.dryRun) {
            logger?.info(TAG, "Dry run completed")
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
        logger?.debug(
            TAG,
            "Calling llmService.chat(), planOnly=${request.planOnly}, historySize=${request.history.size}"
        )

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
            val errMsg = "Chat failed: ${chatResult.error.message}"
            logger?.error(TAG, errMsg)
            return errorResult(
                message = errMsg,
                requestedFiles = filesToGather,
                gatheredFiles = gatheredContext.files.keys.toList()
            )
        }

        val chatResponse = (chatResult as Result.Success).value
        logger?.info(
            TAG,
            "Chat succeeded, modifications=${chatResponse.modifications.size}, tokens(in/out)=${chatResponse.tokenUsage?.inputTokens}/${chatResponse.tokenUsage?.outputTokens}"
        )

        val modificationResults = if (chatResponse.modifications.isNotEmpty() && !request.planOnly) {
            notificationPort.showProgress("Applying ${chatResponse.modifications.size} changes...", 0.8)
            logger?.debug(TAG, "Applying ${chatResponse.modifications.size} modifications")
            codeRepository.applyModifications(chatResponse.modifications)
        } else {
            emptyList()
        }

        val planOnlyNote = if (request.planOnly && chatResponse.modifications.isNotEmpty()) {
            "\n\n\u26A0\uFE0F Plan-only mode: ${chatResponse.modifications.size} modification(s) were suggested but NOT applied."
        } else ""

        val successCount = modificationResults.count { it is ModificationResult.Success }
        val failCount = modificationResults.size - successCount

        logger?.info(TAG, "Modifications applied: success=$successCount, failed=$failCount")

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
            chatOutputTokens = chatResponse.tokenUsage?.outputTokens ?: 0,
            commitMessage = chatResponse.commitMessage
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

    companion object {
        private const val TAG = "ContextAwareModifyService"
    }
}