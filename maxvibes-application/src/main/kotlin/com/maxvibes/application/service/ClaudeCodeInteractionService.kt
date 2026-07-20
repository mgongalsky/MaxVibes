package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.application.port.output.ClaudeCodeError
import com.maxvibes.application.port.output.ClaudeCodePort
import com.maxvibes.application.port.output.ClaudeCodeSendResult
import com.maxvibes.application.port.output.ClaudeCodeSessionLogPort
import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.application.port.output.LoggerPort
import com.maxvibes.application.port.output.NotificationPort
import com.maxvibes.application.port.output.ProjectContextPort
import com.maxvibes.application.port.output.PromptPort
import com.maxvibes.application.port.output.PromptTemplates
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.code.CodeGranularity
import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.code.RequestedViewInfo
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionCommand
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.interaction.InteractionResponse
import com.maxvibes.domain.model.modification.InsertPosition
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.shared.result.Result
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.planning.TaskPlan

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

    /** In-memory workspace: messages, gathered files, prompts, project context. */
    private var sessionState: ClipboardSessionState? = null

    /** ID of the session that owns the current [sessionState]. Guards against cross-session misuse. */
    private var sessionStateOwner: String? = null

    /** Modifications proposed by the LLM, held until the user approves or rejects them. In-memory only. */
    private var pendingModifications: List<InteractionModification> = emptyList()

    /** Commands that arrived alongside the pending modifications — presented after approve+apply. */
    private var pendingCommands: List<CommandRequest> = emptyList()

    /** Commit message that accompanied the pending modifications. */
    private var pendingCommitMessage: String? = null

    /** Session that owns the pending modifications. */
    private var pendingOwner: String? = null

    // ==================== Public API ====================

    /**
     * Unified entry point — routes by current [ClipboardSessionStatus] and dispatches
     * to either a fresh task start or a continuation of an existing dialog.
     *
     * When the session is AWAITING_APPROVE with pending modifications, typing a new
     * message is interpreted as a rejection: the pending set is cleared and the message
     * is forwarded to the LLM with a prefix stating nothing was applied.
     */
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
    ): ClaudeCodeStepResult {
        // Switch the per-dialog transcript to this session BEFORE any transport call —
        // the adapter logs raw I/O without knowing the chat session id (see port contract).
        sessionLog?.begin(sessionId)
        sessionLog?.event(
            "handleUserInput",
            mapOf(
                "status" to sessionManager.statusFor(sessionId).name,
                "planOnly" to planOnly,
                "inputLen" to userInput.length
            )
        )
        return when (sessionManager.statusFor(sessionId)) {
            ClipboardSessionStatus.IDLE,
            ClipboardSessionStatus.SESSION_ACTIVE ->
                startOrContinue(
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

            ClipboardSessionStatus.AWAITING_APPROVE ->
                if (pendingOwner == sessionId && pendingModifications.isNotEmpty()) {
                    // Typing while modifications await approval = reject with feedback.
                    val rejected = pendingModifications.size
                    val hadCommands = pendingCommands.size
                    clearPending()
                    sessionManager.transition(sessionId, ClipboardEvent.Approved)
                    log("User rejected $rejected pending modification(s) by typing a new message")
                    sessionLog?.event(
                        "pending modifications rejected",
                        mapOf("mods" to rejected, "heldCommands" to hadCommands)
                    )
                    startOrContinue(
                        sessionId = sessionId,
                        userInput = "[USER REJECTED your $rejected proposed modification(s) — nothing was applied" +
                                (if (hadCommands > 0) ", the $hadCommands held command(s) were not run" else "") +
                                ". New instruction follows.]\n\n$userInput",
                        history = history,
                        attachedContext = attachedContext,
                        planOnly = planOnly,
                        ideErrors = ideErrors,
                        globalContextFiles = globalContextFiles,
                        specificPromptContent = specificPromptContent,
                        attachedImages = attachedImages
                    )
                } else {
                    // Legacy: AWAITING_APPROVE with requestedViews (e.g. restored after IDE restart).
                    ClaudeCodeStepResult.Error(
                        "Session is awaiting approve. Press Approve or Reset before sending a new message."
                    )
                }

            ClipboardSessionStatus.AWAITING_PASTE ->
                ClaudeCodeStepResult.Error(
                    "Session is in clipboard AWAITING_PASTE state — switch back to clipboard mode or reset."
                )
        }
    }

    /**
     * Confirms an [ClipboardSessionStatus.AWAITING_APPROVE] response.
     *
     * When pending modifications exist, Approve applies them (and releases any held
     * commands). Otherwise the legacy requestedViews-gathering flow runs: gathers the
     * files the LLM requested in its last assistant message, transitions the session
     * back to [ClipboardSessionStatus.SESSION_ACTIVE], and sends a minimal-context
     * follow-up to the same Claude Code process.
     *
     * @return a new [ClaudeCodeStepResult] describing the post-approve state.
     */
    suspend fun approve(
        sessionId: String,
        attachedContext: String? = null,
        ideErrors: String? = null,
        specificPromptContent: String? = null
    ): ClaudeCodeStepResult {
        sessionLog?.begin(sessionId)
        sessionLog?.event("approve", mapOf("status" to sessionManager.statusFor(sessionId).name))
        if (sessionManager.statusFor(sessionId) != ClipboardSessionStatus.AWAITING_APPROVE) {
            return error("Approve is only valid in AWAITING_APPROVE state")
        }

        // New semantics: Approve applies pending modifications when present. Checked BEFORE
        // workspace restore and requestedViews reading below — pending modifications have no
        // requestedViews, so the legacy path would fail with "No assistant message to approve".
        if (pendingOwner == sessionId && pendingModifications.isNotEmpty()) {
            return approvePendingModifications(sessionId)
        }

        // Recover workspace if the IDE was restarted between turns.
        if (sessionState == null || sessionStateOwner != sessionId) {
            log("sessionState missing or owned by another session in approve — restoring for $sessionId")
            if (!ensureWorkspace(sessionId)) {
                return error("Cannot restore session state for session $sessionId. Please start a new task.")
            }
        }
        val state = sessionState ?: return error("No active workspace — cannot approve")

        // Read the last assistant message and its requestedViews.
        val session = chatSessionRepository.getSessionById(sessionId)
            ?: return error("Session not found: $sessionId")
        val lastAssistant = session.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?: return error("No assistant message to approve")
        if (lastAssistant.requestedViews.isEmpty()) {
            return error("Last assistant message has no requestedViews to approve")
        }

        // Convert RequestedViewInfo back into CodeViewRequest for rendering.
        val viewRequests = lastAssistant.requestedViews.map { rv ->
            CodeViewRequest(
                filePath = rv.path,
                granularity = rv.granularity,
                elementPath = rv.elementPath
            )
        }

        // Render views: SKILL comes from the skill repository, FULL through
        // gatherRequestedFiles, everything else through codeRepository.getCodeView.
        val skillRequests = viewRequests.filter { it.granularity == CodeGranularity.SKILL }
        val fullPaths = viewRequests
            .filter { it.granularity == CodeGranularity.FULL }
            .map { it.filePath }
        val partialRequests = viewRequests.filter {
            it.granularity != CodeGranularity.FULL && it.granularity != CodeGranularity.SKILL
        }

        val fullFilesMap: Map<String, String> = if (fullPaths.isNotEmpty()) {
            gatherRequestedFiles(fullPaths) ?: return error("Failed to gather requested files")
        } else emptyMap()

        val partialFilesMap: Map<String, String> = partialRequests.associate { req ->
            try {
                val view = codeRepository.getCodeView(req)
                log("Rendered ${req.granularity} view for ${req.filePath} (${view.content.length} chars)")
                req.filePath to view.content
            } catch (e: Exception) {
                log("ERROR: Failed to render ${req.granularity} view for ${req.filePath}: ${e.message}")
                req.filePath to "// ERROR: Could not render ${req.granularity} view: ${e.message}"
            }
        }

        val skillFilesMap: Map<String, String> = skillRequests.associate { req ->
            val body = specificPromptService?.resolveSkillBody(req.filePath)
            log(if (body != null) "Rendered skill '${req.filePath}' (${body.length} chars)" else "Unknown skill '${req.filePath}'")
            "skill:${req.filePath}" to (body
                ?: "// ERROR: Unknown skill '${req.filePath}'. Use one of the names from the Skills section.")
        }

        val freshFiles = fullFilesMap + partialFilesMap + skillFilesMap
        // Echo the assistant message into history so the LLM sees its own previous request when
        // we send the minimal follow-up (it still has its own context, but this keeps history symmetric).
        if (lastAssistant.content.isNotBlank() &&
            state.dialogHistory.lastOrNull()?.content != lastAssistant.content
        ) {
            addToHistory(ChatRole.ASSISTANT, lastAssistant.content)
        }

        // Drive the state machine: AWAITING_APPROVE → SESSION_ACTIVE before sending the next request.
        sessionManager.transition(sessionId, ClipboardEvent.Approved)

        return doSend(
            sessionId = sessionId,
            freshFiles = freshFiles,
            isFirstMessage = false,
            attachedContext = attachedContext,
            ideErrors = ideErrors,
            specificPromptContent = specificPromptContent
        )
    }

    /**
     * Sends the outcomes of the current turn's shell commands (execution results or user
     * declines) back to Claude Code as an automatic minimal-context follow-up. Called by
     * the UI layer once ALL command blocks of the turn are resolved (Run or Decline).
     *
     * No new user message is involved — this is a protocol continuation, analogous to the
     * post-approve file delivery. Valid only in [ClipboardSessionStatus.SESSION_ACTIVE]:
     * commands never coexist with AWAITING_APPROVE, because mixed responses get their
     * commands skipped in [processResponse].
     */
    suspend fun submitCommandResults(sessionId: String, resultsForLlm: String): ClaudeCodeStepResult {
        sessionLog?.begin(sessionId)
        sessionLog?.event("submitCommandResults", mapOf("len" to resultsForLlm.length))
        if (sessionManager.statusFor(sessionId) != ClipboardSessionStatus.SESSION_ACTIVE) {
            return error("Command results can only be submitted in SESSION_ACTIVE state")
        }
        if (sessionState == null || sessionStateOwner != sessionId) {
            log("sessionState missing or owned by another session in submitCommandResults — restoring for $sessionId")
            if (!ensureWorkspace(sessionId)) {
                return error("Cannot restore session state for session $sessionId. Please start a new task.")
            }
        }
        return doSend(
            sessionId = sessionId,
            freshFiles = emptyMap(),
            isFirstMessage = false,
            commandResults = resultsForLlm
        )
    }

    /** Returns the current [ClipboardSessionStatus] for the given session. */
    fun status(sessionId: String): ClipboardSessionStatus = sessionManager.statusFor(sessionId)

    /**
     * Resets the Claude Code session: clears in-memory workspace, transitions session to IDLE,
     * and asks the transport to release its process. The next call to [handleUserInput] will
     * start a fresh process and a fresh claude session.
     */
    fun reset(sessionId: String) {
        log("Session reset (sessionId=$sessionId)")
        sessionLog?.event("reset requested", mapOf("sessionId" to sessionId))
        sessionState = null
        sessionStateOwner = null
        clearPending()
        sessionManager.transition(sessionId, ClipboardEvent.Reset)
        try {
            claudeCodePort.shutdown()
        } catch (e: Exception) {
            log("Warning: shutdown raised ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ==================== Internal flow ====================

    private suspend fun startOrContinue(
        sessionId: String,
        userInput: String,
        history: List<ChatMessageDTO>,
        attachedContext: String?,
        planOnly: Boolean,
        ideErrors: String?,
        globalContextFiles: List<String>,
        specificPromptContent: String?,
        attachedImages: List<AttachedImage> = emptyList()
    ): ClaudeCodeStepResult {
        val isFirst = sessionManager.statusFor(sessionId) == ClipboardSessionStatus.IDLE

        if (isFirst) {
            log("Starting new Claude Code session (sessionId=$sessionId, planOnly=$planOnly)")
            sessionManager.transition(sessionId, ClipboardEvent.StartSession)

            notificationPort.showProgress("Gathering project context...", 0.1)
            val projectContextResult = contextProvider.getProjectContext()
            if (projectContextResult is Result.Failure) {
                return error("Failed to get project context: ${projectContextResult.error.message}")
            }
            val projectContext = (projectContextResult as Result.Success).value

            // Strategy A: replace state.prompts with one whose chatSystem == claudeCodeSystem.
            // InteractionRequestBuilder reads from state.prompts directly — no special-casing required.
            val claudeSystem = promptPort.claudeCodeSystem()
            val claudePrompts = PromptTemplates(
                chatSystem = claudeSystem,
                planningSystem = claudeSystem
            )

            sessionState = ClipboardSessionState(
                currentMessage = userInput,
                projectContext = projectContext,
                dialogHistory = history.toMutableList(),
                prompts = claudePrompts,
                allGatheredFiles = mutableMapOf(),
                planOnly = planOnly
            )
            sessionStateOwner = sessionId
            addToHistory(ChatRole.USER, userInput)
        } else {
            log("Continuing Claude Code session (sessionId=$sessionId)")
            if (sessionState == null || sessionStateOwner != sessionId) {
                if (!ensureWorkspace(sessionId)) {
                    return error("Cannot restore session state for session $sessionId. Please start a new task.")
                }
            }
            val state = sessionState ?: return error("No active workspace")
            // Reflect the new user message in workspace state.
            sessionState = state.copy(
                currentMessage = userInput,
                planOnly = planOnly
            )
            sessionStateOwner = sessionId
            addToHistory(ChatRole.USER, userInput)
        }

        val freshFiles = if (isFirst) {
            gatherRequestedFiles(globalContextFiles) ?: emptyMap()
        } else emptyMap()

        return doSend(
            sessionId = sessionId,
            freshFiles = freshFiles,
            isFirstMessage = isFirst,
            attachedContext = attachedContext,
            ideErrors = ideErrors,
            specificPromptContent = specificPromptContent,
            attachedImages = attachedImages
        )
    }

    private suspend fun doSend(
        sessionId: String,
        freshFiles: Map<String, String>,
        isFirstMessage: Boolean,
        attachedContext: String? = null,
        ideErrors: String? = null,
        specificPromptContent: String? = null,
        commandResults: String? = null,
        attachedImages: List<AttachedImage> = emptyList()
    ): ClaudeCodeStepResult {
        streamHub?.begin(sessionId)
        val state = sessionState ?: return error("No active workspace")
        var session = chatSessionRepository.getSessionById(sessionId)
            ?: return error("Session not found: $sessionId")

        // Full context is sent on the first message OR when a previous resume failed.
        val needsFull = isFirstMessage || session.claudeCodeNeedsFullContext
        var addHistory = needsFull
        var request = buildRequest(
            state = state,
            freshFiles = freshFiles,
            isFirstMessage = needsFull,
            addHistory = addHistory,
            attachedContext = attachedContext,
            ideErrors = ideErrors,
            specificPromptContent = specificPromptContent,
            commandResults = commandResults,
            attachedImages = attachedImages,
            currentPlan = session.plan
        )

        // Pass the system prompt unconditionally on the first ensureStarted call:
        //   - fresh start (resumeId == null) — process is spawned now; it MUST receive the
        //     prompt or the model has no idea it is talking to MaxVibes and will respond as
        //     a vanilla Claude Code session (asking the user how to access PSI, etc.).
        //   - resume (resumeId != null) — process may have died between IDE sessions and
        //     will be respawned here, in which case the same need applies. If the process
        //     is already alive, the adapter's ensureStarted short-circuits before the prompt
        //     ever reaches the CLI (see ClaudeCodeProcessAdapter.ensureStarted), so passing
        //     it costs nothing.
        val resumeId = session.claudeCodeSessionId
        var ensureResult = claudeCodePort.ensureStarted(
            resumeSessionId = resumeId,
            systemPrompt = state.prompts.chatSystem
        )

        // Resume failed → start fresh. Mark needsFullContext, rebuild the request with
        // full context, and pass the system prompt for the brand-new process.
        if (ensureResult is Result.Failure && ensureResult.error is ClaudeCodeError.ResumeFailed) {
            val rf = ensureResult.error as ClaudeCodeError.ResumeFailed
            log("Resume failed for sessionId=${rf.sessionId}; falling back to fresh start.")
            sessionLog?.event(
                "resume failed — falling back to fresh start",
                mapOf("claudeSessionId" to rf.sessionId)
            )
            session = session.copy(
                claudeCodeSessionId = null,
                claudeCodeNeedsFullContext = true
            )
            chatSessionRepository.saveSession(session)

            request = buildRequest(
                state = state,
                freshFiles = freshFiles,
                isFirstMessage = true,
                addHistory = true,
                attachedContext = attachedContext,
                ideErrors = ideErrors,
                specificPromptContent = specificPromptContent,
                commandResults = commandResults,
                attachedImages = attachedImages,
                currentPlan = session.plan
            )
            ensureResult = claudeCodePort.ensureStarted(
                resumeSessionId = null,
                systemPrompt = state.prompts.chatSystem
            )
        }

        if (ensureResult is Result.Failure) {
            return ClaudeCodeStepResult.TransportError(transportErrorMessage(ensureResult.error))
        }

        // Token estimation for the UI/usage tracker.
        val totalTokens = estimateTokens(request)
        state.lastInputTokens = totalTokens

        log(
            "Sending: tokens≈$totalTokens, freshFiles=${freshFiles.size}, " +
                    "history=${request.chatHistory.size}, fullCtx=$needsFull"
        )
        sessionLog?.event(
            "sending request",
            mapOf(
                "tokensApprox" to totalTokens,
                "freshFiles" to freshFiles.size,
                "history" to request.chatHistory.size,
                "fullContext" to needsFull
            )
        )
        notificationPort.showProgress("Sending to Claude Code...", 0.5)

        // Wall-clock timer for UI "this took Ns" feedback. Distinct from the adapter's
        // internal elapsed timer — that one only covers the send() call's I/O, this one
        // covers the same span but is propagated up to the UI layer via the step result.
        val sendStartedAt = System.currentTimeMillis()
        // Live progress flows through AgentStreamHub (adapter -> hub -> UI); nothing to plumb here.
        val sendResult = claudeCodePort.send(request)
        val durationMs = System.currentTimeMillis() - sendStartedAt

        return when (sendResult) {
            is Result.Success -> {
                val payload: ClaudeCodeSendResult = sendResult.value
                // Persist observed session id and clear the fallback flag.
                val observedId = payload.observedSessionId
                if (observedId != null || session.claudeCodeNeedsFullContext) {
                    val updated = session.copy(
                        claudeCodeSessionId = observedId ?: session.claudeCodeSessionId,
                        claudeCodeNeedsFullContext = false
                    )
                    chatSessionRepository.saveSession(updated)
                }
                val stats = payload.stats
                processResponse(
                    sessionId = sessionId,
                    response = payload.response,
                    inputTokens = stats?.inputTokens?.takeIf { it > 0 } ?: totalTokens,
                    outputTokens = stats?.outputTokens?.takeIf { it > 0 }
                        ?: estimateOutputTokens(payload.response),
                    thinkingText = payload.thinkingText,
                    durationMs = stats?.durationMs?.takeIf { it > 0 } ?: durationMs,
                    costUsd = stats?.costUsd?.takeIf { it > 0.0 },
                    numTurns = stats?.numTurns?.takeIf { it > 0 }
                )
            }

            is Result.Failure -> {
                log("Send failed: ${sendResult.error} (after ${durationMs}ms)")
                sessionLog?.event(
                    "send failed",
                    mapOf("error" to sendResult.error.toString(), "elapsedMs" to durationMs)
                )
                ClaudeCodeStepResult.TransportError(transportErrorMessage(sendResult.error))
            }
        }
    }

    private suspend fun processResponse(
        sessionId: String,
        response: InteractionResponse,
        inputTokens: Int,
        outputTokens: Int,
        thinkingText: String? = null,
        durationMs: Long = 0L,
        costUsd: Double? = null,
        numTurns: Int? = null
    ): ClaudeCodeStepResult {
        val state = sessionState ?: return error("No active workspace")

        val hasViews = response.codeViewRequests.isNotEmpty()
        val hasMods = response.modifications.isNotEmpty()
        val hasQuestions = response.questions.isNotEmpty()
        val commands: List<CommandRequest> = response.commands.mapNotNull { convertCommand(it) }
        log(
            "Processing response: hasViews=$hasViews, hasMods=$hasMods, hasQuestions=$hasQuestions, " +
                    "commands=${commands.size}, msg=${response.message.take(60)}"
        )
        sessionLog?.event(
            "response",
            mapOf(
                "hasViews" to hasViews,
                "hasMods" to hasMods,
                "questions" to response.questions.size,
                "commands" to commands.size,
                "msgLen" to response.message.length,
                "thinkingLen" to (thinkingText?.length ?: 0)
            )
        )

        // Plan snapshot: full-replacement semantics, orthogonal to the approve cycle.
        // Absent field = plan unchanged; present with empty steps = explicit clear.
        response.plan?.let { snapshot ->
            chatSessionRepository.getSessionById(sessionId)?.let { current ->
                val newPlan = snapshot.takeIf { it.steps.isNotEmpty() }
                chatSessionRepository.saveSession(current.withPlan(newPlan))
                log(if (newPlan != null) "Plan updated: ${newPlan.steps.size} step(s), done=${newPlan.doneCount}" else "Plan cleared")
                sessionLog?.event("plan updated", mapOf("steps" to snapshot.steps.size))
            }
        }

        val combinedReasoning = listOfNotNull(
            thinkingText?.takeIf { it.isNotBlank() },
            response.reasoning?.takeIf { it.isNotBlank() }
        ).joinToString("\n\n").takeIf { it.isNotBlank() }

        if (response.message.isNotBlank()) {
            addToHistory(ChatRole.ASSISTANT, response.message)
        }

        val holdMods = hasMods && !state.planOnly

        if (hasViews && !holdMods) {
            persistRequestedViewsIntoDomain(sessionId, response.codeViewRequests)
        }

        sessionManager.transition(
            sessionId,
            ClipboardEvent.ResponseReceived(hasRequestedViews = hasViews || holdMods)
        )

        if (holdMods) {
            if (hasViews) {
                log("WARN: response mixed modifications with ${response.codeViewRequests.size} view request(s) — views skipped per protocol")
                sessionLog?.event(
                    "views skipped (mixed with modifications)",
                    mapOf("count" to response.codeViewRequests.size)
                )
            }
            pendingModifications = response.modifications
            pendingCommands = commands
            pendingCommitMessage = response.commitMessage?.takeIf { it.isNotBlank() }
            pendingOwner = sessionId
            log("Holding ${pendingModifications.size} modification(s) and ${commands.size} command(s) for user approval")
            sessionLog?.event(
                "modifications held for approval",
                mapOf("mods" to pendingModifications.size, "commands" to commands.size)
            )
            return ClaudeCodeStepResult.AwaitingModApprove(
                assistantMessage = response.message,
                proposedModifications = response.modifications,
                heldCommands = commands.size,
                skippedViews = if (hasViews) response.codeViewRequests.size else 0,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                llmReasoning = combinedReasoning,
                durationMs = durationMs,
                costUsd = costUsd,
                numTurns = numTurns,
                diagram = response.diagram
            )
        }

        if (hasViews) {
            if (commands.isNotEmpty()) {
                log("WARN: response mixed requestedViews with ${commands.size} command(s) — commands skipped per protocol")
                sessionLog?.event("commands skipped (mixed with requestedViews)", mapOf("count" to commands.size))
            }
            val requestedViewInfos = response.codeViewRequests.map {
                RequestedViewInfo(
                    path = it.filePath,
                    granularity = it.granularity,
                    elementPath = it.elementPath
                )
            }
            return ClaudeCodeStepResult.WaitingForApprove(
                assistantMessage = response.message,
                requestedViews = requestedViewInfos,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                llmReasoning = combinedReasoning,
                durationMs = durationMs,
                skippedCommands = commands.size,
                costUsd = costUsd,
                numTurns = numTurns,
                diagram = response.diagram
            )
        }

        if (hasQuestions) {
            if (commands.isNotEmpty()) {
                log("WARN: response mixed questions with ${commands.size} command(s) - commands skipped per protocol")
                sessionLog?.event("commands skipped (mixed with questions)", mapOf("count" to commands.size))
            }
            log("LLM asked ${response.questions.size} question(s) - awaiting user answer")
            sessionLog?.event("questions received", mapOf("count" to response.questions.size))
            return ClaudeCodeStepResult.AwaitingQuestions(
                assistantMessage = response.message,
                questions = response.questions,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                llmReasoning = combinedReasoning,
                durationMs = durationMs,
                costUsd = costUsd,
                numTurns = numTurns,
                diagram = response.diagram
            )
        }

        val modResults: List<ModificationResult> = if (hasMods && state.planOnly) {
            log("plan-only mode: skipping ${response.modifications.size} modifications")
            emptyList()
        } else {
            emptyList()
        }

        val messageText = buildString {
            if (response.message.isNotBlank()) append(response.message)
            if (isEmpty()) append("Done.")
        }

        return ClaudeCodeStepResult.Completed(
            message = messageText.trim(),
            modifications = modResults,
            success = true,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            llmReasoning = combinedReasoning,
            commitMessage = response.commitMessage?.takeIf { it.isNotBlank() },
            durationMs = durationMs,
            commands = commands,
            costUsd = costUsd,
            numTurns = numTurns,
            diagram = response.diagram
        )
    }

    /**
     * Applies the modifications held for approval, releases the commands held with them,
     * and returns a [ClaudeCodeStepResult.Completed]. The assistant message was already
     * rendered at proposal time, so the completed message stays terse.
     */
    private suspend fun approvePendingModifications(sessionId: String): ClaudeCodeStepResult {
        val mods = pendingModifications
        val commands = pendingCommands
        val commitMessage = pendingCommitMessage
        clearPending()
        sessionManager.transition(sessionId, ClipboardEvent.Approved)
        log("Applying ${mods.size} approved modification(s), ${commands.size} held command(s)")
        sessionLog?.event(
            "pending modifications approved",
            mapOf("mods" to mods.size, "commands" to commands.size)
        )

        val modResults = applyModifications(mods)
        val successCount = modResults.count { it is ModificationResult.Success }
        val failCount = modResults.size - successCount
        if (failCount > 0) notificationPort.showWarning("Applied $successCount changes, $failCount failed")
        else if (successCount > 0) notificationPort.showSuccess("Applied $successCount changes")

        return ClaudeCodeStepResult.Completed(
            message = "Applied approved modifications.",
            modifications = modResults,
            success = failCount == 0,
            commitMessage = commitMessage,
            commands = commands
        )
    }

    private fun clearPending() {
        pendingModifications = emptyList()
        pendingCommands = emptyList()
        pendingCommitMessage = null
        pendingOwner = null
    }

    // ==================== Helpers ====================

    private fun buildRequest(
        state: ClipboardSessionState,
        freshFiles: Map<String, String>,
        isFirstMessage: Boolean,
        addHistory: Boolean,
        attachedContext: String?,
        ideErrors: String?,
        specificPromptContent: String?,
        commandResults: String? = null,
        attachedImages: List<AttachedImage> = emptyList(),
        currentPlan: TaskPlan? = null
    ): ClipboardRequest = InteractionRequestBuilder.build(
        state = state,
        freshFiles = freshFiles,
        isFirstMessage = isFirstMessage,
        addHistory = addHistory,
        planOnlySuffix = PLAN_ONLY_SUFFIX,
        ideErrors = ideErrors,
        attachedContext = attachedContext,
        specificPromptContent = specificPromptContent,
        // Claude Code transport delivers the system instruction via the CLI's
        // --append-system-prompt flag at process spawn — embedding it in the
        // JSON payload would trip Claude Code's prompt-injection classifier.
        omitSystemInstruction = true,
        commandResults = commandResults,
        attachedImages = attachedImages,
        currentPlan = currentPlan
    )

    /**
     * Persists [codeViewRequests] from the LLM response into the last ASSISTANT message
     * of the domain session as typed [RequestedViewInfo] entries. No-op if the session
     * has no ASSISTANT message yet (e.g. response arrived before user message was committed).
     */
    private fun persistRequestedViewsIntoDomain(sessionId: String, codeViewRequests: List<CodeViewRequest>) {
        val session = chatSessionRepository.getSessionById(sessionId) ?: return
        val messages = session.messages.toMutableList()
        val lastAssistantIdx = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastAssistantIdx < 0) return
        val requestedViews = codeViewRequests.map { rv ->
            RequestedViewInfo(path = rv.filePath, granularity = rv.granularity, elementPath = rv.elementPath)
        }
        messages[lastAssistantIdx] = messages[lastAssistantIdx].copy(requestedViews = requestedViews)
        chatSessionRepository.saveSession(session.copy(messages = messages))
        log("Persisted ${requestedViews.size} requestedViews into domain message for session $sessionId")
    }

    /**
     * Restores the in-memory [sessionState] from persisted domain data — used after IDE restart.
     * Identical strategy to [ClipboardInteractionService.ensureWorkspace] but always uses the
     * Claude Code system prompt.
     *
     * @return true if workspace was successfully restored.
     */
    private suspend fun ensureWorkspace(sessionId: String): Boolean {
        val session = chatSessionRepository.getSessionById(sessionId) ?: return false
        val lastUserMessage = session.messages
            .lastOrNull { it.role == MessageRole.USER }
            ?.content
            ?: return false

        val projectContextResult = contextProvider.getProjectContext()
        if (projectContextResult is Result.Failure) {
            log("ERROR: Failed to get project context during workspace restore: ${projectContextResult.error.message}")
            return false
        }
        val projectContext = (projectContextResult as Result.Success).value
        val claudeSystem = promptPort.claudeCodeSystem()
        val prompts = PromptTemplates(chatSystem = claudeSystem, planningSystem = claudeSystem)

        sessionState = ClipboardSessionState(
            currentMessage = lastUserMessage,
            projectContext = projectContext,
            dialogHistory = session.messages
                .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
                .map {
                    ChatMessageDTO(
                        role = if (it.role == MessageRole.USER) ChatRole.USER else ChatRole.ASSISTANT,
                        content = it.content
                    )
                }
                .toMutableList(),
            prompts = prompts,
            allGatheredFiles = mutableMapOf(),
            planOnly = false
        )
        sessionStateOwner = sessionId
        log("Workspace restored from domain: sessionId=$sessionId, messages=${session.messages.size}")
        return true
    }

    private suspend fun gatherRequestedFiles(requestedPaths: List<String>): Map<String, String>? {
        val state = sessionState ?: return null
        if (requestedPaths.isEmpty()) return emptyMap()

        val newPaths = requestedPaths.filter { it !in state.allGatheredFiles }
        if (newPaths.isEmpty()) {
            log("All requested files already gathered, re-sending existing")
            return requestedPaths.associateWith { state.allGatheredFiles[it] ?: "" }
        }

        log("Gathering ${newPaths.size} new files...")
        notificationPort.showProgress("Gathering ${newPaths.size} files...", 0.4)

        val gatherResult = contextProvider.gatherFiles(newPaths)
        if (gatherResult is Result.Failure) {
            log("ERROR: Failed to gather files: ${gatherResult.error.message}")
            return null
        }
        val gathered = (gatherResult as Result.Success).value
        state.allGatheredFiles.putAll(gathered.files)
        log("Gathered ${gathered.files.size} files, total cached: ${state.allGatheredFiles.size}")
        return gathered.files
    }

    private suspend fun applyModifications(claudeMods: List<InteractionModification>): List<ModificationResult> {
        val modifications = claudeMods.mapNotNull { convertModification(it) }
        if (modifications.isEmpty()) return emptyList()

        log("Applying ${modifications.size} modifications...")
        notificationPort.showProgress("Applying ${modifications.size} changes...", 0.8)

        val results = codeRepository.applyModifications(modifications)
        val successCount = results.count { it is ModificationResult.Success }
        val failCount = results.size - successCount
        log("Modifications: $successCount success, $failCount failed")
        return results
    }

    /**
     * Converts an LLM-protocol [InteractionModification] into a domain [Modification].
     * Mirrors [ClipboardInteractionService.convertModification] — kept duplicated by
     * conscious decision until both services stabilise (see Step 5 "Что НЕ делать").
     */
    private fun convertModification(mod: InteractionModification): Modification? {
        if (mod.type.isBlank() || mod.path.isBlank()) return null
        val elementPath = ElementPath(mod.path)
        val elementKind = try {
            ElementKind.valueOf(mod.elementKind.uppercase())
        } catch (_: Exception) {
            ElementKind.FILE
        }
        val position = try {
            InsertPosition.valueOf(mod.position.uppercase())
        } catch (_: Exception) {
            InsertPosition.LAST_CHILD
        }

        return when (mod.type.uppercase()) {
            "CREATE_FILE" -> Modification.CreateFile(targetPath = elementPath, content = mod.content)
            "REPLACE_FILE" -> Modification.ReplaceFile(targetPath = elementPath, newContent = mod.content)
            "DELETE_FILE" -> Modification.DeleteFile(targetPath = elementPath)
            "CREATE_ELEMENT" -> Modification.CreateElement(
                targetPath = elementPath, elementKind = elementKind,
                content = mod.content, position = position
            )

            "REPLACE_ELEMENT" -> Modification.ReplaceElement(targetPath = elementPath, newContent = mod.content)
            "DELETE_ELEMENT" -> Modification.DeleteElement(targetPath = elementPath)
            "ADD_IMPORT" -> {
                val fqn = mod.importPath.ifBlank { mod.content.removePrefix("import ").trim() }
                if (fqn.isBlank()) null else Modification.AddImport(targetPath = elementPath, importPath = fqn)
            }

            "REMOVE_IMPORT" -> {
                val fqn = mod.importPath.ifBlank { mod.content.removePrefix("import ").trim() }
                if (fqn.isBlank()) null else Modification.RemoveImport(targetPath = elementPath, importPath = fqn)
            }

            "RENAME_ELEMENT" -> {
                val newName = mod.newName.trim()
                if (newName.isBlank()) null
                else Modification.RenameElement(targetPath = elementPath, newName = newName)
            }

            "SAFE_DELETE" -> Modification.SafeDelete(targetPath = elementPath)

            "MOVE_ELEMENT" -> {
                val destination = mod.destination.trim()
                if (destination.isBlank()) null
                else Modification.MoveElement(targetPath = elementPath, destination = destination)
            }

            else -> null
        }
    }

    /**
     * Converts an LLM-protocol [InteractionCommand] into a domain [CommandRequest].
     * Mirrors [ClipboardInteractionService.convertCommand] — kept duplicated by the same
     * conscious decision as [convertModification] until both services stabilise.
     */
    private fun convertCommand(cmd: InteractionCommand): CommandRequest? {
        if (cmd.command.isBlank()) return null
        return CommandRequest(
            command = cmd.command,
            reason = cmd.reason.takeIf { it.isNotBlank() },
            timeoutSec = cmd.timeoutSec.coerceIn(1, 3600)
        )
    }

    private fun addToHistory(role: ChatRole, content: String) {
        val state = sessionState ?: return
        state.dialogHistory.add(ChatMessageDTO(role = role, content = content))
    }

    private fun estimateTokens(request: ClipboardRequest): Int {
        val textSize = request.systemInstruction.length +
                request.fileTree.length +
                request.freshFiles.values.sumOf { it.length } +
                request.chatHistory.sumOf { it.content.length } +
                request.currentMessage.length +
                (request.attachedContext?.length ?: 0) +
                (request.specificPrompt?.length ?: 0) +
                (request.ideErrors?.length ?: 0) +
                (request.commandResults?.length ?: 0)
        val imageTokens = request.attachedImages.size * 1100 // rough: a ≤1568px screenshot ≈ 1.1–1.6k tokens
        return textSize / 4 + imageTokens
    }

    private fun estimateOutputTokens(response: InteractionResponse): Int {
        val text = response.message.length +
                (response.reasoning?.length ?: 0) +
                (response.commitMessage?.length ?: 0) +
                response.modifications.sumOf { it.content.length }
        return text / 4
    }

    private fun transportErrorMessage(error: ClaudeCodeError): String = when (error) {
        is ClaudeCodeError.BinaryNotFound ->
            "Claude Code binary not found. Check the path in MaxVibes settings."

        is ClaudeCodeError.Timeout ->
            "Claude Code did not respond in time."

        is ClaudeCodeError.Crashed ->
            "Claude Code process crashed: ${error.message}"

        is ClaudeCodeError.ProcessFailed ->
            "Claude Code exited with code ${error.exitCode}: ${error.stderr.take(200)}"

        is ClaudeCodeError.ResumeFailed ->
            "Failed to resume claude session ${error.sessionId}: ${error.stderr.take(200)}"

        is ClaudeCodeError.ParseFailed ->
            "Failed to parse Claude Code response: ${error.message}"

        is ClaudeCodeError.Aborted ->
            "Claude Code turn was aborted." +
                    (error.partialText?.let { " Partial output preserved (${it.length} chars)." } ?: "")
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

    companion object {
        // Duplicated from ClipboardInteractionService by design (see Step 5 "Что НЕ делать").
        // Will be unified in a future refactor once both services stabilise.
        private val PLAN_ONLY_SUFFIX = "\n\n" +
                "## PLAN-ONLY MODE — DISCUSSION REQUIRED\n\n" +
                "DO NOT generate any code changes in the modifications array.\n" +
                "Keep modifications and commands empty.\n" +
                "Your goal is to DISCUSS the plan with the user before any code is written.\n\n" +
                "Instead of code, you must:\n" +
                "1. Briefly explain what you understand from the task\n" +
                "2. List which files you plan to touch and what changes you'll make in each\n" +
                "3. Mention any architectural decisions or trade-offs\n" +
                "4. Ask the user to confirm or suggest corrections\n\n" +
                "Always output the JSON with empty modifications and put your discussion in message."
    }
}