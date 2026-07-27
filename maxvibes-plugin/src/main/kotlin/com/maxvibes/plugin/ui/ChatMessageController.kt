package com.maxvibes.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.maxvibes.application.port.input.ContextAwareRequest
import com.maxvibes.application.service.ClaudeCodeStepResult
import com.maxvibes.application.service.ClipboardStepResult
import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.plugin.service.MaxVibesLogger
import com.maxvibes.plugin.service.MaxVibesService
import kotlinx.coroutines.runBlocking
import com.maxvibes.shared.result.Result
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.modification.AppliedModInfo
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import com.maxvibes.domain.model.planning.PlanDiagram
import com.intellij.openapi.progress.ProcessCanceledException

interface ChatPanelCallbacks {
    fun appendToChat(text: String)
    fun appendAssistantMessage(text: String)
    fun setInputEnabled(enabled: Boolean)
    fun setStatus(text: String)
    fun updateModeIndicator()
    fun updateBreadcrumb()
    fun registerElementPaths(modifications: List<ModificationResult>)
    fun formatMarkdown(text: String): String
    fun updateTokenDisplay()

    /** Adds a user message bubble, optionally with attached image thumbnails. */
    fun addUserMessageBubble(text: String, images: List<AttachedImage> = emptyList())
    fun addAssistantMessageBubble(
        text: String,
        tokenInfo: String?,
        modifications: List<ModificationResult>,
        metaFiles: List<String> = emptyList(),
        reasoning: String? = null,
        requestedViews: List<com.maxvibes.domain.model.code.RequestedViewInfo> = emptyList(),
        appliedModifications: List<AppliedModInfo> = emptyList()
    )

    /** Renders a post-apply errors block with Send to model / Dismiss buttons. */
    fun addPostApplyErrorsBubble(
        summary: String,
        details: String,
        onSend: () -> Unit,
        onDismiss: () -> Unit
    ): PostApplyErrorsView

    /** Renders an interactive question block; returns a handle for freeze/status updates. */
    fun addQuestionBubble(
        question: String,
        options: List<String>,
        onAnswer: (String) -> Unit
    ): QuestionBlockView

    /** Submits text through the exact same path as the main input's Send button. */
    fun sendUserMessage(text: String)

    fun appendIconToLastBubble(icon: String)
    fun clearChatDisplay()
    fun setPlanOnlyMode(enabled: Boolean)

    /** Sets the commit message in the IDE VCS commit dialog. */
    fun setCommitMessage(message: String)

    /** Called when attached trace or errors change. */
    fun onAttachmentsChanged(trace: String?, errors: String?)

    /** Called when a background operation encounters an error. */
    fun onError(message: String)

    /** Called when the active session changes (create, delete, branch, load). */
    fun onSessionChanged(session: ChatSession?)

    /** Called when a session is renamed. */
    fun onSessionRenamed(session: ChatSession)

    /** Called to show the welcome screen (e.g. no sessions). */
    fun onShowWelcome()

    /** Renders an interactive shell-command block; returns a view handle for status updates. */
    fun addCommandBubble(
        command: String,
        reason: String?,
        warnings: List<String>,
        onRun: () -> Unit,
        onDecline: (String?) -> Unit
    ): CommandBlockView

    /** Renders a Run all / Decline all bar above a multi-command batch. */
    fun addCommandBatchBar(count: Int, onRunAll: () -> Unit, onDeclineAll: () -> Unit): CommandBatchBarView

    /** Rebuilds the attached-images preview strip (empty list hides it). */
    fun onImagesChanged(images: List<AttachedImage>)

    /** Shows/hides the one-shot editor-skill chip; null label hides it. */
    fun onOneShotChanged(label: String?)

    /** Adds a "Схема" button under the last assistant bubble; opens the plan diagram viewer. */
    fun showDiagramButton(diagram: PlanDiagram)
}

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

    var attachedTrace: String? = null
        private set
    var attachedErrors: String? = null
        private set

    /** Attached images (Claude Code mode only) — one-shot, cleared after send. */
    private val attachedImages = mutableListOf<AttachedImage>()

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
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MaxVibes: $title", true) {
            override fun run(indicator: ProgressIndicator) {
                service.notificationService.setProgressIndicator(indicator)
                val result = try {
                    runBlocking { action() }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Throwable) {
                    // Safety net: an adapter exception must surface as an Error result,
                    // otherwise the panel stays disabled forever (no handler runs).
                    MaxVibesLogger.error(
                        "Controller", "clipboard background action crashed",
                        e as? Exception ?: RuntimeException(e)
                    )
                    ClipboardStepResult.Error(
                        message = "Internal error: ${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                    )
                }
                ApplicationManager.getApplication().invokeLater { clipboardDispatcher.handleResult(result, session) }
            }

            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    service.clipboardService.reset(session.id)
                    callbacks.appendToChat("\u26A0\uFE0F Cancelled")
                    callbacks.setInputEnabled(true)
                    callbacks.updateModeIndicator()
                }
            }
        })
    }

    /**
     * Re-generates and copies the clipboard JSON for the current active session.
     * Flushes editor buffers first, then delegates to [ClipboardDispatcher.redoLastRequest].
     */
    fun redoClipboardJson() {
        saveAllDocuments()
        clipboardDispatcher.redoLastRequest()
    }

    // ==================== Claude Code Mode ====================

    private fun runClaudeCodeBg(
        title: String,
        session: ChatSession,
        action: suspend () -> ClaudeCodeStepResult
    ) {
        callbacks.setStatus("Claude Code: running")
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "MaxVibes: $title", true) {
                override fun run(indicator: ProgressIndicator) {
                    service.notificationService.setProgressIndicator(indicator)
                    val result = try {
                        runBlocking { action() }
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: Throwable) {
                        // Safety net: an adapter exception must surface as an Error result,
                        // otherwise the panel stays disabled forever (no handler runs).
                        MaxVibesLogger.error(
                            "Controller", "claudeCode background action crashed",
                            e as? Exception ?: RuntimeException(e)
                        )
                        ClaudeCodeStepResult.Error(
                            message = "Internal error: ${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                        )
                    }
                    ApplicationManager.getApplication().invokeLater {
                        claudeCodeDispatcher.handleResult(result, session)
                    }
                }

                override fun onCancel() {
                    ApplicationManager.getApplication().invokeLater {
                        service.claudeCodeService.reset(session.id)
                        callbacks.appendToChat("⚠️ Cancelled")
                        callbacks.setInputEnabled(true)
                        callbacks.updateModeIndicator()
                    }
                }
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
        ProgressManager.getInstance()
            .run(object : Task.Backgroundable(project, "MaxVibes: $progressTitle...", true) {
                override fun run(indicator: ProgressIndicator) {
                    service.notificationService.setProgressIndicator(indicator)
                    runBlocking {
                        val uc = if (useCheap) {
                            service.cheapContextAwareModifyUseCase ?: service.contextAwareModifyUseCase
                        } else {
                            service.contextAwareModifyUseCase
                        }
                        val result = uc.execute(request)
                        ApplicationManager.getApplication().invokeLater {
                            apiDispatcher.handleResult(result, session, isPlanOnly)
                        }
                    }
                }

                override fun onCancel() {
                    ApplicationManager.getApplication().invokeLater {
                        chatTreeService.addMessage(session.id, MessageRole.SYSTEM, "Cancelled")
                        callbacks.appendToChat("\u26A0\uFE0F Cancelled")
                        callbacks.setInputEnabled(true)
                        callbacks.setStatus("Cancelled")
                        MaxVibesLogger.warn("Controller", "cancelled by user")
                    }
                }
            })
    }

    /**
     * Approve the last Claude Code response. Applies pending modifications (if any) or
     * gathers requested files, then continues. Called from [ChatPanel] on the Approve button.
     */
    fun approve() {
        saveAllDocuments()
        val trace = attachedTrace
        val errs = attachedErrors
        if (attachedImages.isNotEmpty()) {
            callbacks.appendToChat("⚠️ ${attachedImages.size} attached image(s) dropped — attach them to a regular message, not to Approve")
        }
        if (pendingOneShot != null) {
            callbacks.appendToChat("⚠️ One-shot editor skill dropped — invoke it with a regular message, not with Approve")
        }
        clearAttachmentsAfterSend()
        claudeCodeDispatcher.approve(trace, errs)
    }

    // ==================== Question Flow ====================

    private val questionCoordinator = QuestionTurnCoordinator(callbacks)

    private fun presentQuestions(
        questions: List<com.maxvibes.domain.model.interaction.InteractionQuestion>
    ) = questionCoordinator.presentQuestions(questions)

    private fun dismissQuestionTurn() = questionCoordinator.dismissQuestionTurn()

    // ==================== Command Flow ====================

    /**
     * Command-turn state machine lives in [CommandTurnCoordinator]; the controller only
     * supplies threading (background execution + EDT callback) and batch continuation.
     * Lazy so tests that never touch commands don't need [MaxVibesService.executeCommandUseCase].
     */
    private val commandCoordinator by lazy {
        CommandTurnCoordinator(
            executeCommandUseCase = service.executeCommandUseCase,
            callbacks = callbacks,
            addSystemMessage = { sessionId, text -> chatTreeService.addMessage(sessionId, MessageRole.SYSTEM, text) },
            activeSessionId = { chatTreeService.getActiveSession().id },
            executeAsync = { request, onDone ->
                ProgressManager.getInstance()
                    .run(object : Task.Backgroundable(project, "MaxVibes: Running command...", false) {
                        override fun run(indicator: ProgressIndicator) {
                            val execution = runBlocking { service.executeCommandUseCase.execute(request) }
                            ApplicationManager.getApplication().invokeLater { onDone(execution) }
                        }
                    })
            },
            onBatchComplete = { sessionId, mode, formatted -> handleCommandBatchComplete(sessionId, mode, formatted) }
        )
    }
    private val claudeCodeDispatcher by lazy {
        ClaudeCodeDispatcher(
            claudeCodeService = { service.claudeCodeService },
            resolveSpecificPrompt = { name -> service.specificPromptService.resolvePromptContent(name) },
            chatTreeService = chatTreeService,
            callbacks = callbacks,
            presentQuestions = { questions -> presentQuestions(questions) },
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
    private val apiDispatcher by lazy {
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

    /** Continues the dialog after a command batch resolves, dispatching by interaction mode. */
    private fun handleCommandBatchComplete(sessionId: String, mode: InteractionMode, formatted: String) {
        val session = chatTreeService.getSessionById(sessionId)
        if (session == null) {
            callbacks.setInputEnabled(true)
            return
        }
        when (mode) {
            InteractionMode.CLIPBOARD -> runClipboardBg("Sending command results...", session) {
                service.clipboardService.submitCommandResults(session.id, formatted)
            }

            InteractionMode.CLAUDE_CODE -> runClaudeCodeBg("Sending command results...", session) {
                service.claudeCodeService.submitCommandResults(session.id, formatted)
            }

            InteractionMode.API, InteractionMode.CHEAP_API -> apiDispatcher.submitCommandResults(session, formatted)
        }
    }

    // ==================== Auto-Retry Logic ====================

    // ==================== Result Handlers ====================

    // ==================== Helpers ====================

    /**
     * Flushes all unsaved editor Documents to disk. The plugin reads project files
     * (skills, gathered sources) via java.io — without this flush, edits still sitting
     * in editor buffers are invisible to the LLM. Same convention as the IDE saving
     * all documents before a build. EDT-only.
     */
    private fun saveAllDocuments() {
        try {
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
        } catch (e: Exception) {
            MaxVibesLogger.warn("Controller", "saveAllDocuments failed", data = mapOf("msg" to (e.message ?: "?")))
        }
    }

    fun attachTrace(traceContent: String) {
        attachedTrace = traceContent
        callbacks.onAttachmentsChanged(attachedTrace, attachedErrors)
    }

    fun clearTrace() {
        attachedTrace = null
        callbacks.onAttachmentsChanged(attachedTrace, attachedErrors)
    }

    fun clearErrors() {
        attachedErrors = null
        callbacks.onAttachmentsChanged(attachedTrace, attachedErrors)
    }

    /** Attaches an image; enforces the per-message cap. Returns false when the cap is hit. */
    fun attachImage(image: AttachedImage): Boolean {
        if (attachedImages.size >= ImageAttachments.MAX_IMAGES) {
            callbacks.setStatus("🖼 Max ${ImageAttachments.MAX_IMAGES} images per message")
            return false
        }
        attachedImages.add(image)
        callbacks.onImagesChanged(attachedImages.toList())
        callbacks.setStatus("🖼 Image attached (${attachedImages.size})")
        return true
    }

    fun clearImages() {
        attachedImages.clear()
        callbacks.onImagesChanged(emptyList())
    }

    fun removeImage(index: Int) {
        if (index in attachedImages.indices) {
            attachedImages.removeAt(index)
            callbacks.onImagesChanged(attachedImages.toList())
        }
    }

    fun fetchIdeErrors() {
        callbacks.setStatus("Fetching IDE errors...")
        object : Task.Backgroundable(project, "Fetching IDE errors", false) {
            override fun run(indicator: ProgressIndicator) {
                runBlocking {
                    val result = service.ideErrorsPort.getCompilerErrors()
                    ApplicationManager.getApplication().invokeLater {
                        when (result) {
                            is Result.Success -> {
                                val errors = result.value
                                if (errors.isEmpty()) {
                                    callbacks.setStatus("No IDE errors found in open files")
                                } else {
                                    attachedErrors = errors.joinToString("\n") { it.formatForLlm() }
                                    callbacks.setStatus("Attached ${errors.size} IDE errors")
                                    callbacks.onAttachmentsChanged(attachedTrace, attachedErrors)
                                }
                            }

                            is Result.Failure -> callbacks.onError("Failed to fetch IDE errors: ${result.error}")
                        }
                    }
                }
            }
        }.queue()
    }

    fun clearAttachmentsAfterSend() {
        val hadOneShot = pendingOneShot != null
        attachedTrace = null
        attachedErrors = null
        attachedImages.clear()
        pendingOneShot = null
        callbacks.onAttachmentsChanged(null, null)
        callbacks.onImagesChanged(emptyList())
        // Only notify when a one-shot was actually armed — onOneShotChanged(null)
        // triggers a render() in the panel, redundant on every ordinary send.
        if (hadOneShot) callbacks.onOneShotChanged(null)
    }

    fun createNewSession() {
        val newSession = chatTreeService.createNewSession()
        callbacks.onSessionChanged(newSession)
    }

    fun deleteCurrentSession(sessionId: String) {
        chatTreeService.deleteSession(sessionId)
        val next = chatTreeService.getActiveSession()
        callbacks.onSessionChanged(next)
    }

    fun renameSession(sessionId: String, newTitle: String) {
        val updated = chatTreeService.renameSession(sessionId, newTitle)
        if (updated != null) callbacks.onSessionRenamed(updated)
    }

    fun branchSession(parentSessionId: String, title: String) {
        val newSession = chatTreeService.createBranch(parentSessionId, title)
        if (newSession != null) callbacks.onSessionChanged(newSession)
    }

    fun loadSession(sessionId: String) {
        chatTreeService.setActiveSession(sessionId)
        val session = chatTreeService.getSessionById(sessionId)
        if (session != null) callbacks.onSessionChanged(session)
    }

    /**
     * Updates the selected specific prompt for the currently active session.
     * Null means "Just Code" — no specific prompt.
     */
    fun selectSpecificPrompt(name: String?) {
        val session = chatTreeService.getActiveSession() ?: return
        val updated = session.withSelectedPrompt(name)
        chatTreeService.saveSession(updated)
        callbacks.onSessionChanged(updated)
    }

    fun sendMessage(
        userInput: String,
        isPlanOnly: Boolean,
        isDryRun: Boolean,
        mode: InteractionMode,
        addHistory: Boolean = false,
        selectedSpecificPromptName: String? = null
    ) {
        saveAllDocuments()
        dismissQuestionTurn()
        val trace = attachedTrace
        val errs = attachedErrors
        val imgs = attachedImages.toList()
        // Snapshot the one-shot editor skill BEFORE clearing — clearAttachmentsAfterSend nulls it.
        val oneShot = pendingOneShot
        clearAttachmentsAfterSend()
        // One-shot overrides the session skill for exactly this send; the element body
        // rides the attachedContext (trace) channel, already labeled by the action layer.
        val effectivePromptName = oneShot?.skillName ?: selectedSpecificPromptName
        val effectiveTrace = listOfNotNull(
            oneShot?.elementContext,
            trace
        ).takeIf { it.isNotEmpty() }?.joinToString("\n\n")
        MaxVibesLogger.info(
            "Controller", "sendMessage", mapOf(
                "mode" to mode.name,
                "msgLen" to userInput.length,
                "isPlanOnly" to isPlanOnly,
                "hasTrace" to (trace != null),
                "hasErrors" to (errs != null),
                "images" to imgs.size,
                "addHistory" to addHistory,
                "specificPrompt" to (effectivePromptName ?: "null"),
                "oneShot" to (oneShot?.label ?: "null")
            )
        )
        if (imgs.isNotEmpty() && mode != InteractionMode.CLAUDE_CODE) {
            callbacks.appendToChat("⚠️ ${imgs.size} image(s) dropped — images are only sent in Claude Code mode")
        }
        if (oneShot != null && (mode == InteractionMode.API || mode == InteractionMode.CHEAP_API)) {
            callbacks.appendToChat(
                "⚠️ One-shot editor skill fully works only in Clipboard / Claude Code modes — " +
                        if (mode == InteractionMode.API) "API mode gets the prefill text only"
                        else "Cheap API gets the element context but not the skill body"
            )
        }
        when (mode) {
            InteractionMode.API -> dispatchApiMessage(userInput, effectiveTrace, errs, isPlanOnly, isDryRun)
            InteractionMode.CLIPBOARD -> dispatchClipboardMessage(
                userInput,
                effectiveTrace,
                errs,
                isPlanOnly,
                addHistory,
                effectivePromptName
            )

            InteractionMode.CHEAP_API -> dispatchCheapApiMessage(
                userInput,
                effectiveTrace,
                errs,
                isPlanOnly,
                isDryRun
            )

            InteractionMode.CLAUDE_CODE -> dispatchClaudeCodeMessage(
                userInput,
                effectiveTrace,
                errs,
                isPlanOnly,
                effectivePromptName,
                images = imgs
            )
        }
    }

    private fun dispatchApiMessage(msg: String, trace: String?, errs: String?, isPlanOnly: Boolean, isDryRun: Boolean) =
        apiDispatcher.dispatchMessage(msg, trace, errs, isPlanOnly, isDryRun)

    private fun dispatchClipboardMessage(
        userInput: String,
        trace: String?,
        errs: String?,
        isPlanOnly: Boolean,
        addHistory: Boolean = false,
        selectedSpecificPromptName: String? = null
    ) = clipboardDispatcher.dispatchMessage(userInput, trace, errs, isPlanOnly, addHistory, selectedSpecificPromptName)

    private fun dispatchCheapApiMessage(
        msg: String,
        trace: String?,
        errs: String?,
        isPlanOnly: Boolean,
        isDryRun: Boolean
    ) = apiDispatcher.dispatchCheapMessage(msg, trace, errs, isPlanOnly, isDryRun)

    private fun dispatchClaudeCodeMessage(
        userInput: String,
        trace: String?,
        errs: String?,
        isPlanOnly: Boolean,
        selectedSpecificPromptName: String? = null,
        images: List<AttachedImage> = emptyList()
    ) = claudeCodeDispatcher.dispatchMessage(userInput, trace, errs, isPlanOnly, selectedSpecificPromptName, images)

    /** One-shot editor-skill invocation armed by ChatPanel.acceptPrefill; consumed and cleared by the next send. */
    private class PendingOneShot(val skillName: String?, val elementContext: String?, val label: String)

    /** Armed one-shot editor skill/context; null when nothing is pending. */
    private var pendingOneShot: PendingOneShot? = null

    /** Arms a one-shot editor skill and/or element context for the next send (editor actions). */
    fun armOneShot(skillName: String?, elementContext: String?, label: String) {
        pendingOneShot = PendingOneShot(skillName, elementContext, label)
        callbacks.onOneShotChanged(label)
    }

    /** Cancels the armed one-shot skill (chip close button). */
    fun clearOneShot() {
        pendingOneShot = null
        callbacks.onOneShotChanged(null)
    }
}
