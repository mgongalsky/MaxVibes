package com.maxvibes.plugin.ui

import com.intellij.openapi.progress.ProcessCanceledException
import com.maxvibes.application.port.input.ContextAwareRequest
import com.maxvibes.application.port.input.ContextAwareResult
import com.maxvibes.application.service.ClipboardStepResult
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.command.CommandExecution
import com.maxvibes.plugin.service.MaxVibesLogger
import com.maxvibes.application.service.ClaudeCodeStepResult

/**
 * Owns interaction-specific background execution policy on top of
 * [BackgroundTaskRunner].
 *
 * Dispatchers remain synchronous and testable; this coordinator handles task
 * titles, error conversion, cancellation recovery and API use-case selection.
 */
internal class InteractionExecutionCoordinator(
    private val backgroundTaskRunner: BackgroundTaskRunner,
    private val inputStatusView: InputStatusView,
    private val appendToChat: (String) -> Unit,
    private val resetClipboardSession: (String) -> Unit,
    private val resetClaudeCodeSession: (String) -> Unit,
    private val addSystemMessage: (sessionId: String, text: String) -> Unit,
    private val executeApiRequest: suspend (
        useCheap: Boolean,
        request: ContextAwareRequest
    ) -> ContextAwareResult
) {
    fun runClipboard(
        title: String,
        session: ChatSession,
        action: suspend () -> ClipboardStepResult,
        onResult: (ClipboardStepResult) -> Unit
    ) {
        backgroundTaskRunner.run(
            title = "MaxVibes: $title",
            cancellable = true,
            action = {
                try {
                    action()
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Throwable) {
                    MaxVibesLogger.error(
                        "InteractionExecution",
                        "clipboard background action crashed",
                        e as? Exception ?: RuntimeException(e)
                    )
                    ClipboardStepResult.Error(
                        message = "Internal error: ${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                    )
                }
            },
            onSuccess = onResult,
            onCancel = {
                resetClipboardSession(session.id)
                appendToChat("⚠️ Cancelled")
                inputStatusView.setInputEnabled(true)
                inputStatusView.updateModeIndicator()
            }
        )
    }

    fun runClaudeCode(
        title: String,
        session: ChatSession,
        action: suspend () -> ClaudeCodeStepResult,
        onResult: (ClaudeCodeStepResult) -> Unit
    ) {
        inputStatusView.setStatus("Claude Code: running")
        backgroundTaskRunner.run(
            title = "MaxVibes: $title",
            cancellable = true,
            action = {
                try {
                    action()
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Throwable) {
                    MaxVibesLogger.error(
                        "InteractionExecution",
                        "claudeCode background action crashed",
                        e as? Exception ?: RuntimeException(e)
                    )
                    ClaudeCodeStepResult.Error(
                        message = "Internal error: ${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                    )
                }
            },
            onSuccess = onResult,
            onCancel = {
                resetClaudeCodeSession(session.id)
                appendToChat("⚠️ Cancelled")
                inputStatusView.setInputEnabled(true)
                inputStatusView.updateModeIndicator()
            }
        )
    }

    fun runApi(
        progressTitle: String,
        session: ChatSession,
        useCheap: Boolean,
        request: ContextAwareRequest,
        onResult: (ContextAwareResult) -> Unit
    ) {
        backgroundTaskRunner.run(
            title = "MaxVibes: $progressTitle...",
            cancellable = true,
            action = { executeApiRequest(useCheap, request) },
            onSuccess = onResult,
            onCancel = {
                addSystemMessage(session.id, "Cancelled")
                appendToChat("⚠️ Cancelled")
                inputStatusView.setInputEnabled(true)
                inputStatusView.setStatus("Cancelled")
                MaxVibesLogger.warn("InteractionExecution", "cancelled by user")
            }
        )
    }

    fun runCommand(
        action: suspend () -> CommandExecution,
        onResult: (CommandExecution) -> Unit
    ) {
        backgroundTaskRunner.run(
            title = "MaxVibes: Running command...",
            cancellable = false,
            publishIndicator = false,
            action = action,
            onSuccess = onResult
        )
    }
}
