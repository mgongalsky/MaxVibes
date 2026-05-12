package com.maxvibes.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.maxvibes.application.port.input.ContextAwareRequest
import com.maxvibes.application.port.input.ContextAwareResult
import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.service.ClaudeCodeStepResult
import com.maxvibes.application.service.ClipboardStepResult
import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.plugin.service.MaxVibesLogger
import com.maxvibes.plugin.service.MaxVibesService
import kotlinx.coroutines.runBlocking
import com.maxvibes.shared.result.Result
import com.maxvibes.adapter.llm.dto.toChatMessageDTO
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.modification.AppliedModInfo
import com.maxvibes.domain.model.modification.toCategory
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

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
        metaFiles: List<String> = emptyList(),
        reasoning: String? = null,
        requestedViews: List<com.maxvibes.domain.model.code.RequestedViewInfo> = emptyList(),
        appliedModifications: List<AppliedModInfo> = emptyList()
    )

    fun appendIconToLastBubble(icon: String)
    fun clearChatDisplay()
    fun setPlanOnlyMode(enabled: Boolean)

    /** Sets the commit message in the IDE VCS commit dialog. */
    fun setCommitMessage(message: String)

    /** Called when attached trace or errors change. */
    fun onAttachmentsChanged(trace: String?, errors: String?)

    /** Called when a background operation encounters an error. */
    fun onError(message: String)

    /** Called when the active session changes (create, delete, branch, load). */
    fun onSessionChanged(session: ChatSession?)

    /** Called when a session is renamed. */
    fun onSessionRenamed(session: ChatSession)

    /** Called to show the welcome screen (e.g. no sessions). */
    fun onShowWelcome()
}

/**
 * Handles message sending (API, Clipboard, CheapAPI, ClaudeCode) and response processing.
 *
 * Extracted from ChatPanel to separate message flow logic from UI setup.
 * Uses [ChatPanelCallbacks] to communicate UI updates back to the panel.
 *
 * Auto-retry: when LLM response has ModificationResult.Failure entries,
 * the controller automatically sends a follow-up message to LLM with error details.
 * Limited to [MAX_AUTO_RETRIES] per user message.
 */
class ChatMessageController(
    private val project: Project,
    private val service: MaxVibesService,
    private val callbacks: ChatPanelCallbacks
) {

    private val chatTreeService get() = service.chatTreeService

    private data class ApiRequestContext(
        val isDryRun: Boolean,
        val isPlanOnly: Boolean,
        val globalContextFiles: List<String>,
        val ideErrors: String?
    )

    private var lastApiContext: ApiRequestContext? = null
    private var autoRetryCount = 0
    var attachedTrace: String? = null
        private set
    var attachedErrors: String? = null
        private set

    companion object {
        private const val MAX_AUTO_RETRIES = 1

        fun buildTaskWithContext(task: String, trace: String?, errs: String?): String {
            return buildString {
                append(task)
                if (!trace.isNullOrBlank()) append("\n\n--- Error/Trace/Logs ---\n$trace")
                if (!errs.isNullOrBlank()) append("\n\n--- IDE Errors ---\n$errs")
            }
        }
    }

    // ==================== API Mode ====================

    fun sendApiMessage(
        task: String,
        session: ChatSession,
        history: List<ChatMessageDTO>,
        isDryRun: Boolean,
        isPlanOnly: Boolean,
        globalContextFiles: List<String>,
        ideErrors: String? = null
    ) {
        lastApiContext = ApiRequestContext(isDryRun, isPlanOnly, globalContextFiles, ideErrors)
        autoRetryCount = 0
        runApiRequest(task, session, history, isDryRun, isPlanOnly, globalContextFiles, ideErrors, "Processing")
    }

    // ==================== Cheap API Mode ====================

    fun sendCheapApiMessage(
        task: String,
        session: ChatSession,
        history: List<ChatMessageDTO>,
        isDryRun: Boolean,
        isPlanOnly: Boolean,
        globalContextFiles: List<String>,
        ideErrors: String? = null
    ) {
        lastApiContext = ApiRequestContext(isDryRun, isPlanOnly, globalContextFiles, ideErrors)
        autoRetryCount = 0
        ProgressManager.getInstance()
            .run(object : Task.Backgroundable(project, "MaxVibes: Processing (budget)...", true) {
                override fun run(indicator: ProgressIndicator) {
                    service.notificationService.setProgressIndicator(indicator)
                    runBlocking {
                        val req = ContextAwareRequest(
                            currentMessage = task, history = history, dryRun = isDryRun,
                            planOnly = isPlanOnly, globalContextFiles = globalContextFiles,
                            ideErrors = ideErrors
                        )
                        val uc = service.cheapContextAwareModifyUseCase ?: service.contextAwareModifyUseCase
                        val result = uc.execute(req)
                        ApplicationManager.getApplication().invokeLater { handleApiResult(result, session, isPlanOnly) }
                    }
                }

                override fun onCancel() {
                    ApplicationManager.getApplication().invokeLater {
                        chatTreeService.addMessage(session.id, MessageRole.SYSTEM, "Cancelled")
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
                    service.clipboardService.reset(session.id)
                    callbacks.appendToChat("\u26A0\uFE0F Cancelled")
                    callbacks.setInputEnabled(true)
                    callbacks.updateModeIndicator()
                }
            }
        })
    }

    /**
     * Re-generates and copies the clipboard JSON for the current active session.
     *
     * Runs in a background task — re-gathers project files and rebuilds the full JSON payload via
     * [ClipboardInteractionService.redoLastRequest]. Does NOT add a new user message to history.
     */
    fun redoClipboardJson() {
        val session = chatTreeService.getActiveSession()
        val globalContextFiles = chatTreeService.getGlobalContextFiles()
        callbacks.setInputEnabled(false)
        runClipboardBg("Re-generating JSON...", session) {
            service.clipboardService.redoLastRequest(session.id, globalContextFiles)
        }
    }

    // ==================== Claude Code Mode ====================

    /**
     * Background-task helper for Claude Code interactions.
     *
     * Mirrors [runClipboardBg] but dispatches results through [handleClaudeCodeResult]
     * and resets the Claude Code session (not the clipboard one) on cancel.
     *
     * Spawns a side ticker that updates both the progress indicator text and the
     * status label once per second so long sends (claude can take 2-3 minutes for
     * non-trivial refactors) no longer look like the plugin froze. The ticker
     * lives strictly for the duration of [action] and is cancelled in `finally` —
     * cancellation propagates through `delay()` as `CancellationException`, which
     * we swallow silently, so an unconditional loop is equivalent to `while (isActive)`
     * and avoids depending on the CoroutineScope.isActive extension property.
     */
    private fun runClaudeCodeBg(
        title: String,
        session: ChatSession,
        action: suspend () -> ClaudeCodeStepResult
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MaxVibes: $title", true) {
            override fun run(indicator: ProgressIndicator) {
                service.notificationService.setProgressIndicator(indicator)
                runBlocking {
                    val startedAt = System.currentTimeMillis()
                    val tickerScope = kotlinx.coroutines.CoroutineScope(
                        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
                    )
                    val ticker = tickerScope.launch {
                        try {
                            while (true) {
                                kotlinx.coroutines.delay(1000)
                                val secs = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                                // ProgressIndicator text is safe to set from any thread.
                                indicator.text = "MaxVibes: $title (${secs}s)"
                                ApplicationManager.getApplication().invokeLater {
                                    callbacks.setStatus("Claude Code: $title (${secs}s)")
                                }
                            }
                        } catch (_: kotlinx.coroutines.CancellationException) {
                            // Expected on completion — no-op.
                        }
                    }
                    try {
                        val result = action()
                        ApplicationManager.getApplication().invokeLater { handleClaudeCodeResult(result, session) }
                    } finally {
                        ticker.cancel()
                        tickerScope.cancel()
                    }
                }
            }

            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    service.claudeCodeService.reset(session.id)
                    callbacks.appendToChat("\u26A0\uFE0F Cancelled")
                    callbacks.setInputEnabled(true)
                    callbacks.updateModeIndicator()
                }
            }
        })
    }

    /**
     * Approve the last Claude Code response — gather the files the LLM requested
     * and send a minimal-context follow-up. Called from [ChatPanel] when the user
     * clicks the Approve button.
     */
    fun approve() {
        val session = chatTreeService.getActiveSession()
        val trace = attachedTrace
        val errs = attachedErrors
        clearAttachmentsAfterSend()
        MaxVibesLogger.info(
            "Controller", "approve", mapOf(
                "sessionId" to session.id,
                "hasTrace" to (trace != null),
                "hasErrors" to (errs != null)
            )
        )
        callbacks.setInputEnabled(false)
        callbacks.setStatus("Claude Code: approving...")
        runClaudeCodeBg("Claude Code: approving...", session) {
            service.claudeCodeService.approve(
                sessionId = session.id,
                attachedContext = trace,
                ideErrors = errs
            )
        }
    }

    // ==================== Result Handlers ====================

    private fun handleApiResult(result: ContextAwareResult, session: ChatSession, wasPlanOnly: Boolean = false) {
        callbacks.registerElementPaths(result.modifications)

        var updatedSession =
            chatTreeService.addPlanningTokens(session.id, result.planningInputTokens, result.planningOutputTokens)
        updatedSession =
            chatTreeService.addChatTokens(updatedSession.id, result.chatInputTokens, result.chatOutputTokens)

        val failures = result.modifications.filterIsInstance<ModificationResult.Failure>()

        MaxVibesLogger.info(
            "Controller", "apiResult", mapOf(
                "success" to result.success,
                "mods" to result.modifications.size,
                "failures" to failures.size,
                "planIn" to result.planningInputTokens,
                "planOut" to result.planningOutputTokens,
                "chatIn" to result.chatInputTokens,
                "chatOut" to result.chatOutputTokens,
                "hasCommitMsg" to (result.commitMessage != null),
                "autoRetryCount" to autoRetryCount
            )
        )

        val mainText = result.message

        val tokenInfo = buildTokenInfo(
            result.planningInputTokens, result.planningOutputTokens,
            result.chatInputTokens, result.chatOutputTokens
        )
        val appliedPaths = result.modifications
            .filterIsInstance<ModificationResult.Success>()
            .map { it.affectedPath.toString() }

        val appliedMods = result.modifications
            .filterIsInstance<ModificationResult.Success>()
            .map { AppliedModInfo(path = it.affectedPath.toString(), category = it.modification.toCategory()) }

        updatedSession = chatTreeService.addMessage(
            updatedSession.id, MessageRole.ASSISTANT, mainText,
            appliedModificationPaths = appliedPaths,
            tokenInfo = tokenInfo,
            appliedModifications = appliedMods,
            requestedViews = result.requestedViews
        )
        callbacks.updateTokenDisplay()

        callbacks.addAssistantMessageBubble(
            text = callbacks.formatMarkdown(mainText),
            tokenInfo = tokenInfo,
            modifications = result.modifications,
            metaFiles = emptyList(),
            reasoning = null,
            requestedViews = result.requestedViews,
            appliedModifications = appliedMods
        )

        result.commitMessage?.let { msg ->
            callbacks.setCommitMessage(msg)
            callbacks.appendToChat("\uD83D\uDCAC Commit message set in IDE")
        }

        if (wasPlanOnly) {
            callbacks.setPlanOnlyMode(false)
        }

        if (failures.isNotEmpty() && autoRetryCount < MAX_AUTO_RETRIES) {
            val ctx = lastApiContext
            if (ctx != null) {
                triggerAutoRetry(failures, updatedSession, ctx)
                return
            }
        }

        callbacks.setInputEnabled(true)
        if (failures.isNotEmpty()) {
            callbacks.setStatus("Done \u2014 ${failures.size} modification(s) failed (retry exhausted)")
        } else {
            callbacks.setStatus(if (result.success) "Ready" else "Errors")
        }
        callbacks.updateBreadcrumb()
    }

    private fun handleClipboardResult(result: ClipboardStepResult, session: ChatSession) {
        when (result) {
            is ClipboardStepResult.WaitingForResponse -> {
                MaxVibesLogger.info(
                    "Controller", "clipboard waiting", mapOf(
                        "phase" to result.phase.name,
                        "estimatedTokens" to result.estimatedInputTokens,
                        "freshFiles" to result.freshFileNames.size
                    )
                )
                var updatedSession = chatTreeService.addChatTokens(session.id, result.estimatedInputTokens, 0)

                val assistantText = result.assistantMessage

                val tokenInfo: String? = if (!assistantText.isNullOrBlank()) {
                    val parts = mutableListOf<String>()
                    if (result.estimatedInputTokens > 0) parts += "~${fmt(result.estimatedInputTokens)} tokens"
                    if (result.freshFileNames.isNotEmpty()) parts += "${result.freshFileNames.size} files"
                    parts += result.phase.name.lowercase()
                    parts.joinToString("  \u00B7  ")
                } else null

                if (!assistantText.isNullOrBlank()) {
                    updatedSession = chatTreeService.addMessage(
                        updatedSession.id, MessageRole.ASSISTANT, assistantText,
                        attachedFiles = result.freshFileNames,
                        tokenInfo = tokenInfo,
                        reasoning = result.llmReasoning,
                        requestedViews = result.requestedViews
                    )
                }
                callbacks.updateTokenDisplay()

                if (!assistantText.isNullOrBlank()) {
                    callbacks.addAssistantMessageBubble(
                        text = callbacks.formatMarkdown(assistantText),
                        tokenInfo = tokenInfo,
                        modifications = emptyList(),
                        metaFiles = result.freshFileNames,
                        reasoning = result.llmReasoning,
                        requestedViews = result.requestedViews,
                        appliedModifications = emptyList()
                    )
                } else {
                    callbacks.appendIconToLastBubble("\uD83D\uDCCB")
                }

                callbacks.appendToChat(result.statusMessage)
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("Waiting for LLM response...")
            }

            is ClipboardStepResult.Completed -> {
                val failures = result.modifications.filterIsInstance<ModificationResult.Failure>()
                MaxVibesLogger.info(
                    "Controller", "clipboard completed", mapOf(
                        "success" to result.success,
                        "mods" to result.modifications.size,
                        "failures" to failures.size,
                        "outputTokens" to result.outputTokens,
                        "hasCommitMsg" to (result.commitMessage != null)
                    )
                )
                callbacks.registerElementPaths(result.modifications)

                var updatedSession = chatTreeService.addChatTokens(session.id, 0, result.outputTokens)
                val text = result.message.trim().ifBlank { "Done." }
                val tokenInfo = if (result.outputTokens > 0) "\u2193${fmt(result.outputTokens)}" else null

                val appliedPaths = result.modifications
                    .filterIsInstance<ModificationResult.Success>()
                    .map { it.affectedPath.toString() }

                val appliedMods = result.modifications
                    .filterIsInstance<ModificationResult.Success>()
                    .map { AppliedModInfo(path = it.affectedPath.toString(), category = it.modification.toCategory()) }

                updatedSession = chatTreeService.addMessage(
                    updatedSession.id, MessageRole.ASSISTANT, text,
                    appliedModificationPaths = appliedPaths,
                    tokenInfo = tokenInfo,
                    reasoning = result.llmReasoning,
                    appliedModifications = appliedMods
                )

                if (failures.isNotEmpty()) {
                    val errorSummary = buildErrorSummary(failures)
                    val feedbackMsg = buildString {
                        appendLine("\u274C ${failures.size} modification(s) failed to apply:")
                        appendLine(errorSummary)
                        appendLine()
                        appendLine("\uD83D\uDCCB A retry prompt has been prepared. Paste it into your LLM and paste the response back here.")
                    }
                    chatTreeService.addMessage(updatedSession.id, MessageRole.SYSTEM, feedbackMsg)
                    callbacks.updateTokenDisplay()

                    callbacks.addAssistantMessageBubble(
                        text = callbacks.formatMarkdown(text),
                        tokenInfo = tokenInfo,
                        modifications = result.modifications,
                        metaFiles = emptyList(),
                        reasoning = result.llmReasoning,
                        requestedViews = emptyList(),
                        appliedModifications = appliedMods
                    )
                    result.commitMessage?.let { msg ->
                        callbacks.setCommitMessage(msg)
                        callbacks.appendToChat("\uD83D\uDCAC Commit message set in IDE")
                    }
                    callbacks.appendToChat(feedbackMsg)

                    val retryTask = buildClipboardRetryTask(failures)
                    copyToClipboard(retryTask)

                    callbacks.setInputEnabled(true)
                    callbacks.updateModeIndicator()
                    callbacks.setStatus("\u26A0\uFE0F ${failures.size} failed \u2014 retry prompt copied, paste LLM response")
                } else {
                    callbacks.updateTokenDisplay()

                    callbacks.addAssistantMessageBubble(
                        text = callbacks.formatMarkdown(text),
                        tokenInfo = tokenInfo,
                        modifications = result.modifications,
                        metaFiles = emptyList(),
                        reasoning = result.llmReasoning,
                        requestedViews = emptyList(),
                        appliedModifications = appliedMods
                    )
                    result.commitMessage?.let { msg ->
                        callbacks.setCommitMessage(msg)
                        callbacks.appendToChat("\uD83D\uDCAC Commit message set in IDE")
                    }

                    callbacks.setInputEnabled(true)
                    callbacks.updateModeIndicator()
                    val isSessionActive = service.clipboardService.status(session.id) != ClipboardSessionStatus.IDLE
                    val hint = if (isSessionActive) " \u2022 Session active" else ""
                    callbacks.setStatus((if (result.success) "Ready" else "Errors") + hint)
                    callbacks.updateBreadcrumb()
                }
            }

            is ClipboardStepResult.ParseError -> {
                MaxVibesLogger.warn(
                    "Controller", "clipboard parse error", data = mapOf(
                        "reason" to result.errorDetail.take(80),
                        "clipboardCopied" to result.clipboardCopySucceeded
                    )
                )
                chatTreeService.addMessage(session.id, MessageRole.SYSTEM, "Parse error: ${result.errorDetail}")
                callbacks.appendToChat("\u26A0\uFE0F ${result.humanMessage}")
                if (result.errorDetail.isNotBlank()) {
                    callbacks.appendToChat("Details: ${result.errorDetail}")
                }
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("\u26A0\uFE0F Parse error \u2014 paste corrected LLM response")
            }

            is ClipboardStepResult.Error -> {
                MaxVibesLogger.warn("Controller", "clipboard error", data = mapOf("msg" to result.message))
                chatTreeService.addMessage(session.id, MessageRole.SYSTEM, "Error: ${result.message}")
                callbacks.appendToChat("\u274C ${result.message}")
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("Error")
            }
        }
    }

    /**
     * Renders a [ClaudeCodeStepResult] to the chat. Mirrors [handleClipboardResult]
     * but uses the Claude Code result type; auto-applies modifications when present.
     *
     * Persistence: writes the assistant message to [chatTreeService] in both
     * `WaitingForApprove` and `Completed` branches so that the domain session has
     * an ASSISTANT entry the service can later attach requestedViews to and that
     * approve() can look up. Without this write, the session is empty from the
     * domain's perspective and approve() fails with "No assistant message to approve".
     */
    private fun handleClaudeCodeResult(result: ClaudeCodeStepResult, session: ChatSession) {
        when (result) {
            is ClaudeCodeStepResult.WaitingForApprove -> {
                MaxVibesLogger.info(
                    "Controller", "claudeCode awaiting approve", mapOf(
                        "requestedViews" to result.requestedViews.size,
                        "in" to result.inputTokens,
                        "out" to result.outputTokens,
                        "durationMs" to result.durationMs
                    )
                )
                chatTreeService.addChatTokens(session.id, result.inputTokens, result.outputTokens)

                val tokenInfo = buildTokenInfoForClaudeCode(
                    inTok = result.inputTokens,
                    outTok = result.outputTokens,
                    durationMs = result.durationMs
                )

                // Persist the assistant message to the domain so:
                //   1) the UI can re-render it after restart,
                //   2) ClaudeCodeInteractionService.approve() can find an ASSISTANT
                //      message in session.messages and read its requestedViews.
                chatTreeService.addMessage(
                    session.id, MessageRole.ASSISTANT, result.assistantMessage,
                    tokenInfo = tokenInfo,
                    reasoning = result.llmReasoning,
                    requestedViews = result.requestedViews
                )

                callbacks.updateTokenDisplay()

                callbacks.addAssistantMessageBubble(
                    text = callbacks.formatMarkdown(result.assistantMessage),
                    tokenInfo = tokenInfo,
                    modifications = emptyList(),
                    metaFiles = emptyList(),
                    reasoning = result.llmReasoning,
                    requestedViews = result.requestedViews,
                    appliedModifications = emptyList()
                )

                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("\uD83E\uDD16 Awaiting approval \u2014 click Approve to gather files and continue")
            }

            is ClaudeCodeStepResult.Completed -> {
                val failures = result.modifications.filterIsInstance<ModificationResult.Failure>()
                MaxVibesLogger.info(
                    "Controller", "claudeCode completed", mapOf(
                        "success" to result.success,
                        "mods" to result.modifications.size,
                        "failures" to failures.size,
                        "in" to result.inputTokens,
                        "out" to result.outputTokens,
                        "durationMs" to result.durationMs,
                        "hasCommitMsg" to (result.commitMessage != null)
                    )
                )
                callbacks.registerElementPaths(result.modifications)

                chatTreeService.addChatTokens(session.id, result.inputTokens, result.outputTokens)
                val text = result.message.trim().ifBlank { "Done." }
                val tokenInfo = buildTokenInfoForClaudeCode(
                    inTok = result.inputTokens,
                    outTok = result.outputTokens,
                    durationMs = result.durationMs
                )

                val appliedPaths = result.modifications
                    .filterIsInstance<ModificationResult.Success>()
                    .map { it.affectedPath.toString() }

                val appliedMods = result.modifications
                    .filterIsInstance<ModificationResult.Success>()
                    .map { AppliedModInfo(path = it.affectedPath.toString(), category = it.modification.toCategory()) }

                // Mirror the clipboard/API flows: persist the assistant message + applied
                // modifications to the domain so the conversation survives IDE restart.
                chatTreeService.addMessage(
                    session.id, MessageRole.ASSISTANT, text,
                    appliedModificationPaths = appliedPaths,
                    tokenInfo = tokenInfo,
                    reasoning = result.llmReasoning,
                    appliedModifications = appliedMods
                )

                callbacks.updateTokenDisplay()

                callbacks.addAssistantMessageBubble(
                    text = callbacks.formatMarkdown(text),
                    tokenInfo = tokenInfo,
                    modifications = result.modifications,
                    metaFiles = emptyList(),
                    reasoning = result.llmReasoning,
                    requestedViews = emptyList(),
                    appliedModifications = appliedMods
                )

                result.commitMessage?.let { msg ->
                    callbacks.setCommitMessage(msg)
                    callbacks.appendToChat("\uD83D\uDCAC Commit message set in IDE")
                }

                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                if (failures.isNotEmpty()) {
                    callbacks.setStatus("\u26A0\uFE0F ${failures.size} modification(s) failed")
                } else {
                    callbacks.setStatus(if (result.success) "Ready" else "Errors")
                }
                callbacks.updateBreadcrumb()
            }

            is ClaudeCodeStepResult.Error -> {
                MaxVibesLogger.warn("Controller", "claudeCode error", data = mapOf("msg" to result.message))
                chatTreeService.addMessage(session.id, MessageRole.SYSTEM, "Claude Code error: ${result.message}")
                callbacks.appendToChat("\u274C ${result.message}")
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("Claude Code error")
            }

            is ClaudeCodeStepResult.TransportError -> {
                MaxVibesLogger.warn(
                    "Controller",
                    "claudeCode transport error",
                    data = mapOf("detail" to result.detail)
                )
                chatTreeService.addMessage(
                    session.id,
                    MessageRole.SYSTEM,
                    "Claude Code transport error: ${result.detail}"
                )
                callbacks.appendToChat("\u274C Transport: ${result.detail}")
                callbacks.appendToChat("Check Claude Code settings (binary path, args) and retry.")
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("\u26A0\uFE0F Claude Code transport error")
            }
        }
    }

    /**
     * Formats the bubble-footer info line for Claude Code turns.
     * Layout: `↑1234 · ↓567 · 42s` — components are omitted when their value is zero.
     * Returns null when nothing meaningful is available so the bubble suppresses the footer.
     */
    private fun buildTokenInfoForClaudeCode(inTok: Int, outTok: Int, durationMs: Long): String? {
        val parts = mutableListOf<String>()
        if (inTok > 0) parts += "\u2191${fmt(inTok)}"
        if (outTok > 0) parts += "\u2193${fmt(outTok)}"
        if (durationMs >= 1000) parts += "${durationMs / 1000}s"
        return if (parts.isEmpty()) null else parts.joinToString("  \u00B7  ")
    }

    // ==================== Auto-Retry Logic ====================

    private fun triggerAutoRetry(
        failures: List<ModificationResult.Failure>,
        session: ChatSession,
        ctx: ApiRequestContext
    ) {
        autoRetryCount++
        val errorSummary = buildErrorSummary(failures)
        val retryTask = buildApiRetryTask(failures)

        MaxVibesLogger.warn(
            "Controller", "autoRetry",
            data = mapOf("attempt" to autoRetryCount, "failures" to failures.size)
        )

        val feedbackMsg =
            "\uD83D\uDD04 Auto-fix: ${failures.size} modification(s) failed \u2014 asking LLM to correct:\n$errorSummary"
        callbacks.appendToChat(feedbackMsg)
        callbacks.setStatus("Auto-fixing...")

        val history = session.messages.map { it.toChatMessageDTO() }
        val retrySession = chatTreeService.addMessage(session.id, MessageRole.USER, retryTask)

        runApiRequest(
            task = retryTask,
            session = retrySession,
            history = history,
            isDryRun = ctx.isDryRun,
            isPlanOnly = false,
            globalContextFiles = ctx.globalContextFiles,
            ideErrors = ctx.ideErrors,
            progressTitle = "Auto-fixing"
        )
    }

    private fun runApiRequest(
        task: String,
        session: ChatSession,
        history: List<ChatMessageDTO>,
        isDryRun: Boolean,
        isPlanOnly: Boolean,
        globalContextFiles: List<String>,
        ideErrors: String?,
        progressTitle: String
    ) {
        ProgressManager.getInstance()
            .run(object : Task.Backgroundable(project, "MaxVibes: $progressTitle...", true) {
                override fun run(indicator: ProgressIndicator) {
                    service.notificationService.setProgressIndicator(indicator)
                    runBlocking {
                        val req = ContextAwareRequest(
                            currentMessage = task, history = history, dryRun = isDryRun,
                            planOnly = isPlanOnly, globalContextFiles = globalContextFiles,
                            ideErrors = ideErrors
                        )
                        val result = service.contextAwareModifyUseCase.execute(req)
                        ApplicationManager.getApplication().invokeLater { handleApiResult(result, session, isPlanOnly) }
                    }
                }

                override fun onCancel() {
                    ApplicationManager.getApplication().invokeLater {
                        chatTreeService.addMessage(session.id, MessageRole.SYSTEM, "Cancelled")
                        callbacks.appendToChat("\u26A0\uFE0F Cancelled")
                        callbacks.setInputEnabled(true)
                        callbacks.setStatus("Cancelled")
                        MaxVibesLogger.warn("Controller", "cancelled by user")
                    }
                }
            })
    }

    // ==================== Helpers ====================

    private fun buildErrorSummary(failures: List<ModificationResult.Failure>): String =
        failures.joinToString("\n") { f -> "  \u2022 ${f.error.message}" }

    private fun buildApiRetryTask(failures: List<ModificationResult.Failure>): String {
        val errorLines = buildErrorSummary(failures)
        return """
The following modifications from your last response FAILED to apply:
$errorLines

Please provide CORRECTED modifications only for the ones that failed.
Common causes:
- Wrong element path (class/function name mismatch or wrong nesting)
- Element doesn't exist yet \u2014 use CREATE_ELEMENT instead of REPLACE_ELEMENT
- For CREATE_ELEMENT, path must point to parent, not the new element itself

Respond with a JSON containing only the corrected modifications.
""".trimIndent()
    }

    private fun buildClipboardRetryTask(failures: List<ModificationResult.Failure>): String {
        val errorLines = buildErrorSummary(failures)
        return """
The following modifications FAILED to apply in the IDE:
$errorLines

Please provide CORRECTED modifications for the ones that failed.
Check:
- Element path must match exactly (class name, function name, nesting)
- Use CREATE_ELEMENT if element doesn't exist yet
- Parent path must exist for CREATE_ELEMENT
""".trimIndent()
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            val selection = java.awt.datatransfer.StringSelection(text)
            clipboard.setContents(selection, selection)
        } catch (e: Exception) {
            MaxVibesLogger.error("Controller", "copyToClipboard failed", e)
        }
    }

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

    fun attachTrace(traceContent: String) {
        attachedTrace = traceContent
        callbacks.onAttachmentsChanged(attachedTrace, attachedErrors)
    }

    fun clearTrace() {
        attachedTrace = null
        callbacks.onAttachmentsChanged(attachedTrace, attachedErrors)
    }

    fun clearErrors() {
        attachedErrors = null
        callbacks.onAttachmentsChanged(attachedTrace, attachedErrors)
    }

    fun fetchIdeErrors() {
        callbacks.setStatus("Fetching IDE errors...")
        object : Task.Backgroundable(project, "Fetching IDE errors", false) {
            override fun run(indicator: ProgressIndicator) {
                runBlocking {
                    val result = service.ideErrorsPort.getCompilerErrors()
                    ApplicationManager.getApplication().invokeLater {
                        when (result) {
                            is Result.Success -> {
                                val errors = result.value
                                if (errors.isEmpty()) {
                                    callbacks.setStatus("No IDE errors found in open files")
                                } else {
                                    attachedErrors = errors.joinToString("\n") { it.formatForLlm() }
                                    callbacks.setStatus("Attached ${errors.size} IDE errors")
                                    callbacks.onAttachmentsChanged(attachedTrace, attachedErrors)
                                }
                            }

                            is Result.Failure -> callbacks.onError("Failed to fetch IDE errors: ${result.error}")
                        }
                    }
                }
            }
        }.queue()
    }

    fun clearAttachmentsAfterSend() {
        attachedTrace = null
        attachedErrors = null
        callbacks.onAttachmentsChanged(null, null)
    }

    fun createNewSession() {
        val newSession = chatTreeService.createNewSession()
        callbacks.onSessionChanged(newSession)
    }

    fun deleteCurrentSession(sessionId: String) {
        chatTreeService.deleteSession(sessionId)
        val next = chatTreeService.getActiveSession()
        callbacks.onSessionChanged(next)
    }

    fun renameSession(sessionId: String, newTitle: String) {
        val updated = chatTreeService.renameSession(sessionId, newTitle)
        if (updated != null) callbacks.onSessionRenamed(updated)
    }

    fun branchSession(parentSessionId: String, title: String) {
        val newSession = chatTreeService.createBranch(parentSessionId, title)
        if (newSession != null) callbacks.onSessionChanged(newSession)
    }

    fun loadSession(sessionId: String) {
        chatTreeService.setActiveSession(sessionId)
        val session = chatTreeService.getSessionById(sessionId)
        if (session != null) callbacks.onSessionChanged(session)
    }

    /**
     * Updates the selected specific prompt for the currently active session.
     * Null means "Just Code" — no specific prompt.
     */
    fun selectSpecificPrompt(name: String?) {
        val session = chatTreeService.getActiveSession() ?: return
        val updated = session.withSelectedPrompt(name)
        chatTreeService.saveSession(updated)
        callbacks.onSessionChanged(updated)
    }

    fun sendMessage(
        userInput: String,
        isPlanOnly: Boolean,
        isDryRun: Boolean,
        mode: InteractionMode,
        addHistory: Boolean = false,
        selectedSpecificPromptName: String? = null
    ) {
        val trace = attachedTrace
        val errs = attachedErrors
        clearAttachmentsAfterSend()
        MaxVibesLogger.info(
            "Controller", "sendMessage", mapOf(
                "mode" to mode.name,
                "msgLen" to userInput.length,
                "isPlanOnly" to isPlanOnly,
                "hasTrace" to (trace != null),
                "hasErrors" to (errs != null),
                "addHistory" to addHistory,
                "specificPrompt" to (selectedSpecificPromptName ?: "null")
            )
        )
        when (mode) {
            InteractionMode.API -> dispatchApiMessage(userInput, trace, errs, isPlanOnly, isDryRun)
            InteractionMode.CLIPBOARD -> dispatchClipboardMessage(
                userInput,
                trace,
                errs,
                isPlanOnly,
                addHistory,
                selectedSpecificPromptName
            )

            InteractionMode.CHEAP_API -> dispatchCheapApiMessage(userInput, trace, errs, isPlanOnly, isDryRun)
            InteractionMode.CLAUDE_CODE -> dispatchClaudeCodeMessage(
                userInput,
                trace,
                errs,
                isPlanOnly,
                selectedSpecificPromptName
            )
        }
    }

    private fun dispatchApiMessage(msg: String, trace: String?, errs: String?, isPlanOnly: Boolean, isDryRun: Boolean) {
        var session = chatTreeService.getActiveSession()
        val fullTask = buildString {
            append(msg)
            if (!trace.isNullOrBlank()) append("\n[trace: ${trace.lines().size} lines]")
            if (!errs.isNullOrBlank()) append("\n[attached ide errors]")
            if (isPlanOnly) append("\n[plan-only]")
        }
        val history = session.messages.map { it.toChatMessageDTO() }
        session = chatTreeService.addMessage(session.id, MessageRole.USER, fullTask)
        callbacks.addUserMessageBubble(msg)
        callbacks.setInputEnabled(false)
        callbacks.setStatus(if (isPlanOnly) "Planning..." else "Thinking...")
        sendApiMessage(fullTask, session, history, isDryRun, isPlanOnly, chatTreeService.getGlobalContextFiles(), errs)
    }

    private fun dispatchClipboardMessage(
        userInput: String,
        trace: String?,
        errs: String?,
        isPlanOnly: Boolean,
        addHistory: Boolean = false,
        selectedSpecificPromptName: String? = null
    ) {
        val cs = service.clipboardService
        var session = chatTreeService.getActiveSession()
        val globalContextFiles = chatTreeService.getGlobalContextFiles()
        val currentStatus = session.clipboardStatus

        // Capture history BEFORE mutating session.
        val history = session.messages.map { it.toChatMessageDTO() }

        // Resolve prompt content from the name already captured in the UI state snapshot —
        // avoids a second repository read that could race with a just-saved selectSpecificPrompt.
        val specificPromptContent = service.specificPromptService.resolvePromptContent(selectedSpecificPromptName)

        when (currentStatus) {
            ClipboardSessionStatus.AWAITING_PASTE -> {
                callbacks.appendIconToLastBubble("\uD83D\uDCE5")
            }

            else -> {
                val fullMsg = buildString {
                    append(userInput)
                    if (!trace.isNullOrBlank()) append("\n[trace: ${trace.lines().size} lines]")
                    if (!errs.isNullOrBlank()) append("\n[attached ide errors]")
                    if (isPlanOnly) append("\n[plan-only]")
                }
                session = chatTreeService.addMessage(session.id, MessageRole.USER, fullMsg)
                callbacks.addUserMessageBubble(userInput)
            }
        }

        callbacks.setInputEnabled(false)
        val statusText = when (currentStatus) {
            ClipboardSessionStatus.AWAITING_PASTE -> "Processing response..."
            ClipboardSessionStatus.SESSION_ACTIVE -> "Continuing..."
            ClipboardSessionStatus.IDLE -> "Generating JSON..."
            // Clipboard mode should never see AWAITING_APPROVE (Claude Code-only); fall back gracefully.
            ClipboardSessionStatus.AWAITING_APPROVE -> "Awaiting approval..."
        }
        callbacks.setStatus(statusText)

        val capturedSession = session
        runClipboardBg(statusText, capturedSession) {
            cs.handleUserInput(
                sessionId = capturedSession.id,
                userInput = userInput,
                history = history,
                attachedContext = trace,
                planOnly = isPlanOnly,
                ideErrors = errs,
                globalContextFiles = globalContextFiles,
                addHistory = addHistory,
                specificPromptContent = specificPromptContent
            )
        }
    }

    private fun dispatchCheapApiMessage(
        msg: String,
        trace: String?,
        errs: String?,
        isPlanOnly: Boolean,
        isDryRun: Boolean
    ) {
        var session = chatTreeService.getActiveSession()
        val fullTask = buildTaskWithContext(msg, trace, errs)
        val history = session.messages.map { it.toChatMessageDTO() }
        session = chatTreeService.addMessage(session.id, MessageRole.USER, fullTask)
        callbacks.addUserMessageBubble(msg)
        callbacks.setInputEnabled(false)
        callbacks.setStatus("Thinking (cheap)...")
        @Suppress("DEPRECATION")
        service.ensureCheapLLMService()
        sendCheapApiMessage(
            fullTask,
            session,
            history,
            isDryRun,
            isPlanOnly,
            chatTreeService.getGlobalContextFiles(),
            errs
        )
    }

    /**
     * Dispatches a user message in Claude Code mode.
     *
     * Adds the user message to the session, then sends it to [com.maxvibes.application.service.ClaudeCodeInteractionService.handleUserInput]
     * via [runClaudeCodeBg]. The service decides whether to start a fresh process or
     * continue an existing one. The result is rendered through [handleClaudeCodeResult].
     */
    private fun dispatchClaudeCodeMessage(
        userInput: String,
        trace: String?,
        errs: String?,
        isPlanOnly: Boolean,
        selectedSpecificPromptName: String? = null
    ) {
        val cs = service.claudeCodeService
        var session = chatTreeService.getActiveSession()
        val globalContextFiles = chatTreeService.getGlobalContextFiles()

        // Capture history BEFORE mutating session.
        val history = session.messages.map { it.toChatMessageDTO() }

        val specificPromptContent = service.specificPromptService.resolvePromptContent(selectedSpecificPromptName)

        val fullMsg = buildString {
            append(userInput)
            if (!trace.isNullOrBlank()) append("\n[trace: ${trace.lines().size} lines]")
            if (!errs.isNullOrBlank()) append("\n[attached ide errors]")
            if (isPlanOnly) append("\n[plan-only]")
        }
        session = chatTreeService.addMessage(session.id, MessageRole.USER, fullMsg)
        callbacks.addUserMessageBubble(userInput)

        callbacks.setInputEnabled(false)
        callbacks.setStatus("Claude Code: sending...")

        val capturedSession = session
        runClaudeCodeBg("Claude Code: sending...", capturedSession) {
            cs.handleUserInput(
                sessionId = capturedSession.id,
                userInput = userInput,
                history = history,
                attachedContext = trace,
                planOnly = isPlanOnly,
                ideErrors = errs,
                globalContextFiles = globalContextFiles,
                specificPromptContent = specificPromptContent
            )
        }
    }
}
