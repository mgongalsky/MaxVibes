package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.application.port.output.LoggerPort
import com.maxvibes.application.port.output.NotificationPort
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.application.port.output.CodingAgentSessionLogPort
import com.maxvibes.domain.model.chat.CodingAgentProvider

internal class CodingAgentApprovalService(
    private val chatSessionRepository: ChatSessionRepository,
    private val sessionManager: ClipboardSessionManager,
    private val pendingStore: PendingModificationsStore,
    private val workspaceService: CodingAgentWorkspaceService,
    private val viewResolver: CodingAgentViewResolver,
    private val codeRepository: CodeRepository,
    private val notificationPort: NotificationPort,
    private val sessionLog: CodingAgentSessionLogPort? = null,
    private val logger: LoggerPort? = null,
    private val provider: CodingAgentProvider = CodingAgentProvider.CLAUDE_CODE
) {
    fun rejectPending(command: UserInputCommand): UserInputCommand? {
        val pending = pendingStore.take(command.sessionId) ?: return null
        val rejectedCount = pending.modifications.size
        val heldCommandCount = pending.commands.size

        finishRejection(command.sessionId, rejectedCount, heldCommandCount, "by typing a new message")

        val rejectionMessage = buildString {
            append("[USER REJECTED your ")
            append(rejectedCount)
            append(" proposed modification(s) — nothing was applied")
            if (heldCommandCount > 0) {
                append(", the ")
                append(heldCommandCount)
                append(" held command(s) were not run")
            }
            appendLine(". New instruction follows.]")
            appendLine()
            append(command.userInput)
        }

        return command.copy(userInput = rejectionMessage)
    }

    suspend fun approve(
        sessionId: String,
        attachedContext: String? = null,
        ideErrors: String? = null,
        specificPromptContent: String? = null
    ): CodingAgentApprovalOutcome {
        sessionLog?.begin(sessionId)
        sessionLog?.event(
            "approve",
            mapOf("status" to sessionManager.statusFor(sessionId).name)
        )

        if (sessionManager.statusFor(sessionId) != ClipboardSessionStatus.AWAITING_APPROVE) {
            return immediateError("Approve is only valid in AWAITING_APPROVE state")
        }

        if (pendingStore.hasPendingFor(sessionId)) {
            return CodingAgentApprovalOutcome.Immediate(
                approvePendingModifications(sessionId)
            )
        }

        if (workspaceService.state == null || workspaceService.owner != sessionId) {
            log("sessionState missing or owned by another session in approve — restoring for $sessionId")
            if (!workspaceService.ensure(sessionId)) {
                return immediateError(
                    "Cannot restore session state for session $sessionId. Please start a new task."
                )
            }
        }

        val state = workspaceService.state
            ?: return immediateError("No active workspace — cannot approve")
        val session = chatSessionRepository.getSessionById(sessionId)
            ?: return immediateError("Session not found: $sessionId")
        val lastAssistant = session.messages.lastOrNull {
            it.role == MessageRole.ASSISTANT
        } ?: return immediateError("No assistant message to approve")

        if (lastAssistant.requestedViews.isEmpty()) {
            return immediateError("Last assistant message has no requestedViews to approve")
        }

        val viewRequests = lastAssistant.requestedViews.map { requestedView ->
            CodeViewRequest(
                filePath = requestedView.path,
                granularity = requestedView.granularity,
                elementPath = requestedView.elementPath
            )
        }
        val freshFiles = viewResolver.resolve(viewRequests, state)
            ?: return immediateError("Failed to gather requested files")

        if (
            lastAssistant.content.isNotBlank() &&
            state.dialogHistory.lastOrNull()?.content != lastAssistant.content
        ) {
            workspaceService.appendAssistantHistory(lastAssistant.content)
        }

        sessionManager.transition(sessionId, ClipboardEvent.Approved)

        return CodingAgentApprovalOutcome.Continue(
            CodingAgentTurnCommand(
                sessionId = sessionId,
                freshFiles = freshFiles,
                attachedContext = attachedContext,
                ideErrors = ideErrors,
                specificPromptContent = specificPromptContent
            )
        )
    }

    private suspend fun approvePendingModifications(
        sessionId: String
    ): ClaudeCodeStepResult {
        val pending = pendingStore.take(sessionId)
            ?: return error("No pending modifications to approve")

        sessionManager.transition(sessionId, ClipboardEvent.Approved)
        log(
            "Applying ${pending.modifications.size} approved modification(s), " +
                    "${pending.commands.size} held command(s)"
        )
        sessionLog?.event(
            "pending modifications approved",
            mapOf(
                "mods" to pending.modifications.size,
                "commands" to pending.commands.size
            )
        )

        val modificationResults = applyModifications(pending.modifications)
        val successCount = modificationResults.count { it is ModificationResult.Success }
        val failureCount = modificationResults.size - successCount
        val rolledBack = modificationResults.any {
            it is ModificationResult.Failure &&
                    it.error is com.maxvibes.domain.model.modification.ModificationError.BatchRolledBack
        }

        when {
            rolledBack -> notificationPort.showWarning(
                "Modification batch failed and was rolled back: 0 of ${modificationResults.size} applied"
            )

            failureCount > 0 -> notificationPort.showWarning(
                "Applied $successCount changes, $failureCount failed"
            )

            successCount > 0 -> notificationPort.showSuccess("Applied $successCount changes")
        }

        val message = when {
            rolledBack -> "Approved modification batch failed. Original project state was restored; 0 of ${modificationResults.size} changes were applied."
            failureCount == 0 -> "Applied approved modifications."
            successCount == 0 -> "Approved modifications failed to apply."
            else -> "Applied $successCount modification(s); $failureCount failed."
        }
        return ClaudeCodeStepResult.Completed(
            message = message,
            modifications = modificationResults,
            success = failureCount == 0,
            commitMessage = pending.commitMessage.takeIf { failureCount == 0 },
            commands = pending.commands.takeIf { failureCount == 0 }.orEmpty()
        )
    }

    private suspend fun applyModifications(
        requestedModifications: List<InteractionModification>
    ): List<ModificationResult> {
        val modifications = requestedModifications.mapNotNull {
            ProtocolConverter.convertModification(it)
        }
        if (modifications.isEmpty()) return emptyList()

        log("Applying ${modifications.size} modifications...")
        notificationPort.showProgress(
            "Applying ${modifications.size} changes...",
            0.8
        )

        val results = codeRepository.applyModifications(modifications)
        val successCount = results.count {
            it is ModificationResult.Success
        }
        val failureCount = results.size - successCount
        log("Modifications: $successCount success, $failureCount failed")
        return results
    }

    private fun immediateError(message: String): CodingAgentApprovalOutcome.Immediate =
        CodingAgentApprovalOutcome.Immediate(error(message))

    private fun error(message: String): ClaudeCodeStepResult.Error {
        val policy = CodingAgentProviderPolicy.forProvider(provider)
        println("[MaxVibes ${policy.logTag}] ERROR: $message")
        logger?.error(policy.logTag, message)
        return ClaudeCodeStepResult.Error(message)
    }

    private fun log(message: String) {
        val policy = CodingAgentProviderPolicy.forProvider(provider)
        println("[MaxVibes ${policy.logTag}] $message")
        logger?.info(policy.logTag, message)
    }
    fun reject(sessionId: String): Boolean {
        val pending = pendingStore.take(sessionId) ?: return false
        finishRejection(
            sessionId = sessionId,
            rejectedCount = pending.modifications.size,
            heldCommandCount = pending.commands.size,
            source = "explicitly"
        )
        return true
    }
    private fun finishRejection(
        sessionId: String,
        rejectedCount: Int,
        heldCommandCount: Int,
        source: String
    ) {
        sessionManager.transition(sessionId, ClipboardEvent.Approved)
        log("User rejected $rejectedCount pending modification(s) $source")
        sessionLog?.event(
            "pending modifications rejected",
            mapOf(
                "mods" to rejectedCount,
                "heldCommands" to heldCommandCount,
                "source" to source
            )
        )
    }
}

internal sealed interface CodingAgentApprovalOutcome {
    data class Continue(
        val command: CodingAgentTurnCommand
    ) : CodingAgentApprovalOutcome

    data class Immediate(
        val result: ClaudeCodeStepResult
    ) : CodingAgentApprovalOutcome
}
