package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.application.port.output.ClaudeCodeError
import com.maxvibes.application.port.output.ClaudeCodePort
import com.maxvibes.application.port.output.ClaudeCodeSendResult
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
import com.maxvibes.domain.model.interaction.ClaudeCodeActivity
import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.interaction.InteractionResponse
import com.maxvibes.domain.model.modification.InsertPosition
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.shared.result.Result

/**
 * Application-layer service that orchestrates the Claude Code dialog mode.
 *
 * Conceptually mirrors [ClipboardInteractionService] but replaces the manual clipboard
 * round-trip with an automatic transport via [ClaudeCodePort]. The user's "paste"
 * step disappears; in its place, when the LLM asks for files via `requestedViews`,
 * the session enters [ClipboardSessionStatus.AWAITING_APPROVE] and the UI shows an
 * Approve button. Pressing it gathers the requested files and triggers the next [send].
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
 *  - [handleUserInput] — unified entry point; routes by session status.
 *  - [approve]         — confirm an [ClipboardSessionStatus.AWAITING_APPROVE] response and continue.
 *  - [status]          — current [ClipboardSessionStatus] for a session.
 *  - [reset]           — clears in-memory state and sets session back to IDLE.
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
 * @param activityTracker        in-memory store for transient live-activity events surfaced to UI.
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
    private val activityTracker: ClaudeCodeActivityTracker
) {

    /** In-memory workspace: messages, gathered files, prompts, project context. */
    private var sessionState: ClipboardSessionState? = null

    /** ID of the session that owns the current [sessionState]. Guards against cross-session misuse. */
    private var sessionStateOwner: String? = null

    // ==================== Public API ====================

    /**
     * Unified entry point — routes by current [ClipboardSessionStatus] and dispatches
     * to either a fresh task start or a continuation of an existing dialog.
     *
     * Returns an error result for [ClipboardSessionStatus.AWAITING_APPROVE] (caller must
     * press Approve first) and for [ClipboardSessionStatus.AWAITING_PASTE] (foreign mode).
     */
    suspend fun handleUserInput(
        sessionId: String,
        userInput: String,
        history: List<ChatMessageDTO> = emptyList(),
        attachedContext: String? = null,
        planOnly: Boolean = false,
        ideErrors: String? = null,
        globalContextFiles: List<String> = emptyList(),
        specificPromptContent: String? = null
    ): ClaudeCodeStepResult {
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
                    specificPromptContent = specificPromptContent
                )

            ClipboardSessionStatus.AWAITING_APPROVE ->
                ClaudeCodeStepResult.Error(
                    "Session is awaiting approve. Press Approve or Reset before sending a new message."
                )

            ClipboardSessionStatus.AWAITING_PASTE ->
                ClaudeCodeStepResult.Error(
                    "Session is in clipboard AWAITING_PASTE state — switch back to clipboard mode or reset."
                )
        }
    }

    /**
     * Confirms an [ClipboardSessionStatus.AWAITING_APPROVE] response: gathers the files
     * the LLM requested in its last assistant message, transitions the session back to
     * [ClipboardSessionStatus.SESSION_ACTIVE] via [ClipboardEvent.Approved], and sends
     * a minimal-context follow-up to the same Claude Code process.
     *
     * @return a new [ClaudeCodeStepResult] describing the post-approve state.
     */
    suspend fun approve(
        sessionId: String,
        attachedContext: String? = null,
        ideErrors: String? = null,
        specificPromptContent: String? = null
    ): ClaudeCodeStepResult {
        if (sessionManager.statusFor(sessionId) != ClipboardSessionStatus.AWAITING_APPROVE) {
            return error("Approve is only valid in AWAITING_APPROVE state")
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

        // Render views: FULL goes through gatherRequestedFiles, partial goes through codeRepository.getCodeView.
        val fullPaths = viewRequests
            .filter { it.granularity == CodeGranularity.FULL }
            .map { it.filePath }
        val partialRequests = viewRequests.filter { it.granularity != CodeGranularity.FULL }

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

        val freshFiles = fullFilesMap + partialFilesMap
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

    /** Returns the current [ClipboardSessionStatus] for the given session. */
    fun status(sessionId: String): ClipboardSessionStatus = sessionManager.statusFor(sessionId)

    /**
     * Resets the Claude Code session: clears in-memory workspace, transitions session to IDLE,
     * and asks the transport to release its process. The next call to [handleUserInput] will
     * start a fresh process and a fresh claude session.
     */
    fun reset(sessionId: String) {
        log("Session reset (sessionId=$sessionId)")
        sessionState = null
        sessionStateOwner = null
        // Defensive: clear any in-flight live activity for this session. Normally the
        // finally block in doSend already cleared it, but reset() may race with a hung
        // send (e.g. user pressed Reset while the transport was waiting on stdout).
        activityTracker.clear(sessionId)
        sessionManager.transition(sessionId, ClipboardEvent.Reset)
        try {
            claudeCodePort.shutdown()
        } catch (e: Exception) {
            log("Warning: shutdown raised ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ==================== Internal flow ====================

    /**
     * Starts a new Claude Code session or continues an existing one.
     *
     * On IDLE → builds a fresh [ClipboardSessionState] with the Claude Code system prompt
     * (Strategy A from the plan) and transitions to SESSION_ACTIVE.
     *
     * On SESSION_ACTIVE → reuses (or restores) the existing workspace and appends the user message.
     */
    private suspend fun startOrContinue(
        sessionId: String,
        userInput: String,
        history: List<ChatMessageDTO>,
        attachedContext: String?,
        planOnly: Boolean,
        ideErrors: String?,
        globalContextFiles: List<String>,
        specificPromptContent: String?
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
            specificPromptContent = specificPromptContent
        )
    }

    /**
     * Builds the request, ensures the process is running (with `--resume` fallback to fresh start),
     * sends the request and dispatches the response to [processResponse].
     *
     * System prompt handling: on a fresh start, the MaxVibes system instruction is forwarded
     * to the adapter via `--append-system-prompt` rather than embedded into the JSON payload —
     * the latter trips Claude Code's prompt-injection classifier. On `--resume`, the prompt is
     * already installed in the existing claude session, so we pass null.
     */
    private suspend fun doSend(
        sessionId: String,
        freshFiles: Map<String, String>,
        isFirstMessage: Boolean,
        attachedContext: String? = null,
        ideErrors: String? = null,
        specificPromptContent: String? = null
    ): ClaudeCodeStepResult {
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
            specificPromptContent = specificPromptContent
        )

        // Try resume first if we have a session id. The system prompt is null on resume:
        // the existing CLI session already has the prompt installed from its first spawn.
        val resumeId = session.claudeCodeSessionId
        var ensureResult = claudeCodePort.ensureStarted(
            resumeSessionId = resumeId,
            systemPrompt = null
        )

        // Resume failed → start fresh. Mark needsFullContext, rebuild the request with
        // full context, AND now we DO pass the system prompt — it's a brand-new process.
        if (ensureResult is Result.Failure && ensureResult.error is ClaudeCodeError.ResumeFailed) {
            val rf = ensureResult.error as ClaudeCodeError.ResumeFailed
            log("Resume failed for sessionId=${rf.sessionId}; falling back to fresh start.")
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
                specificPromptContent = specificPromptContent
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
        notificationPort.showProgress("Sending to Claude Code...", 0.5)

        // Wall-clock timer for UI "this took Ns" feedback. Distinct from the adapter's
        // internal elapsed timer — that one only covers the send() call's I/O, this one
        // covers the same span but is propagated up to the UI layer via the step result.
        val sendStartedAt = System.currentTimeMillis()
        // Live activity: forward every transport-emitted event into the tracker so the UI
        // can render a transient "live bubble". The finally block guarantees the bubble is
        // cleared regardless of how the send terminates (success, transport error, throw).
        val sendResult = try {
            claudeCodePort.send(request) { activity ->
                activityTracker.update(sessionId, activity)
            }
        } finally {
            activityTracker.clear(sessionId)
        }
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
                processResponse(
                    sessionId = sessionId,
                    response = payload.response,
                    inputTokens = totalTokens,
                    outputTokens = estimateOutputTokens(payload.response),
                    durationMs = durationMs
                )
            }

            is Result.Failure -> {
                log("Send failed: ${sendResult.error} (after ${durationMs}ms)")
                ClaudeCodeStepResult.TransportError(transportErrorMessage(sendResult.error))
            }
        }
    }

    /**
     * Post-processes a successful response:
     *  - persists requestedViews into the last assistant message of the domain session,
     *  - drives the session-status state machine via [ClipboardEvent.ResponseReceived],
     *  - applies modifications when not in plan-only mode,
     *  - decides between [ClaudeCodeStepResult.WaitingForApprove] and [ClaudeCodeStepResult.Completed].
     *
     * @param durationMs wall-clock duration of the send call, propagated to the UI via the result.
     */
    private suspend fun processResponse(
        sessionId: String,
        response: InteractionResponse,
        inputTokens: Int,
        outputTokens: Int,
        durationMs: Long = 0L
    ): ClaudeCodeStepResult {
        val state = sessionState ?: return error("No active workspace")

        val hasViews = response.codeViewRequests.isNotEmpty()
        val hasMods = response.modifications.isNotEmpty()
        log("Processing response: hasViews=$hasViews, hasMods=$hasMods, msg=${response.message.take(60)}")

        // Append the assistant message to the dialog history (mirrors clipboard flow).
        if (response.message.isNotBlank()) {
            addToHistory(ChatRole.ASSISTANT, response.message)
        }

        // Persist requestedViews into the domain so the UI can render them and approve() can read them.
        if (hasViews) {
            persistRequestedViewsIntoDomain(sessionId, response.codeViewRequests)
        }

        // Drive the state machine: ResponseReceived(hasRequestedViews) decides the target status.
        // SESSION_ACTIVE/AWAITING_APPROVE → AWAITING_APPROVE if hasViews, else → SESSION_ACTIVE.
        sessionManager.transition(sessionId, ClipboardEvent.ResponseReceived(hasRequestedViews = hasViews))

        if (hasViews) {
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
                llmReasoning = response.reasoning?.takeIf { it.isNotBlank() },
                durationMs = durationMs
            )
        }

        // Apply modifications (skip if plan-only).
        val modResults: List<ModificationResult> = if (hasMods && !state.planOnly) {
            applyModifications(response.modifications)
        } else if (hasMods && state.planOnly) {
            log("plan-only mode: skipping ${response.modifications.size} modifications")
            emptyList()
        } else {
            emptyList()
        }

        val successCount = modResults.count { it is ModificationResult.Success }
        val failCount = modResults.size - successCount
        if (modResults.isNotEmpty()) {
            if (failCount > 0) notificationPort.showWarning("Applied $successCount changes, $failCount failed")
            else notificationPort.showSuccess("Applied $successCount changes")
        }

        val messageText = buildString {
            if (response.message.isNotBlank()) append(response.message)
            if (isEmpty()) append("Done.")
        }

        return ClaudeCodeStepResult.Completed(
            message = messageText.trim(),
            modifications = modResults,
            success = failCount == 0,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            llmReasoning = response.reasoning?.takeIf { it.isNotBlank() },
            commitMessage = response.commitMessage?.takeIf { it.isNotBlank() },
            durationMs = durationMs
        )
    }

    // ==================== Helpers ====================

    private fun buildRequest(
        state: ClipboardSessionState,
        freshFiles: Map<String, String>,
        isFirstMessage: Boolean,
        addHistory: Boolean,
        attachedContext: String?,
        ideErrors: String?,
        specificPromptContent: String?
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
        omitSystemInstruction = true
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

            else -> null
        }
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
                (request.ideErrors?.length ?: 0)
        return textSize / 4
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
                "Keep modifications empty.\n" +
                "Your goal is to DISCUSS the plan with the user before any code is written.\n\n" +
                "Instead of code, you must:\n" +
                "1. Briefly explain what you understand from the task\n" +
                "2. List which files you plan to touch and what changes you'll make in each\n" +
                "3. Mention any architectural decisions or trade-offs\n" +
                "4. Ask the user to confirm or suggest corrections\n\n" +
                "Always output the JSON with empty modifications and put your discussion in message."
    }
}
