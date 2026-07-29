package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.application.port.output.ClaudeCodePort
import com.maxvibes.application.port.output.ClaudeCodeSessionLogPort
import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.application.port.output.LoggerPort
import com.maxvibes.application.port.output.NotificationPort
import com.maxvibes.application.port.output.ProjectContextPort
import com.maxvibes.application.port.output.PromptPort
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.domain.model.interaction.AttachedImage

/**
 * Application-layer service that orchestrates the Claude Code dialog mode.
 *
 * Conceptually mirrors [ClipboardInteractionService] but replaces the manual clipboard
 * round-trip with an automatic transport via [ClaudeCodePort]. The user's "paste"
 * step disappears; in its place, when the LLM asks for files via `requestedViews`,
 * the session enters [ClipboardSessionStatus.AWAITING_APPROVE] and the UI shows an
 * Approve button. Pressing it gathers the requested files and triggers the next [send].
 *
 * Modifications approval: when the LLM proposes `modifications`, the service no longer
 * applies them immediately. It holds them (plus any commands and commit message that
 * arrived with them) in-memory and enters AWAITING_APPROVE. Approve applies them; typing
 * a new message rejects them with a feedback prefix. Held commands run after a successful
 * apply. In-memory only: an IDE restart before Approve loses the pending set.
 *
 * Shell commands: when the LLM requests terminal commands via the `commands` field,
 * they bypass the state machine entirely — the session stays SESSION_ACTIVE, the UI
 * renders per-command Run/Decline blocks, and once every command is resolved the
 * controller calls [submitCommandResults] to continue the dialog automatically.
 * Responses that mix `commands` with `requestedViews` get their commands skipped
 * (files win); responses that mix `requestedViews` with `modifications` get their
 * views skipped (modifications win) — see [processResponse].
 *
 * Reuses the existing clipboard stack as-is: [InteractionRequestBuilder] for request
 * assembly, [ClipboardSessionState] for the in-memory workspace, [ClipboardSessionManager]
 * for the state machine, and [CodeRepository] for applying PSI modifications.
 *
 * Token-saving policy: the first send for a session carries the full context
 * (system prompt + history + file tree); subsequent sends are minimal — just the
 * latest message and any freshly gathered files. After a failed `--resume`, the
 * service marks [com.maxvibes.domain.model.chat.ChatSession.claudeCodeNeedsFullContext]
 * so the next send replays the full context to the freshly started process.
 *
 * Public API surface:
 *  - [handleUserInput]       — unified entry point; routes by session status.
 *  - [approve]               — confirm an [ClipboardSessionStatus.AWAITING_APPROVE] response and continue.
 *  - [submitCommandResults]  — send resolved shell-command outcomes back as an automatic follow-up.
 *  - [status]                — current [ClipboardSessionStatus] for a session.
 *  - [reset]                 — clears in-memory state and sets session back to IDLE.
 *
 * Concurrency: the service is single-threaded by contract. Callers must not invoke
 * [handleUserInput]/[approve] concurrently for the same project.
 *
 * @param contextProvider        supplies project tree and file content.
 * @param claudeCodePort         transport to the running `claude` CLI process.
 * @param codeRepository         applies PSI-level code modifications.
 * @param notificationPort       displays progress/success/warning notifications in the IDE.
 * @param promptPort             supplies system prompt templates; required for Claude Code mode.
 * @param logger                 optional logger; pass null in unit tests to suppress output.
 * @param sessionManager         clipboard-status state-machine manager (shared with clipboard mode).
 * @param chatSessionRepository  read/write access to persisted chat sessions.
 * @param sessionLog             optional per-dialog verbose transcript ([ClaudeCodeSessionLogPort]).
 *                               The service calls begin(sessionId) at every entry point so the
 *                               transport's raw I/O lands in the right dialog file. Null disables.
 * @param specificPromptService optional resolver for SKILL requestedViews.
 */
class ClaudeCodeInteractionService(
    private val contextProvider: ProjectContextPort,
    private val claudeCodePort: ClaudeCodePort,
    private val codeRepository: CodeRepository,
    private val notificationPort: NotificationPort,
    private val promptPort: PromptPort,
    private val logger: LoggerPort? = null,
    private val sessionManager: ClipboardSessionManager,
    private val chatSessionRepository: ChatSessionRepository,
    private val sessionLog: ClaudeCodeSessionLogPort? = null,
    private val specificPromptService: SpecificPromptService? = null,
    private val streamHub: AgentStreamHub? = null
) {

    /** Modification sets proposed by the LLM, held until the user approves or rejects them. In-memory only. */
    private val pendingStore = PendingModificationsStore()
    private val viewResolver = ClaudeCodeViewResolver(
        contextProvider = contextProvider,
        codeRepository = codeRepository,
        specificPromptService = specificPromptService,
        notificationPort = notificationPort,
        logger = logger
    )
    private val responseHandler = ClaudeCodeResponseHandler(
        chatSessionRepository = chatSessionRepository,
        sessionManager = sessionManager,
        pendingStore = pendingStore,
        sessionLog = sessionLog,
        logger = logger
    )
    private val turnExecutor = ClaudeCodeTurnExecutor(
        claudeCodePort = claudeCodePort,
        chatSessionRepository = chatSessionRepository,
        notificationPort = notificationPort,
        sessionLog = sessionLog,
        streamHub = streamHub,
        logger = logger
    )
    private val workspaceService = ClaudeCodeWorkspaceService(
        contextProvider = contextProvider,
        promptPort = promptPort,
        chatSessionRepository = chatSessionRepository,
        notificationPort = notificationPort,
        logger = logger
    )

    // ==================== Public API ====================

    suspend fun handleUserInput(
        sessionId: String,
        userInput: String,
        history: List<ChatMessageDTO> = emptyList(),
        attachedContext: String? = null,
        planOnly: Boolean = false,
        ideErrors: String? = null,
        globalContextFiles: List<String> = emptyList(),
        specificPromptContent: String? = null,
        attachedImages: List<AttachedImage> = emptyList()
    ): ClaudeCodeStepResult = handleUserInputCommand(
        UserInputCommand(
            sessionId = sessionId,
            userInput = userInput,
            history = history,
            attachedContext = attachedContext,
            planOnly = planOnly,
            ideErrors = ideErrors,
            globalContextFiles = globalContextFiles,
            specificPromptContent = specificPromptContent,
            attachedImages = attachedImages
        )
    )

    private suspend fun handleUserInputCommand(
        command: UserInputCommand
    ): ClaudeCodeStepResult {
        sessionLog?.begin(command.sessionId)
        sessionLog?.event(
            "handleUserInput",
            mapOf(
                "status" to sessionManager.statusFor(command.sessionId).name,
                "planOnly" to command.planOnly,
                "inputLen" to command.userInput.length
            )
        )

        return when (sessionManager.statusFor(command.sessionId)) {
            ClipboardSessionStatus.IDLE,
            ClipboardSessionStatus.SESSION_ACTIVE ->
                startOrContinue(command)

            ClipboardSessionStatus.AWAITING_APPROVE -> {
                val rejectedSet = pendingStore.take(command.sessionId)
                if (rejectedSet != null) {
                    val rejected = rejectedSet.modifications.size
                    val hadCommands = rejectedSet.commands.size
                    sessionManager.transition(command.sessionId, ClipboardEvent.Approved)
                    log("User rejected $rejected pending modification(s) by typing a new message")
                    sessionLog?.event(
                        "pending modifications rejected",
                        mapOf(
                            "mods" to rejected,
                            "heldCommands" to hadCommands
                        )
                    )

                    val rejectionMessage = buildString {
                        append("[USER REJECTED your ")
                        append(rejected)
                        append(" proposed modification(s) — nothing was applied")
                        if (hadCommands > 0) {
                            append(", the ")
                            append(hadCommands)
                            append(" held command(s) were not run")
                        }
                        appendLine(". New instruction follows.]")
                        appendLine()
                        append(command.userInput)
                    }

                    startOrContinue(
                        command.copy(userInput = rejectionMessage)
                    )
                } else {
                    ClaudeCodeStepResult.Error(
                        "Session is awaiting approve. Press Approve or Reset before sending a new message."
                    )
                }
            }

            ClipboardSessionStatus.AWAITING_PASTE ->
                ClaudeCodeStepResult.Error(
                    "Session is in clipboard AWAITING_PASTE state — switch back to clipboard mode or reset."
                )
        }
    }

    suspend fun approve(
        sessionId: String,
        attachedContext: String? = null,
        ideErrors: String? = null,
        specificPromptContent: String? = null
    ): ClaudeCodeStepResult {
        sessionLog?.begin(sessionId)
        sessionLog?.event(
            "approve",
            mapOf("status" to sessionManager.statusFor(sessionId).name)
        )
        if (sessionManager.statusFor(sessionId) != ClipboardSessionStatus.AWAITING_APPROVE) {
            return error("Approve is only valid in AWAITING_APPROVE state")
        }

        if (pendingStore.hasPendingFor(sessionId)) {
            return approvePendingModifications(sessionId)
        }

        if (workspaceService.state == null || workspaceService.owner != sessionId) {
            log("sessionState missing or owned by another session in approve — restoring for $sessionId")
            if (!workspaceService.ensure(sessionId)) {
                return error("Cannot restore session state for session $sessionId. Please start a new task.")
            }
        }
        val state = workspaceService.state
            ?: return error("No active workspace — cannot approve")

        val session = chatSessionRepository.getSessionById(sessionId)
            ?: return error("Session not found: $sessionId")
        val lastAssistant = session.messages.lastOrNull {
            it.role == MessageRole.ASSISTANT
        } ?: return error("No assistant message to approve")
        if (lastAssistant.requestedViews.isEmpty()) {
            return error("Last assistant message has no requestedViews to approve")
        }

        val viewRequests = lastAssistant.requestedViews.map { requestedView ->
            CodeViewRequest(
                filePath = requestedView.path,
                granularity = requestedView.granularity,
                elementPath = requestedView.elementPath
            )
        }
        val freshFiles = viewResolver.resolve(viewRequests, state)
            ?: return error("Failed to gather requested files")

        if (
            lastAssistant.content.isNotBlank() &&
            state.dialogHistory.lastOrNull()?.content != lastAssistant.content
        ) {
            workspaceService.appendAssistantHistory(lastAssistant.content)
        }

        sessionManager.transition(sessionId, ClipboardEvent.Approved)

        return doSend(
            ClaudeCodeTurnCommand(
                sessionId = sessionId,
                freshFiles = freshFiles,
                attachedContext = attachedContext,
                ideErrors = ideErrors,
                specificPromptContent = specificPromptContent
            )
        )
    }

    suspend fun submitCommandResults(
        sessionId: String,
        resultsForLlm: String
    ): ClaudeCodeStepResult {
        sessionLog?.begin(sessionId)
        sessionLog?.event(
            "submitCommandResults",
            mapOf("len" to resultsForLlm.length)
        )
        if (sessionManager.statusFor(sessionId) != ClipboardSessionStatus.SESSION_ACTIVE) {
            return error("Command results can only be submitted in SESSION_ACTIVE state")
        }
        if (workspaceService.state == null || workspaceService.owner != sessionId) {
            log("sessionState missing or owned by another session in submitCommandResults — restoring for $sessionId")
            if (!workspaceService.ensure(sessionId)) {
                return error("Cannot restore session state for session $sessionId. Please start a new task.")
            }
        }
        return doSend(
            ClaudeCodeTurnCommand(
                sessionId = sessionId,
                commandResults = resultsForLlm
            )
        )
    }

    /** Returns the current [ClipboardSessionStatus] for the given session. */
    fun status(sessionId: String): ClipboardSessionStatus = sessionManager.statusFor(sessionId)

    fun reset(sessionId: String) {
        log("Session reset (sessionId=$sessionId)")
        sessionLog?.event(
            "reset requested",
            mapOf("sessionId" to sessionId)
        )
        workspaceService.clear()
        pendingStore.clear()
        sessionManager.transition(sessionId, ClipboardEvent.Reset)
        try {
            claudeCodePort.shutdown()
        } catch (exception: Exception) {
            log(
                "Warning: shutdown raised ${exception.javaClass.simpleName}: ${exception.message}"
            )
        }
    }

    // ==================== Internal flow ====================

    private suspend fun startOrContinue(
        command: UserInputCommand
    ): ClaudeCodeStepResult {
        val sessionId = command.sessionId
        val isFirst = sessionManager.statusFor(sessionId) == ClipboardSessionStatus.IDLE

        if (isFirst) {
            log("Starting new Claude Code session (sessionId=$sessionId, planOnly=${command.planOnly})")
            sessionManager.transition(sessionId, ClipboardEvent.StartSession)
        } else {
            log("Continuing Claude Code session (sessionId=$sessionId)")
        }

        val workspaceResult = if (isFirst) {
            workspaceService.start(command)
        } else {
            workspaceService.continueSession(command)
        }
        val state = when (workspaceResult) {
            is ClaudeCodeWorkspaceResult.Ready -> workspaceResult.state
            is ClaudeCodeWorkspaceResult.Failure ->
                return error(workspaceResult.message)
        }

        val freshFiles = if (isFirst) {
            viewResolver.gatherFullFiles(
                command.globalContextFiles,
                state
            ) ?: emptyMap()
        } else {
            emptyMap()
        }

        return doSend(
            ClaudeCodeTurnCommand(
                sessionId = sessionId,
                freshFiles = freshFiles,
                firstMessage = isFirst,
                attachedContext = command.attachedContext,
                ideErrors = command.ideErrors,
                specificPromptContent = command.specificPromptContent,
                attachedImages = command.attachedImages
            )
        )
    }

    private suspend fun doSend(
        command: ClaudeCodeTurnCommand
    ): ClaudeCodeStepResult {
        val state = workspaceService.state
            ?: return error("No active workspace")

        return when (val execution = turnExecutor.execute(command, state)) {
            is ClaudeCodeTurnExecutionResult.Success ->
                responseHandler.handle(
                    sessionId = command.sessionId,
                    turn = execution.turn,
                    state = state
                )

            is ClaudeCodeTurnExecutionResult.Failure ->
                execution.result
        }
    }

    /**
     * Applies the modifications held for approval, releases the commands held with them,
     * and returns a [ClaudeCodeStepResult.Completed]. The assistant message was already
     * rendered at proposal time, so the completed message stays terse.
     */
    private suspend fun approvePendingModifications(sessionId: String): ClaudeCodeStepResult {
        val pending = pendingStore.take(sessionId)
            ?: return error("No pending modifications to approve")
        sessionManager.transition(sessionId, ClipboardEvent.Approved)
        log("Applying ${pending.modifications.size} approved modification(s), ${pending.commands.size} held command(s)")
        sessionLog?.event(
            "pending modifications approved",
            mapOf("mods" to pending.modifications.size, "commands" to pending.commands.size)
        )

        val modResults = applyModifications(pending.modifications)
        val successCount = modResults.count { it is ModificationResult.Success }
        val failCount = modResults.size - successCount
        if (failCount > 0) notificationPort.showWarning("Applied $successCount changes, $failCount failed")
        else if (successCount > 0) notificationPort.showSuccess("Applied $successCount changes")

        return ClaudeCodeStepResult.Completed(
            message = "Applied approved modifications.",
            modifications = modResults,
            success = failCount == 0,
            commitMessage = pending.commitMessage,
            commands = pending.commands
        )
    }

    // ==================== Helpers ====================

    private suspend fun applyModifications(claudeMods: List<InteractionModification>): List<ModificationResult> {
        val modifications = claudeMods.mapNotNull { ProtocolConverter.convertModification(it) }
        if (modifications.isEmpty()) return emptyList()

        log("Applying ${modifications.size} modifications...")
        notificationPort.showProgress("Applying ${modifications.size} changes...", 0.8)

        val results = codeRepository.applyModifications(modifications)
        val successCount = results.count { it is ModificationResult.Success }
        val failCount = results.size - successCount
        log("Modifications: $successCount success, $failCount failed")
        return results
    }

    private fun log(message: String) {
        println("[MaxVibes ClaudeCode] $message")
        logger?.info("ClaudeCode", message)
    }

    private fun error(message: String): ClaudeCodeStepResult.Error {
        println("[MaxVibes ClaudeCode] ERROR: $message")
        logger?.error("ClaudeCode", message)
        return ClaudeCodeStepResult.Error(message)
    }

}