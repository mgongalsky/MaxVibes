package com.maxvibes.plugin.ui

import com.maxvibes.adapter.llm.dto.toChatMessageDTO
import com.maxvibes.application.port.input.ContextAwareRequest
import com.maxvibes.application.port.input.ContextAwareResult
import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.service.ChatTreeService
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.domain.model.modification.AppliedModInfo
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.domain.model.modification.toCategory
import com.maxvibes.plugin.service.MaxVibesLogger

/**
 * Dispatches user messages in API and Cheap-API modes and renders the results.
 *
 * Extracted from [ChatMessageController]. The dispatcher is synchronous and UI-thread-agnostic:
 * request execution (background task + EDT hop back into [handleResult]) is injected as
 * [executeAsync], so unit tests can record requests without touching ProgressManager.
 *
 * Auto-retry: when a response contains [ModificationResult.Failure] entries, the dispatcher
 * automatically sends one follow-up request with error details (max [MAX_AUTO_RETRIES] per
 * user message). Retries and command-result continuations always use the regular use case,
 * never the cheap one — same as before the extraction.
 */
class ApiDispatcher(
    private val chatTreeService: ChatTreeService,
    private val callbacks: MessageFlowView,
    private val ensureCheapService: () -> Unit,
    private val presentCommands: (commands: List<CommandRequest>, sessionId: String, mode: InteractionMode) -> Unit,
    private val executeAsync: (progressTitle: String, session: ChatSession, isPlanOnly: Boolean, useCheap: Boolean, request: ContextAwareRequest) -> Unit
) {

    private data class ApiRequestContext(
        val isDryRun: Boolean,
        val isPlanOnly: Boolean,
        val globalContextFiles: List<String>,
        val ideErrors: String?
    )

    private var lastApiContext: ApiRequestContext? = null
    private var autoRetryCount = 0

    companion object {
        private const val MAX_AUTO_RETRIES = 1
    }

    fun dispatchMessage(msg: String, trace: String?, errs: String?, isPlanOnly: Boolean, isDryRun: Boolean) {
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
        send(
            fullTask, session, history, isDryRun, isPlanOnly,
            chatTreeService.getGlobalContextFiles(), errs,
            useCheap = false, progressTitle = "Processing"
        )
    }

    fun dispatchCheapMessage(
        msg: String,
        trace: String?,
        errs: String?,
        isPlanOnly: Boolean,
        isDryRun: Boolean
    ) {
        var session = chatTreeService.getActiveSession()
        val fullTask = TaskContextFormatter.build(msg, trace, errs)
        val history = session.messages.map { it.toChatMessageDTO() }
        session = chatTreeService.addMessage(session.id, MessageRole.USER, fullTask)
        callbacks.addUserMessageBubble(msg)
        callbacks.setInputEnabled(false)
        callbacks.setStatus("Thinking (cheap)...")
        ensureCheapService()
        send(
            fullTask,
            session,
            history,
            isDryRun,
            isPlanOnly,
            chatTreeService.getGlobalContextFiles(),
            errs,
            useCheap = true,
            progressTitle = "Processing (budget)"
        )
    }

    /** Continues the dialog after a resolved command batch (API / Cheap-API modes). */
    fun submitCommandResults(session: ChatSession, formatted: String) {
        val task = "=== COMMAND RESULTS ===\n$formatted\n\nReact to these results and continue the task."
        val history = session.messages.map { it.toChatMessageDTO() }
        val updated = chatTreeService.addMessage(session.id, MessageRole.USER, task)
        execute(
            task = task, session = updated, history = history,
            isDryRun = false, isPlanOnly = false,
            globalContextFiles = lastApiContext?.globalContextFiles
                ?: chatTreeService.getGlobalContextFiles(),
            ideErrors = null,
            progressTitle = "Processing command results",
            useCheap = false
        )
    }

    private fun send(
        task: String,
        session: ChatSession,
        history: List<ChatMessageDTO>,
        isDryRun: Boolean,
        isPlanOnly: Boolean,
        globalContextFiles: List<String>,
        ideErrors: String?,
        useCheap: Boolean,
        progressTitle: String
    ) {
        lastApiContext = ApiRequestContext(isDryRun, isPlanOnly, globalContextFiles, ideErrors)
        autoRetryCount = 0
        execute(task, session, history, isDryRun, isPlanOnly, globalContextFiles, ideErrors, progressTitle, useCheap)
    }

    private fun execute(
        task: String,
        session: ChatSession,
        history: List<ChatMessageDTO>,
        isDryRun: Boolean,
        isPlanOnly: Boolean,
        globalContextFiles: List<String>,
        ideErrors: String?,
        progressTitle: String,
        useCheap: Boolean
    ) {
        val request = ContextAwareRequest(
            currentMessage = task, history = history, dryRun = isDryRun,
            planOnly = isPlanOnly, globalContextFiles = globalContextFiles,
            ideErrors = ideErrors
        )
        executeAsync(progressTitle, session, isPlanOnly, useCheap, request)
    }

    private fun triggerAutoRetry(
        failures: List<ModificationResult.Failure>,
        session: ChatSession,
        ctx: ApiRequestContext
    ) {
        autoRetryCount++
        val errorSummary = buildErrorSummary(failures)
        val retryTask = buildApiRetryTask(failures)

        MaxVibesLogger.warn(
            "ApiDispatcher", "autoRetry",
            data = mapOf("attempt" to autoRetryCount, "failures" to failures.size)
        )

        val feedbackMsg =
            "\uD83D\uDD04 Auto-fix: ${failures.size} modification(s) failed \u2014 asking LLM to correct:\n$errorSummary"
        callbacks.appendToChat(feedbackMsg)
        callbacks.setStatus("Auto-fixing...")

        val history = session.messages.map { it.toChatMessageDTO() }
        val retrySession = chatTreeService.addMessage(session.id, MessageRole.USER, retryTask)

        execute(
            task = retryTask,
            session = retrySession,
            history = history,
            isDryRun = ctx.isDryRun,
            isPlanOnly = false,
            globalContextFiles = ctx.globalContextFiles,
            ideErrors = ctx.ideErrors,
            progressTitle = "Auto-fixing",
            useCheap = false
        )
    }

    fun handleResult(result: ContextAwareResult, session: ChatSession, wasPlanOnly: Boolean = false) {
        callbacks.registerElementPaths(result.modifications)

        var updatedSession =
            chatTreeService.addPlanningTokens(session.id, result.planningInputTokens, result.planningOutputTokens)
        updatedSession =
            chatTreeService.addChatTokens(updatedSession.id, result.chatInputTokens, result.chatOutputTokens)

        val failures = result.modifications.filterIsInstance<ModificationResult.Failure>()

        MaxVibesLogger.info(
            "ApiDispatcher", "apiResult", mapOf(
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

        if (result.commands.isNotEmpty()) {
            when {
                wasPlanOnly ->
                    callbacks.appendToChat("\u26A0\uFE0F ${result.commands.size} command(s) skipped \u2014 plan-only mode")

                failures.isNotEmpty() ->
                    callbacks.appendToChat("\u26A0\uFE0F ${result.commands.size} command(s) skipped \u2014 fix failed modifications first")

                else -> {
                    presentCommands(result.commands, updatedSession.id, InteractionMode.API)
                    callbacks.updateBreadcrumb()
                    return
                }
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
}
