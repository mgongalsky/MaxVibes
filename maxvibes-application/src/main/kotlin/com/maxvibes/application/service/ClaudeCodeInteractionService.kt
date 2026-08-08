package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.application.port.output.ClaudeCodeSessionLogPort
import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.application.port.output.LoggerPort
import com.maxvibes.application.port.output.NotificationPort
import com.maxvibes.application.port.output.ProjectContextPort
import com.maxvibes.application.port.output.PromptPort
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.application.port.output.CodingAgentCliPort

class ClaudeCodeInteractionService(
    contextProvider: ProjectContextPort,
    claudeCodePort: CodingAgentCliPort,
    codeRepository: CodeRepository,
    notificationPort: NotificationPort,
    promptPort: PromptPort,
    private val logger: LoggerPort? = null,
    private val sessionManager: ClipboardSessionManager,
    chatSessionRepository: ChatSessionRepository,
    private val sessionLog: ClaudeCodeSessionLogPort? = null,
    specificPromptService: SpecificPromptService? = null,
    streamHub: AgentStreamHub? = null
) {
    private val pendingStore = PendingModificationsStore()

    private val viewResolver = CodingAgentViewResolver(
        contextProvider = contextProvider,
        codeRepository = codeRepository,
        specificPromptService = specificPromptService,
        notificationPort = notificationPort,
        logger = logger
    )

    private val responseHandler = CodingAgentResponseHandler(
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

    private val approvalService = CodingAgentApprovalService(
        chatSessionRepository = chatSessionRepository,
        sessionManager = sessionManager,
        pendingStore = pendingStore,
        workspaceService = workspaceService,
        viewResolver = viewResolver,
        codeRepository = codeRepository,
        notificationPort = notificationPort,
        sessionLog = sessionLog,
        logger = logger
    )

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

    suspend fun approve(
        sessionId: String,
        attachedContext: String? = null,
        ideErrors: String? = null,
        specificPromptContent: String? = null
    ): ClaudeCodeStepResult = when (
        val outcome = approvalService.approve(
            sessionId = sessionId,
            attachedContext = attachedContext,
            ideErrors = ideErrors,
            specificPromptContent = specificPromptContent
        )
    ) {
        is CodingAgentApprovalOutcome.Continue -> send(outcome.command)
        is CodingAgentApprovalOutcome.Immediate -> outcome.result
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
        if (!workspaceService.ensure(sessionId)) {
            return error(
                "Cannot restore session state for session $sessionId. Please start a new task."
            )
        }

        return send(
            CodingAgentTurnCommand(
                sessionId = sessionId,
                commandResults = resultsForLlm
            )
        )
    }

    fun status(sessionId: String): ClipboardSessionStatus =
        sessionManager.statusFor(sessionId)

    fun reset(sessionId: String) {
        log("Session reset (sessionId=$sessionId)")
        sessionLog?.event(
            "reset requested",
            mapOf("sessionId" to sessionId)
        )
        workspaceService.clear()
        pendingStore.clear()
        sessionManager.transition(sessionId, ClipboardEvent.Reset)
        turnExecutor.shutdown()
    }

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

            ClipboardSessionStatus.AWAITING_APPROVE ->
                approvalService.rejectPending(command)
                    ?.let { startOrContinue(it) }
                    ?: ClaudeCodeStepResult.Error(
                        "Session is awaiting approve. Press Approve or Reset before sending a new message."
                    )

            ClipboardSessionStatus.AWAITING_PASTE ->
                ClaudeCodeStepResult.Error(
                    "Session is in clipboard AWAITING_PASTE state — switch back to clipboard mode or reset."
                )
        }
    }

    private suspend fun startOrContinue(
        command: UserInputCommand
    ): ClaudeCodeStepResult {
        val isFirst = sessionManager.statusFor(command.sessionId) ==
                ClipboardSessionStatus.IDLE

        if (isFirst) {
            log(
                "Starting new Claude Code session " +
                        "(sessionId=${command.sessionId}, planOnly=${command.planOnly})"
            )
            sessionManager.transition(
                command.sessionId,
                ClipboardEvent.StartSession
            )
        } else {
            log("Continuing Claude Code session (sessionId=${command.sessionId})")
        }

        val workspaceResult = if (isFirst) {
            workspaceService.start(command)
        } else {
            workspaceService.continueSession(command)
        }
        val state = when (workspaceResult) {
            is CodingAgentWorkspaceResult.Ready -> workspaceResult.state
            is CodingAgentWorkspaceResult.Failure ->
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

        return send(
            CodingAgentTurnCommand(
                sessionId = command.sessionId,
                freshFiles = freshFiles,
                firstMessage = isFirst,
                attachedContext = command.attachedContext,
                ideErrors = command.ideErrors,
                specificPromptContent = command.specificPromptContent,
                attachedImages = command.attachedImages
            )
        )
    }

    private suspend fun send(
        command: CodingAgentTurnCommand
    ): ClaudeCodeStepResult {
        val state = workspaceService.state
            ?: return error("No active workspace")

        return when (val execution = turnExecutor.execute(command, state)) {
            is CodingAgentTurnExecutionResult.Success ->
                responseHandler.handle(
                    sessionId = command.sessionId,
                    turn = execution.turn,
                    state = state
                )

            is CodingAgentTurnExecutionResult.Failure ->
                execution.result
        }
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
