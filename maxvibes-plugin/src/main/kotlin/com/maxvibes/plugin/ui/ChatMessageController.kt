package com.maxvibes.plugin.ui

import com.intellij.openapi.project.Project
import com.maxvibes.application.port.input.ContextAwareRequest
import com.maxvibes.application.service.ClaudeCodeStepResult
import com.maxvibes.application.service.ClipboardStepResult
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.plugin.service.MaxVibesLogger
import com.maxvibes.plugin.service.MaxVibesService
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.domain.model.interaction.AttachedImage
import com.intellij.openapi.progress.ProcessCanceledException

/**
 * Aggregate UI port of the chat panel. Prefer depending on a narrow facet
 * (see ChatPanelViews.kt) — this aggregate exists for ChatPanel and test fakes
 * that implement the full surface.
 */
interface ChatPanelCallbacks :
    ConversationView,
    InputStatusView,
    AttachmentView,
    SessionView,
    QuestionView,
    CommandView

/**
 * Handles message sending (API, Clipboard, CheapAPI, ClaudeCode) and response processing.
 *
 * Extracted from ChatPanel to separate message flow logic from UI setup.
 * Uses [ChatPanelCallbacks] to communicate UI updates back to the panel.
 *
 * Auto-retry: when LLM response has ModificationResult.Failure entries,
 * the controller automatically sends a follow-up message to LLM with error details.
 * Limited to [MAX_AUTO_RETRIES] per user message.
 */
class ChatMessageController(
    private val project: Project,
    private val service: MaxVibesService,
    private val callbacks: ChatPanelCallbacks
) {

    private val chatTreeService get() = service.chatTreeService

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

    companion object {
        fun buildTaskWithContext(task: String, trace: String?, errs: String?): String {
            return buildString {
                append(task)
                if (!trace.isNullOrBlank()) append("\n\n--- Error/Trace/Logs ---\n$trace")
                if (!errs.isNullOrBlank()) append("\n\n--- IDE Errors ---\n$errs")
            }
        }
    }

    // ==================== API Mode ====================

    // ==================== Cheap API Mode ====================

    // ==================== Clipboard Mode ====================

    fun runClipboardBg(
        title: String,
        session: ChatSession,
        action: suspend () -> ClipboardStepResult
    ) {
        backgroundTaskRunner.run(
            title = "MaxVibes: $title",
            cancellable = true,
            action = {
                try {
                    action()
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Throwable) {
                    MaxVibesLogger.error(
                        "Controller",
                        "clipboard background action crashed",
                        e as? Exception ?: RuntimeException(e)
                    )
                    ClipboardStepResult.Error(
                        message = "Internal error: ${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                    )
                }
            },
            onSuccess = { result ->
                clipboardDispatcher.handleResult(result, session)
            },
            onCancel = {
                service.clipboardService.reset(session.id)
                callbacks.appendToChat("⚠️ Cancelled")
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
            }
        )
    }

    /**
     * Re-generates and copies the clipboard JSON for the current active session.
     */
    fun redoClipboardJson() = turnSubmissionCoordinator.redoClipboardJson()

    // ==================== Claude Code Mode ====================

    private fun runClaudeCodeBg(
        title: String,
        session: ChatSession,
        action: suspend () -> ClaudeCodeStepResult
    ) {
        callbacks.setStatus("Claude Code: running")
        backgroundTaskRunner.run(
            title = "MaxVibes: $title",
            cancellable = true,
            action = {
                try {
                    action()
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Throwable) {
                    MaxVibesLogger.error(
                        "Controller",
                        "claudeCode background action crashed",
                        e as? Exception ?: RuntimeException(e)
                    )
                    ClaudeCodeStepResult.Error(
                        message = "Internal error: ${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                    )
                }
            },
            onSuccess = { result ->
                claudeCodeDispatcher.handleResult(result, session)
            },
            onCancel = {
                service.claudeCodeService.reset(session.id)
                callbacks.appendToChat("⚠️ Cancelled")
                callbacks.setInputEnabled(true)
                callbacks.updateModeIndicator()
            }
        )
    }

    private fun runApiBg(
        progressTitle: String,
        session: ChatSession,
        isPlanOnly: Boolean,
        useCheap: Boolean,
        request: ContextAwareRequest
    ) {
        backgroundTaskRunner.run(
            title = "MaxVibes: $progressTitle...",
            cancellable = true,
            action = {
                val useCase = if (useCheap) {
                    service.cheapContextAwareModifyUseCase ?: service.contextAwareModifyUseCase
                } else {
                    service.contextAwareModifyUseCase
                }
                useCase.execute(request)
            },
            onSuccess = { result ->
                apiDispatcher.handleResult(result, session, isPlanOnly)
            },
            onCancel = {
                chatTreeService.addMessage(session.id, MessageRole.SYSTEM, "Cancelled")
                callbacks.appendToChat("⚠️ Cancelled")
                callbacks.setInputEnabled(true)
                callbacks.setStatus("Cancelled")
                MaxVibesLogger.warn("Controller", "cancelled by user")
            }
        )
    }

    fun approve() = turnSubmissionCoordinator.approve()

    // ==================== Question Flow ====================

    private val questionCoordinator = QuestionTurnCoordinator(
        questionView = callbacks,
        callbacks = callbacks
    )

    // ==================== Command Flow ====================

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
                backgroundTaskRunner.run(
                    title = "MaxVibes: Running command...",
                    cancellable = false,
                    publishIndicator = false,
                    action = { service.executeCommandUseCase.execute(request) },
                    onSuccess = onDone
                )
            },
            onBatchComplete = { sessionId, mode, formatted ->
                commandResultRouter.route(sessionId, mode, formatted)
            }
        )
    }
    private val claudeCodeDispatcher by lazy {
        ClaudeCodeDispatcher(
            claudeCodeService = { service.claudeCodeService },
            resolveSpecificPrompt = { name -> service.specificPromptService.resolvePromptContent(name) },
            chatTreeService = chatTreeService,
            callbacks = callbacks,
            presentQuestions = questionCoordinator::presentQuestions,
            presentCommands = { commands, sessionId, mode ->
                commandCoordinator.presentCommands(commands, sessionId, mode)
            },
            executeAsync = { title, session, action -> runClaudeCodeBg(title, session, action) }
        )
    }
    private val clipboardDispatcher by lazy {
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
            executeAsync = { progressTitle, session, isPlanOnly, useCheap, request ->
                runApiBg(progressTitle, session, isPlanOnly, useCheap, request)
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
            submitApi = { session, formatted ->
                apiDispatcher.submitCommandResults(session, formatted)
            },
            onMissingSession = {
                callbacks.setInputEnabled(true)
            }
        )
    }
    private val turnSubmissionCoordinator: TurnSubmissionCoordinator by lazy {
        TurnSubmissionCoordinator(
            documentSaver = documentSaver,
            dismissQuestionTurn = { questionCoordinator.dismissQuestionTurn() },
            attachments = attachmentCoordinator,
            appendToChat = callbacks::appendToChat,
            dispatchApi = { message, trace, errors, planOnly, dryRun ->
                apiDispatcher.dispatchMessage(message, trace, errors, planOnly, dryRun)
            },
            dispatchClipboard = { message, trace, errors, planOnly, addHistory, promptName ->
                clipboardDispatcher.dispatchMessage(
                    message,
                    trace,
                    errors,
                    planOnly,
                    addHistory,
                    promptName
                )
            },
            dispatchCheapApi = { message, trace, errors, planOnly, dryRun ->
                apiDispatcher.dispatchCheapMessage(message, trace, errors, planOnly, dryRun)
            },
            dispatchClaudeCode = { message, trace, errors, planOnly, promptName, images ->
                claudeCodeDispatcher.dispatchMessage(
                    message,
                    trace,
                    errors,
                    planOnly,
                    promptName,
                    images
                )
            },
            approveClaudeCode = { trace, errors ->
                claudeCodeDispatcher.approve(trace, errors)
            },
            redoClipboardJson = {
                clipboardDispatcher.redoLastRequest()
            }
        )
    }

    // ==================== Auto-Retry Logic ====================

    // ==================== Result Handlers ====================

    // ==================== Helpers ====================

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

    fun renameSession(sessionId: String, newTitle: String) =
        sessionActions.renameSession(sessionId, newTitle)

    fun branchSession(parentSessionId: String, title: String) =
        sessionActions.branchSession(parentSessionId, title)

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
}
