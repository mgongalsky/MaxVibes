package com.maxvibes.plugin.ui

import com.maxvibes.adapter.llm.dto.toChatMessageDTO
import com.maxvibes.application.service.ChatTreeService
import com.maxvibes.application.service.ClipboardInteractionService
import com.maxvibes.application.service.ClipboardStepResult
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.domain.model.modification.AppliedModInfo
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.domain.model.modification.toCategory
import com.maxvibes.plugin.service.MaxVibesLogger

/**
 * Dispatches user messages in Clipboard mode and renders the step results.
 *
 * Extracted from [ChatMessageController]. The dispatcher is synchronous and UI-thread-agnostic:
 * background execution (progress task + EDT hop back into [handleResult]) is injected as
 * [executeAsync]. [clipboardService] is a provider deliberately dereferenced only inside
 * dispatch paths, so unit tests never have to construct the real service.
 */
class ClipboardDispatcher(
    private val clipboardService: () -> ClipboardInteractionService,
    private val resolveSpecificPrompt: (name: String?) -> String?,
    private val chatTreeService: ChatTreeService,
    private val callbacks: ChatPanelCallbacks,
    private val presentCommands: (commands: List<CommandRequest>, sessionId: String, mode: InteractionMode) -> Unit,
    private val executeAsync: (title: String, session: ChatSession, action: suspend () -> ClipboardStepResult) -> Unit
) {

    fun dispatchMessage(
        userInput: String,
        trace: String?,
        errs: String?,
        isPlanOnly: Boolean,
        addHistory: Boolean = false,
        selectedSpecificPromptName: String? = null
    ) {
        val cs = clipboardService()
        var session = chatTreeService.getActiveSession()
        val globalContextFiles = chatTreeService.getGlobalContextFiles()
        val currentStatus = session.clipboardStatus

        // Capture history BEFORE mutating session.
        val history = session.messages.map { it.toChatMessageDTO() }

        // Resolve prompt content from the name already captured in the UI state snapshot —
        // avoids a second repository read that could race with a just-saved selectSpecificPrompt.
        val specificPromptContent = resolveSpecificPrompt(selectedSpecificPromptName)

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
        executeAsync(statusText, capturedSession) {
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

    /**
     * Re-generates and copies the clipboard JSON for the current active session.
     * Does NOT add a new user message to history.
     */
    fun redoLastRequest() {
        val session = chatTreeService.getActiveSession()
        val globalContextFiles = chatTreeService.getGlobalContextFiles()
        callbacks.setInputEnabled(false)
        executeAsync("Re-generating JSON...", session) {
            clipboardService().redoLastRequest(session.id, globalContextFiles)
        }
    }

    fun handleResult(result: ClipboardStepResult, session: ChatSession) {
        when (result) {
            is ClipboardStepResult.WaitingForResponse -> {
                MaxVibesLogger.info(
                    "ClipboardDispatcher", "clipboard waiting", mapOf(
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
                    "ClipboardDispatcher", "clipboard completed", mapOf(
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
                    if (result.commands.isNotEmpty()) callbacks.appendToChat("\u26A0\uFE0F ${result.commands.size} command(s) skipped \u2014 fix failed modifications first")

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

                    if (result.commands.isNotEmpty()) {
                        presentCommands(result.commands, session.id, InteractionMode.CLIPBOARD)
                        callbacks.updateModeIndicator()
                        return
                    }

                    callbacks.setInputEnabled(true)
                    callbacks.updateModeIndicator()
                    val isSessionActive = clipboardService().status(session.id) != ClipboardSessionStatus.IDLE
                    val hint = if (isSessionActive) " \u2022 Session active" else ""
                    callbacks.setStatus((if (result.success) "Ready" else "Errors") + hint)
                    callbacks.updateBreadcrumb()
                }
            }

            is ClipboardStepResult.ParseError -> {
                MaxVibesLogger.warn(
                    "ClipboardDispatcher", "clipboard parse error", data = mapOf(
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
                MaxVibesLogger.warn("ClipboardDispatcher", "clipboard error", data = mapOf("msg" to result.message))
                chatTreeService.addMessage(session.id, MessageRole.SYSTEM, "Error: ${result.message}")
                callbacks.appendToChat("\u274C ${result.message}")
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("Error")
            }
        }
    }

    private fun buildErrorSummary(failures: List<ModificationResult.Failure>): String =
        failures.joinToString("\n") { f -> "  \u2022 ${f.error.message}" }

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
            MaxVibesLogger.error("ClipboardDispatcher", "copyToClipboard failed", e)
        }
    }

    private fun fmt(n: Int) = if (n >= 1000) "${n / 1000}k" else n.toString()
}
