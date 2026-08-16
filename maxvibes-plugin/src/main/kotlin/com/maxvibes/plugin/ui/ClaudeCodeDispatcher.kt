package com.maxvibes.plugin.ui

import com.maxvibes.adapter.llm.dto.toChatMessageDTO
import com.maxvibes.application.service.ChatTreeService
import com.maxvibes.application.service.CodingAgentInteractionService
import com.maxvibes.application.service.ClaudeCodeStepResult
import com.maxvibes.application.service.turn.TurnAutopilot
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.CodingAgentProvider
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.domain.model.interaction.InteractionQuestion
import com.maxvibes.domain.model.modification.AppliedModInfo
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.domain.model.modification.toCategory
import com.maxvibes.plugin.service.MaxVibesLogger
import com.maxvibes.application.service.turn.TurnSignalMapper

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
 *
 * [turnAutopilot] is a provider for the same reason plus one more: the autopilot
 * continues the turn through this dispatcher, so the two reference each other and
 * the provider breaks the construction cycle. When it yields null the dispatcher
 * behaves exactly as it did before autonomy existed.
 *
 * [maxFormatRetries] limits how many times in a row the agent may be asked to redo
 * a step whose modifications never reached the code — whether they could not be
 * parsed or failed to apply.
 *
 * [agentName] is the display name of the CLI actually behind this dialog: the same
 * dispatcher serves Codex, so statuses and progress titles must not claim it is
 * Claude Code. Deliberately a separate lambda instead of asking [claudeCodeService]
 * — that one must stay untouched outside [executeAsync].
 */
class ClaudeCodeDispatcher(
    private val claudeCodeService: () -> CodingAgentInteractionService,
    private val resolveSpecificPrompt: (name: String?) -> String?,
    private val chatTreeService: ChatTreeService,
    private val callbacks: MessageFlowView,
    private val presentQuestions: (questions: List<InteractionQuestion>) -> Unit,
    private val presentCommands: (commands: List<CommandRequest>, sessionId: String, mode: InteractionMode) -> Unit,
    private val executeAsync: (title: String, session: ChatSession, action: suspend () -> ClaudeCodeStepResult) -> Unit,
    private val turnAutopilot: () -> TurnAutopilot? = { null },
    private val maxFormatRetries: () -> Int = { 2 },
    private val agentName: () -> String = { CodingAgentProvider.CLAUDE_CODE.displayName }
) {
    private var modificationProposalView: ModificationProposalView? = null

    /**
     * Готовый текст следующего хода для шага, который сорвался: правки либо не
     * разобрались, либо не применились. Текст собирается сразу, потому что причина
     * видна только там, где есть весь результат шага.
     */
    private val pendingFix = mutableMapOf<String, String>()

    /**
     * Сколько раз ПОДРЯД шаг агента срывался. Счётчик общий для ошибок разбора и
     * ошибок применения: лимит из настроек — это бюджет на «агент не может выдать
     * рабочую правку», а не на каждый вид поломки отдельно.
     */
    private val fixRetries = mutableMapOf<String, Int>()

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
            if (!trace.isNullOrBlank()) append(10.toChar()).append("[trace: ${trace.lines().size} lines]")
            if (!errs.isNullOrBlank()) append(10.toChar()).append("[attached ide errors]")
            if (isPlanOnly) append(10.toChar()).append("[plan-only]")
            if (images.isNotEmpty()) append(10.toChar()).append("[\uD83D\uDDBC ${images.size} image(s)]")
        }
        session = chatTreeService.addMessage(session.id, MessageRole.USER, fullMsg)
        callbacks.addUserMessageBubble(userInput, images)

        turnAutopilot()?.startTurn(session.id)
        pendingFix.remove(session.id)
        fixRetries.remove(session.id)

        val sending = "${agentName()}: sending..."
        callbacks.setInputEnabled(false)
        callbacks.setStatus(sending)

        val capturedSession = session
        executeAsync(sending, capturedSession) {
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

    /** Approves the current coding-agent turn with pre-collected text attachments. */
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
        turnAutopilot()?.onHumanApproved(session.id)
        val approving = "${agentName()}: approving..."
        callbacks.setInputEnabled(false)
        callbacks.setStatus(approving)
        executeAsync(approving, session) {
            claudeCodeService().approve(
                sessionId = session.id,
                attachedContext = trace,
                ideErrors = errs
            )
        }
    }

    /**
     * Continues the turn without a human, on the autopilot's decision.
     *
     * Mirrors [approve] but never records a manual step. The session check is not
     * defensive noise: the user can switch chats while the agent is thinking, and
     * resuming someone else's turn in the visible chat would be wrong.
     */
    fun continueTurnAutomatically(sessionId: String) {
        val session = chatTreeService.getActiveSession()
        if (session.id != sessionId) return

        MaxVibesLogger.info(
            "ClaudeCodeDispatcher",
            "auto-continue",
            mapOf("sessionId" to sessionId)
        )
        modificationProposalView?.setApplying()
        callbacks.setInputEnabled(false)
        callbacks.setStatus("\uD83E\uDD16 Continuing automatically...")
        executeAsync("${agentName()}: continuing...", session) {
            claudeCodeService().approve(
                sessionId = session.id,
                attachedContext = null,
                ideErrors = null
            )
        }
    }

    /**
     * Sends the agent one more turn because it said it was not finished — or
     * because its last step never reached the code: the modifications were either
     * unparsable or failed to apply.
     *
     * Deliberately NOT routed through [dispatchMessage]: that one calls
     * [TurnAutopilot.startTurn], which restores the autonomy budget. A
     * self-continuing agent would then reset its own limit on every step and
     * never stop. No user bubble either — the user did not write this text.
     */
    fun continueWithoutHuman(sessionId: String) {
        val session = chatTreeService.getActiveSession()
        if (session.id != sessionId) return

        val correction = pendingFix.remove(sessionId)
        if (correction != null) {
            val limit = maxFormatRetries()
            val attempt = (fixRetries[sessionId] ?: 0) + 1
            if (attempt > limit) {
                fixRetries.remove(sessionId)
                turnAutopilot()?.forget(sessionId)
                MaxVibesLogger.info(
                    "ClaudeCodeDispatcher",
                    "fix-retry-exhausted",
                    mapOf("sessionId" to sessionId, "limit" to limit)
                )
                callbacks.appendToChat(
                    "\u26A0\uFE0F Агент $attempt раз(а) подряд не смог довести правки до кода — " +
                            "автоповторы остановлены (лимит $limit)"
                )
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("\u26A0\uFE0F Правки так и не дошли до кода")
                return
            }

            fixRetries[sessionId] = attempt
            MaxVibesLogger.info(
                "ClaudeCodeDispatcher",
                "fix-retry",
                mapOf("sessionId" to sessionId, "attempt" to attempt, "limit" to limit)
            )
            callbacks.setInputEnabled(false)
            callbacks.setStatus("\uD83E\uDD16 Просим агента переделать сорвавшиеся правки...")
            executeAsync("${agentName()}: fixing modifications...", session) {
                claudeCodeService().handleUserInput(
                    sessionId = session.id,
                    userInput = correction
                )
            }
            return
        }

        MaxVibesLogger.info(
            "ClaudeCodeDispatcher",
            "auto-next-turn",
            mapOf("sessionId" to sessionId)
        )
        callbacks.setInputEnabled(false)
        callbacks.setStatus("\uD83E\uDD16 Continuing on its own...")
        executeAsync("${agentName()}: continuing...", session) {
            claudeCodeService().handleUserInput(
                sessionId = session.id,
                userInput = "[AUTO-CONTINUE] No new instruction from the user. " +
                        "You reported that you were not finished, so take the next step now. " +
                        "Set turnIntent to DONE as soon as the task is complete."
            )
        }
    }

    fun handleResult(result: ClaudeCodeStepResult, session: ChatSession) {
        when (result) {
            is ClaudeCodeStepResult.Error -> chatTreeService.addMessage(
                session.id,
                MessageRole.SYSTEM,
                "${agentName()} error: ${result.message}"
            )

            is ClaudeCodeStepResult.TransportError -> chatTreeService.addMessage(
                session.id,
                MessageRole.SYSTEM,
                "${agentName()} transport error: ${result.detail}"
            )

            else -> Unit
        }

        when (result) {
            is ClaudeCodeStepResult.WaitingForApprove -> {
                chatTreeService.addChatTokens(session.id, result.inputTokens, result.outputTokens)
                val tokenInfo = buildTokenInfoForClaudeCode(
                    result.inputTokens,
                    result.outputTokens,
                    result.durationMs,
                    result.costUsd,
                    result.numTurns
                )
                chatTreeService.addMessage(
                    session.id,
                    MessageRole.ASSISTANT,
                    result.assistantMessage,
                    tokenInfo = tokenInfo,
                    reasoning = result.llmReasoning,
                    requestedViews = result.requestedViews
                )
                callbacks.updateTokenDisplay()
                callbacks.addAssistantMessageBubble(
                    callbacks.formatMarkdown(result.assistantMessage),
                    tokenInfo,
                    emptyList(),
                    emptyList(),
                    result.llmReasoning,
                    result.requestedViews,
                    emptyList()
                )
                result.diagram?.let { callbacks.showDiagramButton(it) }
                if (result.skippedCommands > 0) callbacks.appendToChat("\u26A0\uFE0F ${result.skippedCommands} command(s) skipped — response mixed them with requestedViews")
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("\uD83E\uDD16 Awaiting approval — click Approve to gather files and continue")
            }

            is ClaudeCodeStepResult.AwaitingModApprove -> {
                chatTreeService.addChatTokens(session.id, result.inputTokens, result.outputTokens)
                val tokenInfo = buildTokenInfoForClaudeCode(
                    result.inputTokens,
                    result.outputTokens,
                    result.durationMs,
                    result.costUsd,
                    result.numTurns
                )
                chatTreeService.addMessage(
                    session.id,
                    MessageRole.ASSISTANT,
                    result.assistantMessage,
                    tokenInfo = tokenInfo,
                    reasoning = result.llmReasoning
                )
                callbacks.updateTokenDisplay()
                callbacks.addAssistantMessageBubble(
                    callbacks.formatMarkdown(result.assistantMessage),
                    tokenInfo,
                    emptyList(),
                    emptyList(),
                    result.llmReasoning,
                    emptyList(),
                    emptyList()
                )
                result.diagram?.let { callbacks.showDiagramButton(it) }
                modificationProposalView = callbacks.addModificationProposalBubble(
                    modifications = result.proposedModifications,
                    heldCommands = result.heldCommands,
                    onApply = {
                        modificationProposalView?.setApplying()
                        approve(null, null)
                    },
                    onReject = { rejectPendingModifications(session) }
                )
                if (result.skippedViews > 0) callbacks.appendToChat("\u26A0\uFE0F ${result.skippedViews} file request(s) skipped — response mixed them with modifications")
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("Review proposed changes, then Apply or Reject")
            }

            is ClaudeCodeStepResult.AwaitingQuestions -> {
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
                            append('\n').append(index + 1).append(". ").append(option)
                        }
                    }
                }
                val combined = listOf(result.assistantMessage.trim(), questionsBlock)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                chatTreeService.addMessage(
                    session.id,
                    MessageRole.ASSISTANT,
                    combined,
                    tokenInfo = tokenInfo,
                    reasoning = result.llmReasoning
                )
                callbacks.updateTokenDisplay()
                if (result.assistantMessage.isNotBlank()) callbacks.addAssistantMessageBubble(
                    callbacks.formatMarkdown(
                        result.assistantMessage
                    ), tokenInfo, emptyList(), emptyList(), result.llmReasoning, emptyList(), emptyList()
                )
                result.diagram?.let { callbacks.showDiagramButton(it) }
                presentQuestions(result.questions)
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("\u2753 ${result.questions.size} question(s) — pick an option or type your answer")
            }

            is ClaudeCodeStepResult.Completed -> {
                if (result.modifications.isNotEmpty()) modificationProposalView?.setApplied()
                modificationProposalView = null
                val failures = result.modifications.filterIsInstance<ModificationResult.Failure>()
                val lostToFormat = result.malformedModifications.isNotEmpty()
                callbacks.registerElementPaths(result.modifications)
                chatTreeService.addChatTokens(session.id, result.inputTokens, result.outputTokens)
                val text = result.message.trim().ifBlank { "Done." }
                val tokenInfo = buildTokenInfoForClaudeCode(
                    result.inputTokens,
                    result.outputTokens,
                    result.durationMs,
                    result.costUsd,
                    result.numTurns
                )
                val appliedPaths = result.modifications.filterIsInstance<ModificationResult.Success>()
                    .map { it.affectedPath.toString() }
                val appliedMods = result.modifications.filterIsInstance<ModificationResult.Success>().map {
                    AppliedModInfo(it.affectedPath.toString(), it.modification.toCategory())
                }
                chatTreeService.addMessage(
                    session.id,
                    MessageRole.ASSISTANT,
                    text,
                    appliedModificationPaths = appliedPaths,
                    tokenInfo = tokenInfo,
                    reasoning = result.llmReasoning,
                    appliedModifications = appliedMods
                )
                callbacks.updateTokenDisplay()
                callbacks.addAssistantMessageBubble(
                    callbacks.formatMarkdown(text),
                    tokenInfo,
                    result.modifications,
                    emptyList(),
                    result.llmReasoning,
                    emptyList(),
                    appliedMods
                )
                result.diagram?.let { callbacks.showDiagramButton(it) }
                result.commitMessage?.let { callbacks.setCommitMessage(it) }
                if (result.commands.isNotEmpty() && failures.isEmpty() && !lostToFormat) {
                    presentCommands(result.commands, session.id, InteractionMode.CLAUDE_CODE)
                    callbacks.updateModeIndicator()
                    callbacks.updateBreadcrumb()
                    notifyTurn(result, session)
                    return
                }
                if (result.commands.isNotEmpty()) callbacks.appendToChat(
                    if (lostToFormat) {
                        "\u26A0\uFE0F ${result.commands.size} command(s) skipped — the modifications of this step were lost to a format error"
                    } else {
                        "\u26A0\uFE0F ${result.commands.size} command(s) skipped — fix failed modifications first"
                    }
                )
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus(if (failures.isNotEmpty()) "\u26A0\uFE0F ${failures.size} modification(s) failed" else if (result.success) "Ready" else "Errors")
                callbacks.updateBreadcrumb()
            }

            is ClaudeCodeStepResult.Error -> {
                callbacks.appendToChat("\u274C ${result.message}")
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("${agentName()} error")
            }

            is ClaudeCodeStepResult.TransportError -> {
                callbacks.appendToChat("\u274C Transport: ${result.detail}")
                callbacks.appendToChat("Check ${agentName()} settings (binary path, args) and retry.")
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
                callbacks.setStatus("\u26A0\uFE0F ${agentName()} transport error")
            }
        }

        notifyTurn(result, session)
    }

    private fun notifyTurn(result: ClaudeCodeStepResult, session: ChatSession) {
        // Незаконченный шаг — правки на подтверждении, запрос файлов, вопросы — ничего
        // не говорит о том, дошли ли правки до кода, поэтому серию срывов не трогает.
        // Иначе удержание правок обнуляло бы счётчик, и лимит повторов был бы
        // недостижим для ошибок применения: они всегда идут через подтверждение.
        val completed = result as? ClaudeCodeStepResult.Completed
        if (completed != null) {
            val malformed = completed.malformedModifications
            val failed = completed.modifications
                .filterIsInstance<ModificationResult.Failure>()
                .map { "${it.modification.targetPath.value}: ${it.error.message}" }
            if (malformed.isEmpty() && failed.isEmpty()) {
                pendingFix.remove(session.id)
                fixRetries.remove(session.id)
            } else {
                pendingFix[session.id] = fixPrompt(malformed, failed)
            }
        }
        val autopilot = turnAutopilot() ?: return
        autopilot.onStep(session.id, TurnSignalMapper.from(result))
    }

    /**
     * Сообщение агенту о сорвавшемся шаге. Уходит как реплика пользователя, а не
     * как запись в его собственной истории: только так это читается моделью как
     * требование исправиться, а не как её же старый текст.
     *
     * Из непонятой записи берётся только первая строка: дальше в ней лежит сырой
     * JSON записи для отчёта о сбое, и при отвергнутом CREATE_FILE это целый файл.
     */
    private fun fixPrompt(malformed: List<String>, failed: List<String>): String = buildString {
        append("[STEP FAILED] Your last modifications did not reach the code.")
        if (malformed.isNotEmpty()) {
            append("\n\nThe plugin could not parse ").append(malformed.size)
            append(" entry(ies), so they were NOT applied. Every entry needs a \"type\" ")
            append("(REPLACE_ELEMENT, CREATE_FILE, ...) and one combined \"path\" like ")
            append("\"file:src/Main.kt/class[Foo]/function[bar]\".")
            malformed.forEach { append("\n").append(it.lineSequence().first()) }
        }
        if (failed.isNotEmpty()) {
            append("\n\n").append(failed.size).append(" entry(ies) parsed but failed to apply:")
            failed.forEach { append("\n").append(it) }
        }
        append("\n\nResend the same changes in a shape that applies. ")
        append("Request the current code first if you are not sure what is in the file now.")
    }

    /**
     * Formats the bubble-footer info line for coding-agent turns.
     * Layout: `↑1234 · ↓567 · 42s` — components are omitted when their value is zero.
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
        costUsd?.takeIf { it > 0.0 }?.let { parts += "$${String.format("%.4f", it)}" }
        return if (parts.isEmpty()) null else parts.joinToString("  \u00B7  ")
    }

    private fun fmt(n: Int) = if (n >= 1000) "${n / 1000}k" else n.toString()

    private fun rejectPendingModifications(session: ChatSession) {
        val rejected = claudeCodeService().rejectPendingModifications(session.id)
        if (rejected) {
            modificationProposalView?.setRejected()
            modificationProposalView = null
            callbacks.setInputEnabled(true)
            callbacks.updateModeIndicator()
            callbacks.setStatus("Changes rejected — nothing was applied")
        } else {
            callbacks.setStatus("No pending modifications to reject")
        }
    }
}
