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

interface ChatPanelCallbacks {
    fun appendToChat(text: String)
    fun appendAssistantMessage(text: String)
    fun setInputEnabled(enabled: Boolean)
    fun setStatus(text: String)
    fun updateModeIndicator()
    fun updateBreadcrumb()
    fun registerElementPaths(modifications: List<ModificationResult>)
    fun formatMarkdown(text: String): String
    fun updateTokenDisplay()
    fun addUserMessageBubble(text: String)
    fun addAssistantMessageBubble(
        text: String,
        tokenInfo: String?,
        modifications: List<ModificationResult>,
        metaFiles: List<String> = emptyList()
    )

    fun appendIconToLastBubble(icon: String)
    fun clearChatDisplay()
    fun setPlanOnlyMode(enabled: Boolean)
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
                    ApplicationManager.getApplication().invokeLater { handleApiResult(result, session, isPlanOnly) }
                }
            }

            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    session.addMessage(MessageRole.SYSTEM, "Cancelled")
                    callbacks.appendToChat("\u26A0\uFE0F Cancelled")
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
        ProgressManager.getInstance()
            .run(object : Task.Backgroundable(project, "MaxVibes: Processing (budget)...", true) {
                override fun run(indicator: ProgressIndicator) {
                    service.notificationService.setProgressIndicator(indicator)
                    runBlocking {
                        val req = ContextAwareRequest(
                            task = task, history = history, dryRun = isDryRun,
                            planOnly = isPlanOnly, globalContextFiles = globalContextFiles
                        )
                        val uc = service.cheapContextAwareModifyUseCase ?: service.contextAwareModifyUseCase
                        val result = uc.execute(req)
                        ApplicationManager.getApplication().invokeLater { handleApiResult(result, session, isPlanOnly) }
                    }
                }

                override fun onCancel() {
                    ApplicationManager.getApplication().invokeLater {
                        session.addMessage(MessageRole.SYSTEM, "Cancelled")
                        callbacks.appendToChat("\u26A0\uFE0F Cancelled")
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
                    ApplicationManager.getApplication().invokeLater { handleClipboardResult(result, session) }
                }
            }

            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    service.clipboardService.reset()
                    callbacks.appendToChat("\u26A0\uFE0F Cancelled")
                    callbacks.setInputEnabled(true)
                    callbacks.updateModeIndicator()
                }
            }
        })
    }

    // ==================== Result Handlers ====================

    private fun handleApiResult(result: ContextAwareResult, session: ChatSession, wasPlanOnly: Boolean = false) {
        callbacks.registerElementPaths(result.modifications)
        session.addPlanningTokens(result.planningInputTokens, result.planningOutputTokens)
        session.addChatTokens(result.chatInputTokens, result.chatOutputTokens)
        callbacks.updateTokenDisplay()

        val mainText = result.message
        session.addMessage(MessageRole.ASSISTANT, mainText)

        val tokenInfo = buildTokenInfo(
            result.planningInputTokens, result.planningOutputTokens,
            result.chatInputTokens, result.chatOutputTokens
        )
        callbacks.addAssistantMessageBubble(
            callbacks.formatMarkdown(mainText),
            tokenInfo,
            result.modifications
        )

        // Auto-uncheck Plan after receiving the plan response so next message implements it
        if (wasPlanOnly) {
            callbacks.setPlanOnlyMode(false)
        }

        callbacks.setInputEnabled(true)
        callbacks.setStatus(if (result.success) "Ready" else "Errors")
        callbacks.updateBreadcrumb()
    }

    private fun handleClipboardResult(result: ClipboardStepResult, session: ChatSession) {
        when (result) {
            is ClipboardStepResult.WaitingForResponse -> {
                session.addChatTokens(result.estimatedInputTokens, 0)
                callbacks.updateTokenDisplay()
                session.addMessage(MessageRole.ASSISTANT, result.userMessage)
                val reasoning = result.llmReasoning
                if (!reasoning.isNullOrBlank()) {
                    val tokenSummaryParts = mutableListOf<String>()
                    if (result.estimatedInputTokens > 0) tokenSummaryParts += "~${fmt(result.estimatedInputTokens)} tokens"
                    if (result.freshFileNames.isNotEmpty()) tokenSummaryParts += "${result.freshFileNames.size} files"
                    if (result.previouslyGatheredCount > 0) tokenSummaryParts += "prev: ${result.previouslyGatheredCount}"
                    tokenSummaryParts += result.phase.name.lowercase()
                    val tokenInfo = tokenSummaryParts.joinToString("  \u00B7  ")
                    callbacks.addAssistantMessageBubble(
                        callbacks.formatMarkdown(reasoning), tokenInfo, emptyList(), result.freshFileNames
                    )
                } else {
                    callbacks.appendIconToLastBubble("\uD83D\uDCCB")
                }
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("Waiting for LLM response...")
            }

            is ClipboardStepResult.Completed -> {
                callbacks.registerElementPaths(result.modifications)
                session.addChatTokens(0, result.outputTokens)
                callbacks.updateTokenDisplay()
                val text = result.message.trim().ifBlank { "Done." }
                session.addMessage(MessageRole.ASSISTANT, text)
                val tokenInfo = if (result.outputTokens > 0) "\u2193${fmt(result.outputTokens)}" else null
                callbacks.addAssistantMessageBubble(
                    callbacks.formatMarkdown(text), tokenInfo, result.modifications
                )
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                val hint = if (service.clipboardService.hasActiveSession()) " \u2022 Session active" else ""
                callbacks.setStatus((if (result.success) "Ready" else "Errors") + hint)
                callbacks.updateBreadcrumb()
            }

            is ClipboardStepResult.Error -> {
                session.addMessage(MessageRole.SYSTEM, "Error: ${result.message}")
                callbacks.appendToChat("\u274C ${result.message}")
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("Error")
            }
        }
    }

    // ==================== Helpers ====================

    private fun buildTokenInfo(planIn: Int, planOut: Int, chatIn: Int, chatOut: Int): String? {
        if (planIn + planOut + chatIn + chatOut == 0) return null
        val parts = mutableListOf<String>()
        if (planIn + planOut > 0) parts += "plan \u2191${fmt(planIn)} \u2193${fmt(planOut)}"
        if (chatIn + chatOut > 0) parts += "chat \u2191${fmt(chatIn)} \u2193${fmt(chatOut)}"
        val cost = (planIn + chatIn) / 1_000_000.0 * 3.0 + (planOut + chatOut) / 1_000_000.0 * 15.0
        parts += "~\$${String.format("%.3f", cost)}"
        return parts.joinToString("  \u00B7  ")
    }

    private fun fmt(n: Int) = if (n >= 1000) "${n / 1000}k" else n.toString()

    companion object {
        fun buildTaskWithTrace(task: String, trace: String?): String {
            if (trace.isNullOrBlank()) return task
            return "$task\n\n--- Error/Trace/Logs ---\n$trace"
        }
    }
}
