package com.maxvibes.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.maxvibes.application.port.input.ContextAwareRequest
import com.maxvibes.application.port.input.ContextAwareResult
import com.maxvibes.application.port.output.ChatMessageDTO
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
        reasoning: String? = null
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
 * Handles message sending (API, Clipboard, CheapAPI) and response processing.
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
                            task = task, history = history, dryRun = isDryRun,
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
        updatedSession = chatTreeService.addMessage(updatedSession.id, MessageRole.ASSISTANT, mainText)
        callbacks.updateTokenDisplay()

        val tokenInfo = buildTokenInfo(
            result.planningInputTokens, result.planningOutputTokens,
            result.chatInputTokens, result.chatOutputTokens
        )
        callbacks.addAssistantMessageBubble(
            callbacks.formatMarkdown(mainText),
            tokenInfo,
            result.modifications,
            emptyList(),
            null
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
                if (!assistantText.isNullOrBlank()) {
                    updatedSession = chatTreeService.addMessage(updatedSession.id, MessageRole.ASSISTANT, assistantText)
                }
                callbacks.updateTokenDisplay()

                if (!assistantText.isNullOrBlank()) {
                    val tokenSummaryParts = mutableListOf<String>()
                    if (result.estimatedInputTokens > 0) tokenSummaryParts += "~${fmt(result.estimatedInputTokens)} tokens"
                    if (result.freshFileNames.isNotEmpty()) tokenSummaryParts += "${result.freshFileNames.size} files"
                    tokenSummaryParts += result.phase.name.lowercase()
                    val tokenInfo = tokenSummaryParts.joinToString("  \u00B7  ")

                    callbacks.addAssistantMessageBubble(
                        callbacks.formatMarkdown(assistantText),
                        tokenInfo,
                        emptyList(),
                        result.freshFileNames,
                        result.llmReasoning
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
                updatedSession = chatTreeService.addMessage(updatedSession.id, MessageRole.ASSISTANT, text)

                val tokenInfo = if (result.outputTokens > 0) "\u2193${fmt(result.outputTokens)}" else null

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
                        callbacks.formatMarkdown(text),
                        tokenInfo,
                        result.modifications,
                        emptyList(),
                        result.llmReasoning
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
                        callbacks.formatMarkdown(text),
                        tokenInfo,
                        result.modifications,
                        emptyList(),
                        result.llmReasoning
                    )
                    result.commitMessage?.let { msg ->
                        callbacks.setCommitMessage(msg)
                        callbacks.appendToChat("\uD83D\uDCAC Commit message set in IDE")
                    }

                    callbacks.setInputEnabled(true)
                    callbacks.updateModeIndicator()
                    val hint = if (service.clipboardService.hasActiveSession()) " \u2022 Session active" else ""
                    callbacks.setStatus((if (result.success) "Ready" else "Errors") + hint)
                    callbacks.updateBreadcrumb()
                }
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
                            task = task, history = history, dryRun = isDryRun,
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

                            is Result.Failure -> {
                                callbacks.onError("Failed to fetch IDE errors: ${result.error}")
                            }
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
        // deleteSession() internally sets the next active session (parent / sibling / first remaining)
        val next = chatTreeService.getActiveSession()
        callbacks.onSessionChanged(next)
    }
    fun renameSession(sessionId: String, newTitle: String) {
        val updated = chatTreeService.renameSession(sessionId, newTitle)
        if (updated != null) {
            callbacks.onSessionRenamed(updated)
        }
    }
    fun branchSession(parentSessionId: String, title: String) {
        val newSession = chatTreeService.createBranch(parentSessionId, title)
        if (newSession != null) {
            callbacks.onSessionChanged(newSession)
        }
    }
    fun loadSession(sessionId: String) {
        chatTreeService.setActiveSession(sessionId)
        val session = chatTreeService.getSessionById(sessionId)
        if (session != null) {
            callbacks.onSessionChanged(session)
        }
    }

    /**
     * Dispatches a user message to the appropriate mode handler.
     *
     * @param userInput raw text from the input field
     * @param isPlanOnly whether plan-only mode is active
     * @param isDryRun whether dry-run mode is active (API/CheapAPI only)
     * @param mode current interaction mode
     * @param addHistory when true, previously gathered file paths are included in the
     *   Clipboard request so a fresh LLM chat can re-request context it needs
     */
    fun sendMessage(
        userInput: String,
        isPlanOnly: Boolean,
        isDryRun: Boolean,
        mode: InteractionMode,
        addHistory: Boolean = false
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
                "addHistory" to addHistory
            )
        )
        when (mode) {
            InteractionMode.API -> dispatchApiMessage(userInput, trace, errs, isPlanOnly, isDryRun)
            InteractionMode.CLIPBOARD -> dispatchClipboardMessage(userInput, trace, errs, isPlanOnly, addHistory)
            InteractionMode.CHEAP_API -> dispatchCheapApiMessage(userInput, trace, errs, isPlanOnly, isDryRun)
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

    /**
     * Routes a Clipboard-mode message to the correct service call based on session state.
     *
     * - **Waiting for paste**: delegates to [ClipboardInteractionService.handlePastedResponse];
     *   [addHistory] is ignored (session is already active).
     * - **Active session**: calls [ClipboardInteractionService.continueDialog] with [addHistory].
     * - **New session**: calls [ClipboardInteractionService.startTask] with [addHistory].
     *
     * @param addHistory when true, previously gathered file paths are forwarded to the service
     *   so they appear in the `previouslyGatheredFiles` field of the Clipboard JSON
     */
    private fun dispatchClipboardMessage(
        userInput: String,
        trace: String?,
        errs: String?,
        isPlanOnly: Boolean,
        addHistory: Boolean = false
    ) {
        val cs = service.clipboardService
        var session = chatTreeService.getActiveSession()
        val globalContextFiles = chatTreeService.getGlobalContextFiles()
        when {
            cs.isWaitingForResponse() -> {
                // addHistory is irrelevant here — we are pasting a response, not starting a request.
                callbacks.appendIconToLastBubble("📥")
                callbacks.setInputEnabled(false)
                callbacks.setStatus("Processing...")
                runClipboardBg("Processing response...", session) { cs.handlePastedResponse(userInput) }
            }

            cs.hasActiveSession() -> {
                callbacks.addUserMessageBubble(userInput)
                val fullMsg = buildString {
                    append(userInput)
                    if (!trace.isNullOrBlank()) append("\n[trace: ${trace.lines().size} lines]")
                    if (!errs.isNullOrBlank()) append("\n[attached ide errors]")
                    if (isPlanOnly) append("\n[plan-only]")
                }
                session = chatTreeService.addMessage(session.id, MessageRole.USER, fullMsg)
                callbacks.setInputEnabled(false)
                callbacks.setStatus("Continuing...")
                runClipboardBg("Continuing...", session) {
                    cs.continueDialog(
                        userInput,
                        trace,
                        isPlanOnly,
                        errs,
                        globalContextFiles,
                        addHistory = addHistory
                    )
                }
            }

            else -> {
                callbacks.addUserMessageBubble(userInput)
                val fullMsg = buildString {
                    append(userInput)
                    if (!trace.isNullOrBlank()) append("\n[trace: ${trace.lines().size} lines]")
                    if (!errs.isNullOrBlank()) append("\n[attached ide errors]")
                    if (isPlanOnly) append("\n[plan-only]")
                }
                val dtos = session.messages.map { it.toChatMessageDTO() }
                session = chatTreeService.addMessage(session.id, MessageRole.USER, fullMsg)
                callbacks.setInputEnabled(false)
                callbacks.setStatus("Generating JSON...")
                runClipboardBg("Generating request...", session) {
                    cs.startTask(
                        userInput,
                        dtos,
                        trace,
                        isPlanOnly,
                        errs,
                        globalContextFiles,
                        addHistory = addHistory
                    )
                }
            }
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
}
