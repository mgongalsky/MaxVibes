package com.maxvibes.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.maxvibes.application.port.input.ContextAwareRequest
import com.maxvibes.application.port.input.ContextAwareResult
import com.maxvibes.application.service.ClipboardStepResult
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.plugin.chat.ChatSession
import com.maxvibes.plugin.chat.MessageRole
import com.maxvibes.plugin.service.MaxVibesService
import kotlinx.coroutines.runBlocking

/**
 * Callback interface for ChatMessageController → ChatPanel communication.
 * Allows the controller to update the UI without direct field access.
 */
interface ChatPanelCallbacks {
    fun appendToChat(text: String)
    fun appendAssistantMessage(text: String)
    fun setInputEnabled(enabled: Boolean)
    fun setStatus(text: String)
    fun updateModeIndicator()
    fun updateBreadcrumb()
    fun registerElementPaths(modifications: List<ModificationResult>)
    fun formatMarkdown(text: String): String
}

/**
 * Handles message sending (API, Clipboard, CheapAPI) and response processing.
 *
 * Extracted from ChatPanel to separate message flow logic from UI setup.
 * Uses [ChatPanelCallbacks] to communicate UI updates back to the panel.
 */
class ChatMessageController(
    private val project: Project,
    private val service: MaxVibesService,
    private val callbacks: ChatPanelCallbacks
) {

    // ==================== API Mode ====================

    fun sendApiMessage(
        task: String,
        session: ChatSession,
        history: List<com.maxvibes.application.port.output.ChatMessageDTO>,
        isDryRun: Boolean,
        isPlanOnly: Boolean,
        globalContextFiles: List<String>
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MaxVibes: Processing...", true) {
            override fun run(indicator: ProgressIndicator) {
                service.notificationService.setProgressIndicator(indicator)
                runBlocking {
                    val req = ContextAwareRequest(
                        task = task, history = history, dryRun = isDryRun,
                        planOnly = isPlanOnly, globalContextFiles = globalContextFiles
                    )
                    val result = service.contextAwareModifyUseCase.execute(req)
                    ApplicationManager.getApplication().invokeLater {
                        handleApiResult(result, session)
                    }
                }
            }

            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    session.addMessage(MessageRole.SYSTEM, "Cancelled")
                    callbacks.appendToChat("\n\u26A0\uFE0F Cancelled\n")
                    callbacks.setInputEnabled(true)
                    callbacks.setStatus("Cancelled")
                }
            }
        })
    }

    // ==================== Cheap API Mode ====================

    fun sendCheapApiMessage(
        task: String,
        session: ChatSession,
        history: List<com.maxvibes.application.port.output.ChatMessageDTO>,
        isDryRun: Boolean,
        isPlanOnly: Boolean,
        globalContextFiles: List<String>
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MaxVibes: Processing (budget)...", true) {
            override fun run(indicator: ProgressIndicator) {
                service.notificationService.setProgressIndicator(indicator)
                runBlocking {
                    val req = ContextAwareRequest(
                        task = task, history = history, dryRun = isDryRun,
                        planOnly = isPlanOnly, globalContextFiles = globalContextFiles
                    )
                    val uc = service.cheapContextAwareModifyUseCase ?: service.contextAwareModifyUseCase
                    val result = uc.execute(req)
                    ApplicationManager.getApplication().invokeLater {
                        handleApiResult(result, session)
                    }
                }
            }

            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    session.addMessage(MessageRole.SYSTEM, "Cancelled")
                    callbacks.appendToChat("\n\u26A0\uFE0F Cancelled\n")
                    callbacks.setInputEnabled(true)
                    callbacks.setStatus("Cancelled")
                }
            }
        })
    }

    // ==================== Clipboard Mode ====================

    fun runClipboardBg(
        title: String,
        session: ChatSession,
        action: suspend () -> ClipboardStepResult
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MaxVibes: $title", true) {
            override fun run(indicator: ProgressIndicator) {
                service.notificationService.setProgressIndicator(indicator)
                runBlocking {
                    val result = action()
                    ApplicationManager.getApplication().invokeLater {
                        handleClipboardResult(result, session)
                    }
                }
            }

            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    service.clipboardService.reset()
                    callbacks.appendToChat("\n\u26A0\uFE0F Cancelled\n")
                    callbacks.setInputEnabled(true)
                    callbacks.updateModeIndicator()
                }
            }
        })
    }

    // ==================== Result Handlers ====================

    private fun handleApiResult(result: ContextAwareResult, session: ChatSession) {
        callbacks.registerElementPaths(result.modifications)

        val text = buildResultText(result)
        session.addMessage(MessageRole.ASSISTANT, text)
        callbacks.appendAssistantMessage(text)
        callbacks.appendToChat("\u2500".repeat(50) + "\n")
        callbacks.setInputEnabled(true)
        callbacks.setStatus(if (result.success) "Ready" else "Errors")
        callbacks.updateBreadcrumb()
    }

    /**
     * Handle clipboard result.
     * NOTE: result.message from ClipboardInteractionService already includes modification summary,
     * so we do NOT add a second summary here.
     */
    private fun handleClipboardResult(result: ClipboardStepResult, session: ChatSession) {
        when (result) {
            is ClipboardStepResult.WaitingForResponse -> {
                session.addMessage(MessageRole.ASSISTANT, result.userMessage)
                callbacks.appendToChat("\n\uD83D\uDCCB MaxVibes:\n${callbacks.formatMarkdown(result.userMessage)}\n")
                callbacks.appendToChat("\u2500".repeat(50) + "\n")
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("Waiting for LLM response...")
            }

            is ClipboardStepResult.Completed -> {
                callbacks.registerElementPaths(result.modifications)

                val text = result.message.ifBlank { "Done." }
                session.addMessage(MessageRole.ASSISTANT, text)
                callbacks.appendAssistantMessage(text)
                callbacks.appendToChat("\u2500".repeat(50) + "\n")
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                val hint = if (service.clipboardService.hasActiveSession()) " \u2022 Session active" else ""
                callbacks.setStatus((if (result.success) "Ready" else "Errors") + hint)
                callbacks.updateBreadcrumb()
            }

            is ClipboardStepResult.Error -> {
                session.addMessage(MessageRole.SYSTEM, "Error: ${result.message}")
                callbacks.appendToChat("\n\u274C ${result.message}\n")
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("Error")
            }
        }
    }

    // ==================== Helpers ====================

    private fun buildResultText(result: ContextAwareResult): String = buildString {
        append(result.message)
        if (result.modifications.isNotEmpty()) {
            appendLine("\n\n\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500")
            val ok = result.modifications.filterIsInstance<ModificationResult.Success>()
            val fail = result.modifications.filterIsInstance<ModificationResult.Failure>()
            if (ok.isNotEmpty()) {
                appendLine("\u2705 ${ok.size} applied:")
                ok.forEach { appendLine("   \u2022 ${ChatNavigationHelper.formatElementPath(it.affectedPath)}") }
            }
            if (fail.isNotEmpty()) {
                appendLine("\u274C ${fail.size} failed:")
                fail.forEach { appendLine("   \u2022 ${it.error.message}") }
            }
        }
    }

    companion object {
        fun buildTaskWithTrace(task: String, trace: String?): String {
            if (trace.isNullOrBlank()) return task
            return "$task\n\n--- Error/Trace/Logs ---\n$trace"
        }
    }
}