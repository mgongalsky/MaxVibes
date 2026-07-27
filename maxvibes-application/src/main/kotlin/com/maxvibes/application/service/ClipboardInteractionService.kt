package com.maxvibes.application.service

import com.maxvibes.application.port.output.*
import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.interaction.*
import com.maxvibes.domain.model.modification.InsertPosition
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.shared.result.Result
import com.maxvibes.application.port.output.LoggerPort
import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.domain.model.code.RequestedViewInfo

/**
 * Application-layer service that orchestrates the clipboard-mode LLM dialog.
 *
 * Builds JSON requests, copies them to the system clipboard, parses pasted LLM responses,
 * and applies resulting code modifications via [codeRepository].
 *
 * Shell commands: when the LLM requests terminal commands via the `commands` field, they
 * bypass the state machine — the session stays SESSION_ACTIVE, the UI renders per-command
 * Run/Decline blocks, and once every command is resolved the controller calls
 * [submitCommandResults] to continue the dialog as a normal round-trip. Responses that mix
 * `commands` with file requests get their commands skipped (files win) — see
 * [processUnifiedResponse].
 *
 * State transitions are delegated to [ClipboardSessionManager].
 *
 * Public API surface:
 * - [handleUserInput] — unified entry point; routes by session status.
 * - [startTask] / [continueDialog] / [handlePastedResponse] — explicit stage calls.
 * - [submitCommandResults] — send resolved shell-command outcomes back as a new round-trip.
 * - [status] — current [ClipboardSessionStatus] for a session.
 * - [reset] — clears in-memory state and sets session back to IDLE.
 * - [redoLastRequest] — re-generates JSON for any session, even after switching chats.
 *
 * @param contextProvider        Provides project file tree and file content.
 * @param clipboardPort          Copies requests to and parses responses from the system clipboard.
 * @param codeRepository         Applies PSI-level code modifications.
 * @param notificationPort       Displays progress/success/warning notifications in the IDE.
 * @param promptPort             Supplies system prompt templates; falls back to [PromptTemplates.EMPTY].
 * @param logger                 Optional logger; pass null in unit tests to suppress output.
 * @param sessionManager         State-machine manager; wired in by MaxVibesService DI.
 * @param chatSessionRepository  Read/write access to persisted chat sessions.
 * @param specificPromptService optional resolver for SKILL requestedViews.
 */
class ClipboardInteractionService(
    private val contextProvider: ProjectContextPort,
    private val clipboardPort: ClipboardPort,
    private val codeRepository: CodeRepository,
    private val notificationPort: NotificationPort,
    private val promptPort: PromptPort? = null,
    private val logger: LoggerPort? = null,
    private val sessionManager: ClipboardSessionManager,
    private val chatSessionRepository: ChatSessionRepository,
    private val specificPromptService: SpecificPromptService? = null
) {
    /** In-memory workspace: messages, gathered files, prompts, project context. */
    private var sessionState: ClipboardSessionState? = null

    /** ID of the session that owns the current [sessionState]. Guards against cross-session misuse. */
    private var sessionStateOwner: String? = null

    /** Validator used to diagnose parse failures and produce LLM-facing error payloads. */
    private val responseValidator = InteractionResponseValidator()

    // ==================== Status Routing ====================

    /**
     * Returns the current clipboard status for the given session.
     * Delegates directly to [ClipboardSessionManager.statusFor].
     */
    private fun currentStatus(sessionId: String): ClipboardSessionStatus =
        sessionManager.statusFor(sessionId)

    // ==================== Public API ====================

    suspend fun handleUserInput(
        sessionId: String,
        userInput: String,
        history: List<ChatMessageDTO> = emptyList(),
        attachedContext: String? = null,
        planOnly: Boolean = false,
        ideErrors: String? = null,
        globalContextFiles: List<String> = emptyList(),
        addHistory: Boolean = false,
        specificPromptContent: String? = null
    ): ClipboardStepResult = when (currentStatus(sessionId)) {
        ClipboardSessionStatus.AWAITING_PASTE -> handlePastedResponse(sessionId, userInput)
        ClipboardSessionStatus.SESSION_ACTIVE -> continueDialog(
            sessionId = sessionId,
            message = userInput,
            attachedContext = attachedContext,
            planOnly = planOnly,
            ideErrors = ideErrors,
            globalContextFiles = globalContextFiles,
            addHistory = addHistory,
            specificPromptContent = specificPromptContent
        )

        ClipboardSessionStatus.IDLE -> startTask(
            sessionId = sessionId,
            currentMessage = userInput,
            history = history,
            attachedContext = attachedContext,
            planOnly = planOnly,
            ideErrors = ideErrors,
            globalContextFiles = globalContextFiles,
            addHistory = addHistory,
            specificPromptContent = specificPromptContent
        )

        // TODO Step 5/8: AWAITING_APPROVE is Claude Code-specific and should never reach this service.
        //  Returning an Error keeps the when exhaustive without committing to behavior here.
        ClipboardSessionStatus.AWAITING_APPROVE -> ClipboardStepResult.Error(
            "AWAITING_APPROVE is not handled by ClipboardInteractionService (Claude Code mode — see Step 5/8)"
        )
    }

    suspend fun startTask(
        sessionId: String,
        currentMessage: String,
        history: List<ChatMessageDTO> = emptyList(),
        attachedContext: String? = null,
        planOnly: Boolean = false,
        ideErrors: String? = null,
        globalContextFiles: List<String> = emptyList(),
        addHistory: Boolean = false,
        specificPromptContent: String? = null
    ): ClipboardStepResult {
        log("Starting clipboard session: planOnly=$planOnly, addHistory=$addHistory")

        // Transition IDLE -> SESSION_ACTIVE
        sessionManager.transition(sessionId, ClipboardEvent.StartSession)

        notificationPort.showProgress("Gathering project context...", 0.1)
        val projectContextResult = contextProvider.getProjectContext()
        if (projectContextResult is Result.Failure) {
            return error("Failed to get project context: ${projectContextResult.error.message}")
        }
        val projectContext = (projectContextResult as Result.Success).value
        val prompts = promptPort?.getPrompts() ?: PromptTemplates.EMPTY

        log("Project: ${projectContext.name}, files in tree: ${projectContext.fileTree.totalFiles}")

        sessionState = ClipboardSessionState(
            currentMessage = currentMessage,
            projectContext = projectContext,
            dialogHistory = history.toMutableList(),
            prompts = prompts,
            allGatheredFiles = mutableMapOf(),
            planOnly = planOnly
        )
        sessionStateOwner = sessionId

        addToHistory(ChatRole.USER, currentMessage)
        val freshFiles = gatherRequestedFiles(globalContextFiles) ?: emptyMap()
        return generateAndCopyJson(
            sessionId = sessionId,
            freshFiles = freshFiles,
            isFirstMessage = true,
            addHistory = addHistory,
            ideErrors = ideErrors,
            attachedContext = attachedContext,
            specificPromptContent = specificPromptContent
        )
    }

    suspend fun continueDialog(
        sessionId: String,
        message: String,
        attachedContext: String? = null,
        planOnly: Boolean? = null,
        ideErrors: String? = null,
        globalContextFiles: List<String> = emptyList(),
        addHistory: Boolean = false,
        specificPromptContent: String? = null
    ): ClipboardStepResult {
        if (sessionState == null) {
            log("sessionState is null in continueDialog — attempting workspace restore for session $sessionId")
            if (!ensureWorkspace(sessionId)) {
                return error("Cannot restore session state for session $sessionId. Please start a new task.")
            }
            log("Workspace restored successfully, continuing dialog")
        }

        val state = sessionState
            ?: return error("No active clipboard session. Start a new task first.")

        log("Continuing dialog: addHistory=$addHistory")

        if (planOnly != null) {
            sessionState = state.copy(planOnly = planOnly)
        }

        addToHistory(ChatRole.USER, message)

        val filesToGather = if (addHistory) globalContextFiles else emptyList()
        val freshFiles = gatherRequestedFiles(filesToGather) ?: emptyMap()
        return generateAndCopyJson(
            sessionId = sessionId,
            freshFiles = freshFiles,
            isFirstMessage = false,
            addHistory = addHistory,
            ideErrors = ideErrors,
            attachedContext = attachedContext,
            specificPromptContent = specificPromptContent
        )
    }

    /**
     * Handles a raw LLM response pasted by the user.
     *
     * Wraps [handlePastedResponseInternal] in a top-level exception handler so unexpected
     * errors are surfaced as [ClipboardStepResult.Error] rather than crashing.
     */
    suspend fun handlePastedResponse(sessionId: String, rawText: String): ClipboardStepResult {
        return try {
            handlePastedResponseInternal(sessionId, rawText)
        } catch (e: Exception) {
            val msg = "Unexpected error processing response: ${e.javaClass.simpleName}: ${e.message}"
            println("[MaxVibes Clipboard] FATAL: $msg")
            logger?.error("Clipboard", msg, e)
            ClipboardStepResult.Error(msg)
        }
    }

    private suspend fun handlePastedResponseInternal(sessionId: String, rawText: String): ClipboardStepResult {
        // Auto-restore workspace after IDE restart if sessionState was lost
        if (sessionState == null) {
            log("sessionState is null in handlePastedResponse — attempting workspace restore for session $sessionId")
            if (!ensureWorkspace(sessionId)) {
                return error("Cannot restore session state for session $sessionId. Please start a new task.")
            }
            log("Workspace restored successfully, processing pasted response")
        }

        val state = sessionState
            ?: return error("No active clipboard session. Start a new task first.")

        log("Parsing pasted response (${rawText.length} chars)...")

        val validationResult = responseValidator.validate(rawText, clipboardPort)

        if (validationResult !is ValidationResult.Valid) {
            val details = when (validationResult) {
                is ValidationResult.ParseFailure -> validationResult.details
                ValidationResult.EmptyInput -> ParseFailureDetails.NoJsonFound(
                    hint = "The pasted text was empty."
                )

                else -> ParseFailureDetails.NoJsonFound(hint = "Unknown parse failure.")
            }
            val errorJson = responseValidator.buildErrorFeedbackJson(
                details = details,
                originalPreview = rawText.trim().take(InteractionResponseValidator.PREVIEW_LENGTH)
            )
            val copied = clipboardPort.copyRawText(errorJson)
            val detail = details.humanDescription()

            log("Parse failed (${details.reasonCode()}): $detail")
            logger?.warn(
                "Clipboard", "parse failure", data = mapOf(
                    "reason" to details.reasonCode(),
                    "detail" to detail.take(120),
                    "clipboardCopied" to copied
                )
            )

            return ClipboardStepResult.ParseError(
                humanMessage = "Could not parse LLM response (${details.reasonCode()}). " +
                        if (copied) "Diagnostic JSON copied to clipboard — paste it into the LLM and paste the corrected response back here."
                        else "Diagnostic JSON is ready but could not be copied automatically. See details below.",
                errorDetail = detail,
                errorFeedbackJson = errorJson,
                clipboardCopySucceeded = copied
            )
        }

        val transitioned = sessionManager.transition(sessionId, ClipboardEvent.ResponsePasted)
        if (!transitioned) {
            return error("Cannot accept response paste: session is not in AWAITING_PASTE state.")
        }

        val response = validationResult.response
        val outputTokens = rawText.length / 4
        val inputTokens = state.lastInputTokens

        val reasoningDisplay = response.reasoning?.take(40) ?: "none"
        log(
            "Parsed: message=${response.message.take(50)}, " +
                    "requestedFiles=${response.requestedFiles.size}, " +
                    "modifications=${response.modifications.size}, " +
                    "commands=${response.commands.size}, " +
                    "reasoning=$reasoningDisplay"
        )

        if (response.message.isNotBlank()) {
            addToHistory(ChatRole.ASSISTANT, response.message)
        }

        if (response.requestedFiles.isNotEmpty()) {
            persistRequestedFilesIntoDomain(sessionId, response.requestedFiles)
        }

        if (response.codeViewRequests.isNotEmpty()) {
            persistRequestedViewsIntoDomain(sessionId, response.codeViewRequests)
        }

        return processUnifiedResponse(sessionId, response, inputTokens, outputTokens)
    }

    /**
     * Sends the outcomes of the current turn's shell commands (execution results or user
     * declines) back to the LLM as an automatic round-trip — the command analogue of the
     * requestedFiles continuation. Called by the UI layer once ALL command blocks of the
     * turn are resolved (Run or Decline). No new user message is added.
     *
     * Session flow: SESSION_ACTIVE → (JsonCopied) → AWAITING_PASTE, exactly like any other
     * generated request; the user pastes the JSON into the LLM and pastes the reply back.
     */
    suspend fun submitCommandResults(sessionId: String, resultsForLlm: String): ClipboardStepResult {
        log("Submitting command results (${resultsForLlm.length} chars)")
        if (currentStatus(sessionId) != ClipboardSessionStatus.SESSION_ACTIVE) {
            return error("Command results can only be submitted in SESSION_ACTIVE state.")
        }
        if (sessionState == null || sessionStateOwner != sessionId) {
            log("sessionState missing or owned by another session in submitCommandResults — restoring for $sessionId")
            if (!ensureWorkspace(sessionId)) {
                return error("Cannot restore session state for session $sessionId. Please start a new task.")
            }
        }
        return generateAndCopyJson(
            sessionId = sessionId,
            freshFiles = emptyMap(),
            isFirstMessage = false,
            commandResults = resultsForLlm
        )
    }

    /**
     * Saves [requestedFiles] from the LLM response into the last ASSISTANT message
     * of the domain session. No-op if session not found or no ASSISTANT message exists.
     */
    private fun persistRequestedFilesIntoDomain(sessionId: String, requestedFiles: List<String>) {
        val session = chatSessionRepository.getSessionById(sessionId) ?: return
        val messages = session.messages.toMutableList()
        val lastAssistantIdx = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastAssistantIdx < 0) return
        messages[lastAssistantIdx] = messages[lastAssistantIdx].copy(requestedFiles = requestedFiles)
        chatSessionRepository.saveSession(session.copy(messages = messages))
        log("Persisted ${requestedFiles.size} requestedFiles into domain message for session $sessionId")
    }

    /**
     * Saves [codeViewRequests] from the LLM response into the last ASSISTANT message
     * of the domain session as typed [RequestedViewInfo] entries.
     * No-op if session not found or no ASSISTANT message exists.
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
     * Returns the current [ClipboardSessionStatus] for the given session.
     */
    fun status(sessionId: String): ClipboardSessionStatus = currentStatus(sessionId)

    /**
     * Resets the clipboard session: clears in-memory workspace and transitions the session to IDLE.
     */
    fun reset(sessionId: String) {
        log("Session reset (sessionId=$sessionId)")
        sessionState = null
        sessionStateOwner = null
        sessionManager.transition(sessionId, ClipboardEvent.Reset)
    }

    /**
     * Forces the session from AWAITING_PASTE to SESSION_ACTIVE without requiring a paste.
     *
     * Used when the user explicitly decides to skip the pending LLM response and continue
     * the dialog with a new message. In-memory [sessionState] is preserved — the next
     * [handleUserInput] call will route to [continueDialog] as normal.
     *
     * @param sessionId The ID of the session to force-activate.
     * @return true if the transition was applied; false if the session was not in AWAITING_PASTE.
     */
    fun forceActivate(sessionId: String): Boolean {
        log("Force-activating session (sessionId=$sessionId)")
        return sessionManager.transition(sessionId, ClipboardEvent.ForceActivate)
    }

    /**
     * Forces the session from SESSION_ACTIVE back to AWAITING_PASTE.
     *
     * Used when the user wants to paste a previously generated LLM response
     * that was skipped via [forceActivate]. In-memory [sessionState] is preserved.
     *
     * @param sessionId The ID of the session to force into awaiting-paste state.
     * @return true if the transition was applied; false if the session was not in SESSION_ACTIVE.
     */
    fun forceAwaitPaste(sessionId: String): Boolean {
        log("Force-awaiting paste for session (sessionId=$sessionId)")
        return sessionManager.transition(sessionId, ClipboardEvent.ForceAwaitPaste)
    }

    // ==================== Core Logic ====================

    private suspend fun processUnifiedResponse(
        sessionId: String,
        response: InteractionResponse,
        inputTokens: Int = 0,
        outputTokens: Int = 0
    ): ClipboardStepResult {
        val state = sessionState ?: return error("No active session")

        val hasFiles = response.codeViewRequests.isNotEmpty()
        val hasMods = response.modifications.isNotEmpty()
        val hasMessage = response.message.isNotBlank()
        val commands = response.commands.mapNotNull { convertCommand(it) }

        log("Processing: hasFiles=$hasFiles, hasMods=$hasMods, hasMessage=$hasMessage, commands=${commands.size}")

        // Plan snapshot: full-replacement semantics, orthogonal to the state machine.
        // Absent field = plan unchanged; present with empty steps = explicit clear.
        response.plan?.let { snapshot ->
            chatSessionRepository.getSessionById(sessionId)?.let { current ->
                val newPlan = snapshot.takeIf { it.steps.isNotEmpty() }
                chatSessionRepository.saveSession(current.withPlan(newPlan))
                log(if (newPlan != null) "Plan updated: ${newPlan.steps.size} step(s)" else "Plan cleared")
            }
        }

        val modResults = if (hasMods) applyModifications(response.modifications)
        else emptyList<ModificationResult>()

        if (hasFiles) {
            // Protocol rule: commands must not be mixed with file requests.
            // Files win — commands are skipped, and the LLM is told so via commandResults.
            if (commands.isNotEmpty()) {
                log("WARN: response mixed file requests with ${commands.size} command(s) — commands skipped per protocol")
            }

            val skillRequests = response.codeViewRequests
                .filter { it.granularity == com.maxvibes.domain.model.code.CodeGranularity.SKILL }
            val fullRequests = response.codeViewRequests
                .filter { it.granularity == com.maxvibes.domain.model.code.CodeGranularity.FULL }
                .map { it.filePath }
            val partialRequests = response.codeViewRequests
                .filter {
                    it.granularity != com.maxvibes.domain.model.code.CodeGranularity.FULL &&
                            it.granularity != com.maxvibes.domain.model.code.CodeGranularity.SKILL
                }

            val fullFilesMap: Map<String, String> = if (fullRequests.isNotEmpty()) {
                gatherRequestedFiles(fullRequests) ?: run {
                    return buildCompletedResult(
                        response = response,
                        modResults = modResults,
                        extraMessage = buildString {
                            append("Failed to gather some requested files.")
                            if (commands.isNotEmpty()) {
                                append(" ${commands.size} command(s) were skipped (mixed with file requests).")
                            }
                        },
                        inputTokens = inputTokens,
                        outputTokens = outputTokens
                    )
                }
            } else emptyMap()

            val partialFilesMap: Map<String, String> = partialRequests.associate { request ->
                try {
                    val view = codeRepository.getCodeView(request)
                    log("Rendered ${request.granularity} view for ${request.filePath} (${view.content.length} chars)")
                    request.filePath to view.content
                } catch (e: Exception) {
                    log("ERROR: Failed to render ${request.granularity} view for ${request.filePath}: ${e.message}")
                    request.filePath to "// ERROR: Could not render ${request.granularity} view: ${e.message}"
                }
            }

            val skillFilesMap: Map<String, String> = skillRequests.associate { req ->
                val body = specificPromptService?.resolveSkillBody(req.filePath)
                log(if (body != null) "Rendered skill '${req.filePath}' (${body.length} chars)" else "Unknown skill '${req.filePath}'")
                "skill:${req.filePath}" to (body
                    ?: "// ERROR: Unknown skill '${req.filePath}'. Use one of the names from the Skills section.")
            }

            val mergedFiles = fullFilesMap + partialFilesMap + skillFilesMap

            val assistantMsg = response.message.trim().takeIf { it.isNotBlank() }
            val reasoningStr = response.reasoning?.takeIf { it.isNotBlank() }

            val requestedViewInfos = response.codeViewRequests.map {
                RequestedViewInfo(
                    path = it.filePath,
                    granularity = it.granularity,
                    elementPath = it.elementPath
                )
            }

            return generateAndCopyJson(
                sessionId = sessionId,
                freshFiles = mergedFiles,
                isFirstMessage = false,
                assistantMessage = assistantMsg,
                llmReasoning = reasoningStr,
                requestedViews = requestedViewInfos,
                commandResults = if (commands.isEmpty()) null else
                    "SKIPPED: your ${commands.size} command(s) were not executed because the response " +
                            "also contained file requests. Do not mix commands with requestedViews/requestedFiles — " +
                            "re-issue the commands after receiving the files.",
                skippedCommands = commands.size
            )
        }

        return buildCompletedResult(
            response = response,
            modResults = modResults,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            commands = commands
        )
    }

    /**
     * @param commandResults formatted outcomes of the previous turn's shell commands
     *        (execution output or user declines) — forwarded to the LLM in the request.
     * @param skippedCommands number of commands dropped because the response mixed them
     *        with file requests; surfaced to the UI via [ClipboardStepResult.WaitingForResponse].
     */
    private fun generateAndCopyJson(
        sessionId: String,
        freshFiles: Map<String, String>,
        isFirstMessage: Boolean,
        assistantMessage: String? = null,
        llmReasoning: String? = null,
        addHistory: Boolean = false,
        ideErrors: String? = null,
        attachedContext: String? = null,
        requestedViews: List<RequestedViewInfo> = emptyList(),
        specificPromptContent: String? = null,
        commandResults: String? = null,
        skippedCommands: Int = 0
    ): ClipboardStepResult {
        val state = sessionState ?: return error("No active session")

        val request = InteractionRequestBuilder.build(
            state = state,
            freshFiles = freshFiles,
            isFirstMessage = isFirstMessage,
            addHistory = addHistory,
            planOnlySuffix = PLAN_ONLY_SUFFIX,
            ideErrors = ideErrors,
            attachedContext = attachedContext,
            specificPromptContent = specificPromptContent,
            commandResults = commandResults,
            // Live plan state (planner panel), including manual user toggles.
            currentPlan = chatSessionRepository.getSessionById(sessionId)?.plan
        )

        val copied = clipboardPort.copyRequestToClipboard(request)
        val copyStatus = if (copied) "copied to clipboard" else "generated (copy manually)"

        val totalTokens = estimateTokens(request)
        state.lastInputTokens = totalTokens

        log("JSON ready: $copyStatus, ~$totalTokens tokens")

        sessionManager.transition(sessionId, ClipboardEvent.JsonCopied)

        return ClipboardStepResult.WaitingForResponse(
            phase = request.phase,
            statusMessage = "JSON $copyStatus. Paste into Claude/ChatGPT, then paste the response back here.",
            assistantMessage = assistantMessage,
            jsonRequest = request,
            estimatedInputTokens = totalTokens,
            llmReasoning = llmReasoning,
            freshFileNames = freshFiles.keys.map { it.substringAfterLast('/') },
            previouslyGatheredCount = request.previouslyGatheredPaths.size,
            requestedViews = requestedViews,
            skippedCommands = skippedCommands
        )
    }

    /**
     * Restores the in-memory [sessionState] from persisted domain data.
     *
     * Used to recover after IDE restart when [sessionState] is null but
     * [ClipboardSessionStatus] in XML is SESSION_ACTIVE or AWAITING_PASTE.
     *
     * Reads the last user message and project context, rebuilds a minimal
     * [ClipboardSessionState], and sets [sessionStateOwner].
     *
     * @return true if workspace was successfully restored; false if domain data
     *         is insufficient (e.g. session has no user messages yet).
     */
    private suspend fun ensureWorkspace(sessionId: String): Boolean {
        val session = chatSessionRepository.getSessionById(sessionId)
            ?: return false

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
        val prompts = promptPort?.getPrompts() ?: PromptTemplates.EMPTY

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

    suspend fun redoLastRequest(
        sessionId: String,
        globalContextFiles: List<String>
    ): ClipboardStepResult {

        // --- Scenario A: workspace already belongs to this session ---
        if (sessionStateOwner == sessionId && sessionState != null) {
            log("Redo scenario A: reusing existing workspace for session $sessionId")
            val freshFiles = gatherRequestedFiles(globalContextFiles) ?: emptyMap()
            return generateAndCopyJson(
                sessionId = sessionId,
                freshFiles = freshFiles,
                isFirstMessage = false
            )
        }

        // --- Scenario B: workspace belongs to another session or was lost — rebuild from domain ---
        log("Redo scenario B: rebuilding workspace from domain for session $sessionId")

        val session = chatSessionRepository.getSessionById(sessionId)
            ?: return error("Session not found: $sessionId")

        if (session.clipboardStatus == ClipboardSessionStatus.IDLE) {
            return error("No active clipboard session for this chat.")
        }

        if (!ensureWorkspace(sessionId)) {
            return error("Cannot restore session state for session $sessionId. No user message found.")
        }

        val lastRequestedFiles = session.messages
            .lastOrNull { it.role == MessageRole.ASSISTANT && it.requestedFiles.isNotEmpty() }
            ?.requestedFiles
            ?: emptyList()

        val filesToGather = (globalContextFiles + lastRequestedFiles).distinct()
        val freshFiles = gatherRequestedFiles(filesToGather) ?: emptyMap()
        return generateAndCopyJson(
            sessionId = sessionId,
            freshFiles = freshFiles,
            isFirstMessage = false
        )
    }

    // ==================== File Gathering ====================

    private suspend fun gatherRequestedFiles(requestedPaths: List<String>): Map<String, String>? {
        val state = sessionState ?: return null
        if (requestedPaths.isEmpty()) return emptyMap()

        // Always re-read every requested path from the live project. The former
        // per-session content cache served stale files after modifications and
        // silently dropped re-requested paths when mixed with new ones.
        // allGatheredFiles is kept as bookkeeping for previouslyGatheredPaths only.
        log("Gathering ${requestedPaths.size} files (fresh read)...")
        notificationPort.showProgress("Gathering ${requestedPaths.size} files...", 0.4)

        val gatherResult = contextProvider.gatherFiles(requestedPaths)
        if (gatherResult is Result.Failure) {
            log("ERROR: Failed to gather files: ${gatherResult.error.message}")
            return null
        }
        val gathered = (gatherResult as Result.Success).value
        state.allGatheredFiles.putAll(gathered.files)
        log("Gathered ${gathered.files.size} files, total tracked: ${state.allGatheredFiles.size}")
        return gathered.files
    }

    // ==================== Modifications ====================

    private suspend fun applyModifications(clipboardMods: List<InteractionModification>): List<ModificationResult> {
        val modifications = clipboardMods.mapNotNull { convertModification(it) }
        if (modifications.isEmpty()) return emptyList()

        log("Applying ${modifications.size} modifications...")
        notificationPort.showProgress("Applying ${modifications.size} changes...", 0.8)

        val results = codeRepository.applyModifications(modifications)
        val successCount = results.count { it is ModificationResult.Success }
        val failCount = results.size - successCount

        log("Modifications: $successCount success, $failCount failed")
        if (failCount > 0) notificationPort.showWarning("Applied $successCount changes, $failCount failed")
        else if (successCount > 0) notificationPort.showSuccess("Applied $successCount changes")
        return results
    }

    // ==================== Result Building ====================

    private fun buildCompletedResult(
        response: InteractionResponse,
        modResults: List<ModificationResult>,
        extraMessage: String = "",
        inputTokens: Int = 0,
        outputTokens: Int = 0,
        commands: List<CommandRequest> = emptyList()
    ): ClipboardStepResult {
        val successCount = modResults.count { it is ModificationResult.Success }
        val failCount = modResults.size - successCount

        val messageText = buildString {
            if (response.message.isNotBlank()) append(response.message)
            if (extraMessage.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(extraMessage)
            }
            if (isEmpty()) append("Done.")
        }

        if (modResults.isNotEmpty()) notificationPort.showSuccess("Done. Session active — you can continue the dialog.")
        log("Completed: mods=$successCount ok/$failCount fail, commands=${commands.size}.")

        return ClipboardStepResult.Completed(
            message = messageText.trim(),
            modifications = modResults,
            success = failCount == 0,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            llmReasoning = response.reasoning?.takeIf { it.isNotBlank() },
            commitMessage = response.commitMessage?.takeIf { it.isNotBlank() },
            commands = commands
        )
    }

    // ==================== Helpers ====================

    private fun addToHistory(role: ChatRole, content: String) {
        val state = sessionState ?: return
        state.dialogHistory.add(ChatMessageDTO(role = role, content = content))
    }

    private fun estimateTokens(request: ClipboardRequest): Int {
        val textSize = request.systemInstruction.length +
                request.fileTree.length +
                request.freshFiles.values.sumOf { it.length } +
                request.chatHistory.sumOf { it.content.length } +
                (request.attachedContext?.length ?: 0) +
                (request.commandResults?.length ?: 0)
        return textSize / 4
    }

    private fun log(message: String) {
        println("[MaxVibes Clipboard] $message")
        logger?.info("Clipboard", message)
    }

    private fun error(message: String): ClipboardStepResult.Error {
        println("[MaxVibes Clipboard] ERROR: $message")
        logger?.error("Clipboard", message)
        return ClipboardStepResult.Error(message)
    }

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
     * Skips entries with a blank command line.
     */
    private fun convertCommand(cmd: InteractionCommand): CommandRequest? {
        if (cmd.command.isBlank()) return null
        return CommandRequest(
            command = cmd.command,
            reason = cmd.reason.takeIf { it.isNotBlank() },
            timeoutSec = cmd.timeoutSec.coerceIn(1, 3600)
        )
    }

    companion object {
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

// ==================== Results ====================

sealed class ClipboardStepResult {
    data class WaitingForResponse(
        val phase: InteractionPhase,
        val statusMessage: String,
        val assistantMessage: String? = null,
        val jsonRequest: ClipboardRequest,
        val estimatedInputTokens: Int = 0,
        val llmReasoning: String? = null,
        val freshFileNames: List<String> = emptyList(),
        val previouslyGatheredCount: Int = 0,
        val requestedViews: List<com.maxvibes.domain.model.code.RequestedViewInfo> = emptyList(),
        /** Number of shell commands dropped because the response mixed them with file requests. */
        val skippedCommands: Int = 0
    ) : ClipboardStepResult()

    data class Completed(
        val message: String,
        val modifications: List<ModificationResult>,
        val success: Boolean,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val llmReasoning: String? = null,
        val commitMessage: String? = null,
        /** Shell commands requested by the LLM — the UI renders Run/Decline blocks for them. */
        val commands: List<CommandRequest> = emptyList()
    ) : ClipboardStepResult()

    /**
     * A general application-level error (session not found, project context failure, etc.).
     * Distinct from [ParseError] — these are infrastructure failures, not LLM response issues.
     */
    data class Error(val message: String) : ClipboardStepResult()

    /**
     * The pasted LLM response could not be parsed.
     *
     * The session remains in AWAITING_PASTE — the user should paste the response from
     * the LLM after it corrects its output using the [errorFeedbackJson].
     *
     * @param humanMessage           Short description shown in the chat panel.
     * @param errorDetail            Technical detail for display below the message.
     * @param errorFeedbackJson      JSON string already copied to clipboard for the LLM.
     * @param clipboardCopySucceeded Whether the clipboard write succeeded.
     */
    data class ParseError(
        val humanMessage: String,
        val errorDetail: String,
        val errorFeedbackJson: String,
        val clipboardCopySucceeded: Boolean
    ) : ClipboardStepResult()
}

// ==================== Internal State ====================