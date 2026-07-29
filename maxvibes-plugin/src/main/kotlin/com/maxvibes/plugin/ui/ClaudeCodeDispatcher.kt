package com.maxvibes.plugin.ui

import com.maxvibes.adapter.llm.dto.toChatMessageDTO
import com.maxvibes.application.service.ChatTreeService
import com.maxvibes.application.service.ClaudeCodeInteractionService
import com.maxvibes.application.service.ClaudeCodeStepResult
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.domain.model.interaction.InteractionQuestion
import com.maxvibes.domain.model.modification.AppliedModInfo
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.domain.model.modification.toCategory
import com.maxvibes.plugin.service.MaxVibesLogger

/**
 * Claude Code mode dispatcher extracted from [ChatMessageController].
 *
 * Owns the send/approve/result flow of the Claude Code dialog: builds the user
 * turn, hands the suspend call to the injected [executeAsync] (expected to run it
 * off the EDT and invoke [handleResult] back on the EDT), and renders every
 * [ClaudeCodeStepResult] branch. Question and command turns are forwarded to the
 * coordinators via [presentQuestions] / [presentCommands].
 *
 * Threading is intentionally externalized, so this class stays synchronous and
 * unit-testable. [claudeCodeService] is a provider on purpose: it is only
 * dereferenced inside [executeAsync] actions, so tests that record actions
 * without running them never need the real service.
 */
class ClaudeCodeDispatcher(
    private val claudeCodeService: () -> ClaudeCodeInteractionService,
    private val resolveSpecificPrompt: (name: String?) -> String?,
    private val chatTreeService: ChatTreeService,
    private val callbacks: MessageFlowView,
    private val presentQuestions: (questions: List<InteractionQuestion>) -> Unit,
    private val presentCommands: (commands: List<CommandRequest>, sessionId: String, mode: InteractionMode) -> Unit,
    private val executeAsync: (title: String, session: ChatSession, action: suspend () -> ClaudeCodeStepResult) -> Unit
) {

    fun dispatchMessage(
        userInput: String,
        trace: String?,
        errs: String?,
        isPlanOnly: Boolean,
        selectedSpecificPromptName: String? = null,
        images: List<AttachedImage> = emptyList()
    ) {
        var session = chatTreeService.getActiveSession()
        val globalContextFiles = chatTreeService.getGlobalContextFiles()

        // Capture history BEFORE mutating session.
        val history = session.messages.map { it.toChatMessageDTO() }

        val specificPromptContent = resolveSpecificPrompt(selectedSpecificPromptName)

        val fullMsg = buildString {
            append(userInput)
            if (!trace.isNullOrBlank()) append("\n[trace: ${trace.lines().size} lines]")
            if (!errs.isNullOrBlank()) append("\n[attached ide errors]")
            if (isPlanOnly) append("\n[plan-only]")
            if (images.isNotEmpty()) append("\n[🖼 ${images.size} image(s)]")
        }
        session = chatTreeService.addMessage(session.id, MessageRole.USER, fullMsg)
        callbacks.addUserMessageBubble(userInput, images)

        callbacks.setInputEnabled(false)
        callbacks.setStatus("Claude Code: sending...")

        val capturedSession = session
        executeAsync("Claude Code: sending...", capturedSession) {
            claudeCodeService().handleUserInput(
                sessionId = capturedSession.id,
                userInput = userInput,
                history = history,
                attachedContext = trace,
                planOnly = isPlanOnly,
                ideErrors = errs,
                globalContextFiles = globalContextFiles,
                specificPromptContent = specificPromptContent,
                attachedImages = images
            )
        }
    }

    /** Approves the current Claude Code turn with pre-collected text attachments. */
    fun approve(trace: String?, errs: String?) {
        val session = chatTreeService.getActiveSession()
        MaxVibesLogger.info(
            "ClaudeCodeDispatcher",
            "approve",
            mapOf(
                "sessionId" to session.id,
                "hasTrace" to (trace != null),
                "hasErrors" to (errs != null)
            )
        )
        callbacks.setInputEnabled(false)
        callbacks.setStatus("Claude Code: approving...")
        executeAsync("Claude Code: approving...", session) {
            claudeCodeService().approve(
                sessionId = session.id,
                attachedContext = trace,
                ideErrors = errs
            )
        }
    }

    fun handleResult(result: ClaudeCodeStepResult, session: ChatSession) {
        when (result) {
            is ClaudeCodeStepResult.WaitingForApprove -> {
                MaxVibesLogger.info(
                    "ClaudeCodeDispatcher", "claudeCode awaiting approve", mapOf(
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
                    durationMs = result.durationMs,
                    costUsd = result.costUsd,
                    numTurns = result.numTurns
                )
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
                result.diagram?.let { callbacks.showDiagramButton(it) }
                if (result.skippedCommands > 0) {
                    callbacks.appendToChat(
                        "⚠️ ${result.skippedCommands} command(s) skipped — response mixed them with requestedViews"
                    )
                }
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("🤖 Awaiting approval — click Approve to gather files and continue")
            }

            is ClaudeCodeStepResult.AwaitingModApprove -> {
                MaxVibesLogger.info(
                    "ClaudeCodeDispatcher", "claudeCode awaiting mod approve", mapOf(
                        "mods" to result.proposedModifications.size,
                        "heldCommands" to result.heldCommands,
                        "skippedViews" to result.skippedViews,
                        "in" to result.inputTokens,
                        "out" to result.outputTokens
                    )
                )
                chatTreeService.addChatTokens(session.id, result.inputTokens, result.outputTokens)
                val tokenInfo = buildTokenInfoForClaudeCode(
                    result.inputTokens,
                    result.outputTokens,
                    result.durationMs,
                    result.costUsd,
                    result.numTurns
                )
                chatTreeService.addMessage(
                    session.id, MessageRole.ASSISTANT, result.assistantMessage,
                    tokenInfo = tokenInfo,
                    reasoning = result.llmReasoning
                )
                callbacks.updateTokenDisplay()
                callbacks.addAssistantMessageBubble(
                    text = callbacks.formatMarkdown(result.assistantMessage),
                    tokenInfo = tokenInfo,
                    modifications = emptyList(),
                    metaFiles = emptyList(),
                    reasoning = result.llmReasoning,
                    requestedViews = emptyList(),
                    appliedModifications = emptyList()
                )
                result.diagram?.let { callbacks.showDiagramButton(it) }
                val proposal = result.proposedModifications.joinToString("\n") {
                    "  • ${it.type}  ${it.path}"
                }
                callbacks.appendToChat(
                    "📝 Proposed ${result.proposedModifications.size} modification(s):\n$proposal"
                )
                if (result.heldCommands > 0) {
                    callbacks.appendToChat(
                        "⚡ ${result.heldCommands} command(s) held — they run after the modifications are applied"
                    )
                }
                if (result.skippedViews > 0) {
                    callbacks.appendToChat(
                        "⚠️ ${result.skippedViews} file request(s) skipped — response mixed them with modifications"
                    )
                }
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus(
                    "🤖 ${result.proposedModifications.size} modification(s) awaiting approval — Approve to apply, or type to reject"
                )
            }

            is ClaudeCodeStepResult.AwaitingQuestions -> {
                MaxVibesLogger.info(
                    "ClaudeCodeDispatcher", "claudeCode awaiting answers", mapOf(
                        "questions" to result.questions.size,
                        "in" to result.inputTokens,
                        "out" to result.outputTokens,
                        "durationMs" to result.durationMs
                    )
                )
                chatTreeService.addChatTokens(session.id, result.inputTokens, result.outputTokens)
                val tokenInfo = buildTokenInfoForClaudeCode(
                    result.inputTokens,
                    result.outputTokens,
                    result.durationMs,
                    result.costUsd,
                    result.numTurns
                )

                val questionsBlock = result.questions.joinToString("\n\n") { question ->
                    buildString {
                        append("\u2753 ").append(question.question)
                        question.options.forEachIndexed { index, option ->
                            append("\n")
                                .append(index + 1)
                                .append(". ")
                                .append(option)
                        }
                    }
                }
                val combined = buildString {
                    val message = result.assistantMessage.trim()
                    if (message.isNotBlank()) {
                        append(message)
                        append("\n\n")
                    }
                    append(questionsBlock)
                }

                chatTreeService.addMessage(
                    session.id, MessageRole.ASSISTANT, combined,
                    tokenInfo = tokenInfo,
                    reasoning = result.llmReasoning
                )
                callbacks.updateTokenDisplay()

                if (result.assistantMessage.isNotBlank()) {
                    callbacks.addAssistantMessageBubble(
                        text = callbacks.formatMarkdown(result.assistantMessage),
                        tokenInfo = tokenInfo,
                        modifications = emptyList(),
                        metaFiles = emptyList(),
                        reasoning = result.llmReasoning,
                        requestedViews = emptyList(),
                        appliedModifications = emptyList()
                    )
                }
                result.diagram?.let { callbacks.showDiagramButton(it) }
                presentQuestions(result.questions)

                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus(
                    "\u2753 ${result.questions.size} question(s) — pick an option or type your answer"
                )
            }

            is ClaudeCodeStepResult.Completed -> {
                val failures = result.modifications.filterIsInstance<ModificationResult.Failure>()
                MaxVibesLogger.info(
                    "ClaudeCodeDispatcher", "claudeCode completed", mapOf(
                        "success" to result.success,
                        "mods" to result.modifications.size,
                        "failures" to failures.size,
                        "in" to result.inputTokens,
                        "out" to result.outputTokens,
                        "durationMs" to result.durationMs,
                        "hasCommitMsg" to (result.commitMessage != null),
                        "commands" to result.commands.size
                    )
                )
                callbacks.registerElementPaths(result.modifications)
                chatTreeService.addChatTokens(session.id, result.inputTokens, result.outputTokens)
                val text = result.message.trim().ifBlank { "Done." }
                val tokenInfo = buildTokenInfoForClaudeCode(
                    inTok = result.inputTokens,
                    outTok = result.outputTokens,
                    durationMs = result.durationMs,
                    costUsd = result.costUsd,
                    numTurns = result.numTurns
                )
                val appliedPaths = result.modifications
                    .filterIsInstance<ModificationResult.Success>()
                    .map { it.affectedPath.toString() }
                val appliedMods = result.modifications
                    .filterIsInstance<ModificationResult.Success>()
                    .map {
                        AppliedModInfo(
                            path = it.affectedPath.toString(),
                            category = it.modification.toCategory()
                        )
                    }
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
                result.diagram?.let { callbacks.showDiagramButton(it) }
                result.commitMessage?.let { message ->
                    callbacks.setCommitMessage(message)
                    callbacks.appendToChat("💬 Commit message set in IDE")
                }
                if (result.commands.isNotEmpty()) {
                    if (failures.isEmpty()) {
                        presentCommands(result.commands, session.id, InteractionMode.CLAUDE_CODE)
                        callbacks.updateModeIndicator()
                        callbacks.updateBreadcrumb()
                        return
                    }
                    callbacks.appendToChat(
                        "⚠️ ${result.commands.size} command(s) skipped — fix failed modifications first"
                    )
                }
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                if (failures.isNotEmpty()) {
                    callbacks.setStatus("⚠️ ${failures.size} modification(s) failed")
                } else {
                    callbacks.setStatus(if (result.success) "Ready" else "Errors")
                }
                callbacks.updateBreadcrumb()
            }

            is ClaudeCodeStepResult.Error -> {
                MaxVibesLogger.warn(
                    "ClaudeCodeDispatcher",
                    "claudeCode error",
                    data = mapOf("msg" to result.message)
                )
                chatTreeService.addMessage(
                    session.id,
                    MessageRole.SYSTEM,
                    "Claude Code error: ${result.message}"
                )
                callbacks.appendToChat("❌ ${result.message}")
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("Claude Code error")
            }

            is ClaudeCodeStepResult.TransportError -> {
                MaxVibesLogger.warn(
                    "ClaudeCodeDispatcher",
                    "claudeCode transport error",
                    data = mapOf("detail" to result.detail)
                )
                chatTreeService.addMessage(
                    session.id,
                    MessageRole.SYSTEM,
                    "Claude Code transport error: ${result.detail}"
                )
                callbacks.appendToChat("❌ Transport: ${result.detail}")
                callbacks.appendToChat("Check Claude Code settings (binary path, args) and retry.")
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("⚠️ Claude Code transport error")
            }
        }
    }

    /**
     * Formats the bubble-footer info line for Claude Code turns.
     * Layout: `\u21911234 \u00B7 \u2193567 \u00B7 42s` — components are omitted when their value is zero.
     * Returns null when nothing meaningful is available so the bubble suppresses the footer.
     */
    private fun buildTokenInfoForClaudeCode(
        inTok: Int,
        outTok: Int,
        durationMs: Long,
        costUsd: Double? = null,
        numTurns: Int? = null
    ): String? {
        val parts = mutableListOf<String>()
        if (inTok > 0) parts += "\u2191${fmt(inTok)}"
        if (outTok > 0) parts += "\u2193${fmt(outTok)}"
        if (durationMs >= 1000) parts += "${durationMs / 1000}s"
        numTurns?.takeIf { it > 1 }?.let { parts += "$it turns" }
        costUsd?.takeIf { it > 0.0 }?.let { parts += "\$${String.format("%.4f", it)}" }
        return if (parts.isEmpty()) null else parts.joinToString("  \u00B7  ")
    }

    private fun fmt(n: Int) = if (n >= 1000) "${n / 1000}k" else n.toString()
}
