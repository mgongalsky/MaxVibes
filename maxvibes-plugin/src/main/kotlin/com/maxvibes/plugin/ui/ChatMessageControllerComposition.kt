package com.maxvibes.plugin.ui

import com.intellij.openapi.project.Project
import com.maxvibes.application.port.input.ContextAwareRequest
import com.maxvibes.application.service.ChatTreeService
import com.maxvibes.application.service.ClaudeCodeStepResult
import com.maxvibes.application.service.ClipboardStepResult
import com.maxvibes.application.service.approval.ApprovalService
import com.maxvibes.application.service.turn.AgentTurnOrchestrator
import com.maxvibes.application.service.turn.TurnAutopilot
import com.maxvibes.domain.model.approval.AgentActionKind
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.plugin.service.MaxVibesService
import com.maxvibes.plugin.settings.ApprovalPolicySettings
import com.maxvibes.domain.model.turn.AutonomyBudget

/** Composition root behind [ChatMessageController]. */
internal class ChatMessageControllerComposition(
    private val project: Project,
    private val service: MaxVibesService,
    private val callbacks: ChatPanelCallbacks
) {
    private val chatTreeService: ChatTreeService
        get() = service.chatTreeService

    val attachedTrace: String?
        get() = attachmentCoordinator.trace

    val attachedErrors: String?
        get() = attachmentCoordinator.errors

    private val attachmentCoordinator = AttachmentCoordinator(
        context = PendingTurnContext(ImageAttachments.MAX_IMAGES),
        attachmentView = callbacks,
        inputStatusView = callbacks,
        maxImages = ImageAttachments.MAX_IMAGES
    )

    private val backgroundTaskRunner: BackgroundTaskRunner by lazy {
        IntellijBackgroundTaskRunner(project) { indicator ->
            service.notificationService.setProgressIndicator(indicator)
        }
    }

    private val interactionExecutionCoordinator: InteractionExecutionCoordinator by lazy {
        InteractionExecutionCoordinator(
            backgroundTaskRunner = backgroundTaskRunner,
            inputStatusView = callbacks,
            appendToChat = callbacks::appendToChat,
            resetClipboardSession = { sessionId -> service.clipboardService.reset(sessionId) },
            resetClaudeCodeSession = { sessionId -> service.claudeCodeService.reset(sessionId) },
            addSystemMessage = { sessionId, text ->
                chatTreeService.addMessage(sessionId, MessageRole.SYSTEM, text)
            },
            executeApiRequest = { useCheap, request ->
                val useCase = if (useCheap) {
                    service.cheapContextAwareModifyUseCase ?: service.contextAwareModifyUseCase
                } else {
                    service.contextAwareModifyUseCase
                }
                useCase.execute(request)
            }
        )
    }

    private val documentSaver: DocumentSaver = IntellijDocumentSaver()

    private val ideErrorsAttachmentLoader: IdeErrorsAttachmentLoader by lazy {
        IdeErrorsAttachmentLoader(
            ideErrorsPort = service.ideErrorsPort,
            backgroundTaskRunner = backgroundTaskRunner,
            attachments = attachmentCoordinator,
            inputStatusView = callbacks
        )
    }

    private val sessionActions: SessionActions by lazy {
        SessionActions(
            chatTreeService = chatTreeService,
            onSessionChanged = callbacks::onSessionChanged,
            onSessionRenamed = callbacks::onSessionRenamed
        )
    }

    private val questionCoordinator = QuestionTurnCoordinator(
        questionView = callbacks,
        callbacks = callbacks
    )

    private val approvalService: ApprovalService by lazy {
        ApprovalService { ApprovalPolicySettings.getInstance(project).load() }
    }

    private val turnAutopilot: TurnAutopilot by lazy {
        TurnAutopilot(
            orchestrator = AgentTurnOrchestrator(decideApproval = approvalService::decide),
            continueTurn = { sessionId, action ->
                when (action) {
                    AgentActionKind.COMMAND -> commandCoordinator.runAllAutomatically(sessionId)
                    AgentActionKind.BUILD,
                    AgentActionKind.TESTS -> checkCoordinator.runAllAutomatically(sessionId)

                    AgentActionKind.CONTINUATION -> claudeCodeDispatcher.continueWithoutHuman(sessionId)
                    AgentActionKind.VIEW_REQUEST,
                    AgentActionKind.MODIFICATION,
                    null -> claudeCodeDispatcher.continueTurnAutomatically(sessionId)
                }
            },
            budget = {
                AutonomyBudget(
                    ApprovalPolicySettings.getInstance(project).loadAutonomousIterations()
                )
            }
        )
    }

    private val commandCoordinator: CommandTurnCoordinator by lazy {
        CommandTurnCoordinator(
            executeCommandUseCase = service.executeCommandUseCase,
            commandView = callbacks,
            callbacks = callbacks,
            addSystemMessage = { sessionId, text ->
                chatTreeService.addMessage(sessionId, MessageRole.SYSTEM, text)
            },
            activeSessionId = { chatTreeService.getActiveSession().id },
            executeAsync = { request, onDone ->
                interactionExecutionCoordinator.runCommand(
                    request = request,
                    action = { service.executeCommandUseCase.execute(request) },
                    onResult = onDone
                )
            },
            onBatchComplete = { sessionId, mode, formatted ->
                commandResultRouter.route(sessionId, mode, formatted)
            }
        )
    }

    private val checkCoordinator: CheckTurnCoordinator by lazy {
        CheckTurnCoordinator(
            runCheckUseCase = service.runCheckUseCase,
            checkView = callbacks,
            callbacks = callbacks,
            addSystemMessage = { sessionId, text ->
                chatTreeService.addMessage(sessionId, MessageRole.SYSTEM, text)
            },
            activeSessionId = { chatTreeService.getActiveSession().id },
            executeAsync = { request, onDone ->
                interactionExecutionCoordinator.runCheck(
                    request = request,
                    action = { service.runCheckUseCase.run(request) },
                    onResult = onDone
                )
            },
            onBatchComplete = { sessionId, mode, formatted ->
                checkResultRouter.route(sessionId, mode, formatted)
            }
        )
    }

    private val claudeCodeDispatcher: ClaudeCodeDispatcher by lazy {
        ClaudeCodeDispatcher(
            claudeCodeService = { service.claudeCodeService },
            resolveSpecificPrompt = { name -> service.specificPromptService.resolvePromptContent(name) },
            chatTreeService = chatTreeService,
            callbacks = callbacks,
            presentQuestions = questionCoordinator::presentQuestions,
            presentCommands = { commands, sessionId, mode ->
                commandCoordinator.presentCommands(commands, sessionId, mode)
            },
            executeAsync = { title, session, action -> runClaudeCodeBg(title, session, action) },
            turnAutopilot = { turnAutopilot },
            maxFormatRetries = { ApprovalPolicySettings.getInstance(project).loadMaxFormatRetries() }
        )
    }

    private val clipboardDispatcher: ClipboardDispatcher by lazy {
        ClipboardDispatcher(
            clipboardService = { service.clipboardService },
            resolveSpecificPrompt = { name -> service.specificPromptService.resolvePromptContent(name) },
            chatTreeService = chatTreeService,
            callbacks = callbacks,
            presentCommands = { commands, sessionId, mode ->
                commandCoordinator.presentCommands(commands, sessionId, mode)
            },
            executeAsync = { title, session, action -> runClipboardBg(title, session, action) }
        )
    }

    private val apiDispatcher: ApiDispatcher by lazy {
        ApiDispatcher(
            chatTreeService = chatTreeService,
            callbacks = callbacks,
            ensureCheapService = {
                @Suppress("DEPRECATION")
                service.ensureCheapLLMService()
            },
            presentCommands = { commands, sessionId, mode ->
                commandCoordinator.presentCommands(commands, sessionId, mode)
            },
            executeAsync = { title, session, planOnly, cheap, request ->
                runApiBg(title, session, planOnly, cheap, request)
            }
        )
    }

    private val commandResultRouter: CommandResultRouter by lazy {
        CommandResultRouter(
            chatTreeService = chatTreeService,
            submitClipboard = { session, formatted ->
                runClipboardBg("Sending command results...", session) {
                    service.clipboardService.submitCommandResults(session.id, formatted)
                }
            },
            submitClaudeCode = { session, formatted ->
                runClaudeCodeBg("Sending command results...", session) {
                    service.claudeCodeService.submitCommandResults(session.id, formatted)
                }
            },
            submitApi = { session, formatted -> apiDispatcher.submitCommandResults(session, formatted) },
            onMissingSession = { callbacks.setInputEnabled(true) }
        )
    }

    private val checkResultRouter: CommandResultRouter by lazy {
        CommandResultRouter(
            chatTreeService = chatTreeService,
            submitClipboard = { session, formatted ->
                runClipboardBg("Sending check results...", session) {
                    service.clipboardService.submitCommandResults(session.id, formatted)
                }
            },
            submitClaudeCode = { session, formatted ->
                runClaudeCodeBg("Sending check results...", session) {
                    service.claudeCodeService.submitCheckResults(session.id, formatted)
                }
            },
            submitApi = { session, formatted -> apiDispatcher.submitCommandResults(session, formatted) },
            onMissingSession = { callbacks.setInputEnabled(true) }
        )
    }

    private val turnSubmissionCoordinator: TurnSubmissionCoordinator by lazy {
        TurnSubmissionCoordinator(
            documentSaver = documentSaver,
            dismissQuestionTurn = questionCoordinator::dismissQuestionTurn,
            attachments = attachmentCoordinator,
            appendToChat = callbacks::appendToChat,
            dispatchApi = { message, trace, errors, planOnly, dryRun ->
                apiDispatcher.dispatchMessage(message, trace, errors, planOnly, dryRun)
            },
            dispatchClipboard = { message, trace, errors, planOnly, addHistory, promptName ->
                clipboardDispatcher.dispatchMessage(message, trace, errors, planOnly, addHistory, promptName)
            },
            dispatchCheapApi = { message, trace, errors, planOnly, dryRun ->
                apiDispatcher.dispatchCheapMessage(message, trace, errors, planOnly, dryRun)
            },
            dispatchClaudeCode = { message, trace, errors, planOnly, promptName, images ->
                claudeCodeDispatcher.dispatchMessage(message, trace, errors, planOnly, promptName, images)
            },
            approveClaudeCode = { trace, errors -> claudeCodeDispatcher.approve(trace, errors) },
            redoClipboardJson = clipboardDispatcher::redoLastRequest
        )
    }

    fun runClipboardBg(
        title: String,
        session: ChatSession,
        action: suspend () -> ClipboardStepResult
    ): Unit = interactionExecutionCoordinator.runClipboard(
        title = title,
        session = session,
        action = action,
        onResult = { result -> clipboardDispatcher.handleResult(result, session) }
    )

    private fun runClaudeCodeBg(
        title: String,
        session: ChatSession,
        action: suspend () -> ClaudeCodeStepResult
    ): Unit = interactionExecutionCoordinator.runClaudeCode(
        title = title,
        session = session,
        action = action,
        onResult = { result ->
            val completed = result as? ClaudeCodeStepResult.Completed
            val checks = completed?.checks.orEmpty()
            val blockedByCommands = checks.isNotEmpty() && completed?.commands?.isNotEmpty() == true
            // Правки этого шага не дошли до кода, значит проверять нечего: собирать
            // и тестировать пришлось бы то, чего на диске нет.
            val brokenStep = completed?.malformedModifications?.isNotEmpty() == true ||
                    completed?.modifications?.any { !it.success } == true
            // Батч чеков должен существовать до handleResult: тот дёргает автопилот,
            // а автопилот запускает уже готовый батч без участия человека.
            if (checks.isNotEmpty() && !blockedByCommands && !brokenStep) {
                checkCoordinator.presentChecks(checks, session.id, InteractionMode.CLAUDE_CODE)
            }
            claudeCodeDispatcher.handleResult(result, session)
            if (blockedByCommands) {
                callbacks.appendToChat(
                    "\u26A0\uFE0F ${checks.size} check(s) skipped — response mixed them with terminal commands"
                )
            } else if (brokenStep && checks.isNotEmpty()) {
                callbacks.appendToChat(
                    "\u26A0\uFE0F ${checks.size} check(s) skipped — the modifications of this step never reached the code"
                )
            }
        }
    )

    private fun runApiBg(
        progressTitle: String,
        session: ChatSession,
        isPlanOnly: Boolean,
        useCheap: Boolean,
        request: ContextAwareRequest
    ): Unit = interactionExecutionCoordinator.runApi(
        progressTitle = progressTitle,
        session = session,
        useCheap = useCheap,
        request = request,
        onResult = { result -> apiDispatcher.handleResult(result, session, isPlanOnly) }
    )

    fun redoClipboardJson() = turnSubmissionCoordinator.redoClipboardJson()
    fun approve() = turnSubmissionCoordinator.approve()
    fun attachTrace(traceContent: String) = attachmentCoordinator.attachTrace(traceContent)
    fun clearTrace() = attachmentCoordinator.clearTrace()
    fun clearErrors() = attachmentCoordinator.clearErrors()
    fun attachImage(image: AttachedImage): Boolean = attachmentCoordinator.attachImage(image)
    fun clearImages() = attachmentCoordinator.clearImages()
    fun removeImage(index: Int) = attachmentCoordinator.removeImage(index)
    fun fetchIdeErrors() = ideErrorsAttachmentLoader.fetch()
    fun clearAttachmentsAfterSend() = attachmentCoordinator.clearAfterSend()
    fun createNewSession() = sessionActions.createNewSession()
    fun deleteCurrentSession(sessionId: String) = sessionActions.deleteCurrentSession(sessionId)
    fun renameSession(sessionId: String, newTitle: String) = sessionActions.renameSession(sessionId, newTitle)
    fun branchSession(parentSessionId: String, title: String) = sessionActions.branchSession(parentSessionId, title)
    fun loadSession(sessionId: String) = sessionActions.loadSession(sessionId)
    fun selectSpecificPrompt(name: String?) = sessionActions.selectSpecificPrompt(name)

    fun sendMessage(
        userInput: String,
        isPlanOnly: Boolean,
        isDryRun: Boolean,
        mode: InteractionMode,
        addHistory: Boolean = false,
        selectedSpecificPromptName: String? = null
    ) = turnSubmissionCoordinator.sendMessage(
        userInput = userInput,
        isPlanOnly = isPlanOnly,
        isDryRun = isDryRun,
        mode = mode,
        addHistory = addHistory,
        selectedSpecificPromptName = selectedSpecificPromptName
    )

    fun armOneShot(skillName: String?, elementContext: String?, label: String) =
        attachmentCoordinator.armOneShot(skillName, elementContext, label)

    fun clearOneShot() = attachmentCoordinator.clearOneShot()

    fun isAllowAllApprovals(): Boolean =
        approvalService.isAllowAll(chatTreeService.getActiveSession().id)

    fun setAllowAllApprovals(enabled: Boolean) {
        val sessionId = chatTreeService.getActiveSession().id
        approvalService.setAllowAll(sessionId, enabled)
        if (enabled) turnAutopilot.resumeParked(sessionId)
    }
}