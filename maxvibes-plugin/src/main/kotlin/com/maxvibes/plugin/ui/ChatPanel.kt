package com.maxvibes.plugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.application.service.AgentStreamHub
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.plugin.service.MaxVibesLogger
import com.maxvibes.plugin.service.MaxVibesService
import com.maxvibes.plugin.settings.MaxVibesSettings
import java.awt.*
import javax.swing.*
import com.maxvibes.domain.model.planning.PlanDiagram
import com.maxvibes.plugin.diagram.DiagramViewerDialog

class ChatPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
    private val onShowSessions: () -> Unit
) : JPanel(BorderLayout()), ChatPanelCallbacks, Disposable {

    private val conversationPanel = ConversationPanel(project) { path ->
        statusLabel.text = ChatNavigationHelper.navigateToElement(project, path)
    }

    private val statusLabel = JBLabel("Ready").apply { foreground = JBColor.GRAY }
    private val tokenLabel = JBLabel("").apply {
        foreground = JBColor.GRAY
        font = font.deriveFont(10f)
        horizontalAlignment = SwingConstants.CENTER
    }

    /**
     * Subscription usage row (Set 9): 5h and 7d bars mounted under the send-button row.
     * Fed from [streamListener] via [updateLimitsChip]; hidden until the first
     * rate_limit_event. All rendering, colors and thresholds live in [LimitsBarPanel].
     */
    private val limitsBar = LimitsBarPanel()
    private val specificPromptPanel = SpecificPromptPanel(
        onSelectPrompt = { name -> messageController.selectSpecificPrompt(name) },
        onCreatePrompt = { specificPromptFileActions.create() },
        onEditPrompt = { specificPromptFileActions.edit() },
        onDeletePrompt = { specificPromptFileActions.delete() },
        onManagePrompts = {
            SkillManagerDialog(project, service.specificPromptRepository).show()
            render(buildState())
        }
    )

    /**
     * OAuth usage poller (Set 9): every 60s reads the Claude CLI's own token from
     * ~/.claude/.credentials.json and asks api.anthropic.com/api/oauth/usage for
     * exact five_hour / seven_day utilization, feeding [limitsBar]. Unofficial
     * endpoint - fails soft; bars then live on CLI rate_limit_events alone.
     * The token never leaves the machine except to api.anthropic.com and is never
     * logged. Stopped in [dispose].
     */
    private val usagePoller = com.maxvibes.plugin.claudecode.SubscriptionUsagePoller(
        port = com.maxvibes.plugin.claudecode.ClaudeOAuthUsageAdapter(),
        onUsage = { usage -> limitsBar.onUsage(usage) }
    ).also { it.start() }

    private fun updateLimitsChip(e: com.maxvibes.application.port.output.AgentStreamEvent.RateLimitUpdate) {
        MaxVibesLogger.info(
            "ChatPanel",
            "limits event",
            mapOf("kind" to e.kind, "pct" to (e.utilizationPct ?: -1), "status" to e.status)
        )
        limitsBar.onRateLimit(e)
    }

    /**
     * Live in-progress block for the current Claude Code turn: header with elapsed
     * time and last-event age, streaming narration, tool feed, notices, Stop.
     * Event-driven via [streamListener]; hides itself on Completed and flushes the
     * partial narration into the conversation on Failed.
     */
    private val liveTurnPanel = LiveTurnPanel(
        onStop = { service.abortClaudeCode() },
        onPartialFlush = { partial, reason ->
            conversationPanel.addSystemBubble("\u26A0 Turn ended: $reason")
            if (partial.isNotBlank()) conversationPanel.addAssistantBubble(partial)
        }
    )

    private val planPanel = PlanPanel(
        onToggleStep = { stepId, newStatus ->
            chatTreeService.setPlanStepStatus(chatTreeService.getActiveSession().id, stepId, newStatus)
            render(buildState())
        },
        onOpenDoc = { environmentActions.openPlanDoc(it) }
    )

    private val service: MaxVibesService by lazy { MaxVibesService.getInstance(project) }
    private val chatTreeService get() = service.chatTreeService
    private val settings: MaxVibesSettings by lazy { MaxVibesSettings.getInstance() }

    /** Null when the project has no base path (default/light projects). */
    private val specificPromptFiles: SpecificPromptFiles? by lazy {
        project.basePath?.let { SpecificPromptFiles(it) }
    }
    private val specificPromptFileActions: SpecificPromptFileActions by lazy {
        SpecificPromptFileActions(
            files = specificPromptFiles,
            selectedPromptName = { buildState().selectedSpecificPromptName },
            persistedPromptName = { chatTreeService.getActiveSession().selectedSpecificPromptName },
            openFile = { file ->
                com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .refreshAndFindFileByIoFile(file)
                    ?.let {
                        com.intellij.openapi.fileEditor.FileEditorManager
                            .getInstance(project)
                            .openFile(it, true)
                    }
            },
            confirmDelete = { name ->
                JOptionPane.showConfirmDialog(
                    this,
                    "Delete prompt '$name'? The file will be permanently removed from disk.",
                    "Delete Prompt",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                ) == JOptionPane.YES_OPTION
            },
            onClearSelection = { messageController.selectSpecificPrompt(null) },
            onStatus = { statusLabel.text = it },
            onRefresh = { render(buildState()) }
        )
    }
    private val claudeCliSettingsPanel: ClaudeCliSettingsPanel by lazy {
        ClaudeCliSettingsPanel(
            settings = object : ClaudeCliSettings {
                override var model: String
                    get() = settings.claudeCodeModel
                    set(value) {
                        settings.claudeCodeModel = value
                    }

                override var effortLevel: String
                    get() = settings.claudeCodeEffortLevel
                    set(value) {
                        settings.claudeCodeEffortLevel = value
                    }
            },
            onStatus = { statusLabel.text = it }
        )
    }
    private val environmentActions: ChatPanelEnvironmentActions by lazy {
        ChatPanelEnvironmentActions(
            project = project,
            toolWindow = toolWindow,
            parent = this,
            chatTreeService = chatTreeService,
            promptService = service.promptService,
            claudeCodeLogPath = { sessionId -> service.claudeCodeSessionLog.logFilePath(sessionId) },
            attachTrace = { messageController.attachTrace(it) },
            onContextChanged = { render(buildState()) },
            onStatus = { statusLabel.text = it },
            onToolWindowState = { maximized, floating ->
                headerPanel.updateToolWindowIcons(maximized, floating)
            }
        )
    }
    private val sessionUiCoordinator: ChatSessionUiCoordinator by lazy {
        ChatSessionUiCoordinator(
            activeSession = { chatTreeService.getActiveSession() },
            sessionPath = { chatTreeService.getSessionPath(it) },
            parentSession = { chatTreeService.getParent(it) },
            childCount = { chatTreeService.getChildCount(it) },
            setActiveSession = { chatTreeService.setActiveSession(it) },
            transcriptRenderer = SessionTranscriptRenderer(),
            transcriptView = ConversationPanelTranscriptView(conversationPanel),
            dialogs = SwingChatSessionDialogs(this),
            currentMode = { modeManager.currentMode },
            contextFilesCount = { chatTreeService.getGlobalContextFiles().size },
            clearNavigation = { elementNavRegistry.clear() },
            registerModifications = ::registerElementPaths,
            clearAttachments = { messageController.clearAttachmentsAfterSend() },
            createSession = { messageController.createNewSession() },
            branchSession = { parentId, title -> messageController.branchSession(parentId, title) },
            deleteSession = { messageController.deleteCurrentSession(it) },
            renameSession = { sessionId, title -> messageController.renameSession(sessionId, title) },
            onStatus = { statusLabel.text = it },
            onRefresh = { render(buildState()) }
        )
    }

    /**
     * Routes AgentStreamHub events for the ACTIVE session into [liveTurnPanel].
     * Called on the transport reader thread; the panel buffers internally and
     * drains on its own EDT timer, so no per-event invokeLater happens here.
     */
    private val streamListener = AgentStreamHub.Listener { sessionId, event ->
        if (sessionId == chatTreeService.getActiveSession().id) liveTurnPanel.onEvent(event)
        if (event is com.maxvibes.application.port.output.AgentStreamEvent.RateLimitUpdate) updateLimitsChip(event)
    }

    // Manages interaction mode state (API / Clipboard / CheapAPI / ClaudeCode).
    // Extracted from ChatPanel to separate state logic from UI.
    private val modeManager: InteractionModeManager by lazy {
        InteractionModeManager(
            settings = settings,
            onModeChanged = { mode ->
                settings.interactionMode = mode.name
                if (mode == InteractionMode.CHEAP_API) @Suppress("DEPRECATION") service.ensureCheapLLMService()
                render(buildState())
            }
        )
    }

    private val elementNavRegistry = mutableMapOf<String, String>()
    private val headerPanel = ChatHeaderPanel(
        onModeSelected = ::handleModeSelection,
        onIndicatorAction = ::handleIndicatorAction,
        onOpenCcLog = { environmentActions.openClaudeCodeLog() },
        onShowSessions = onShowSessions,
        onNewChat = { sessionUiCoordinator.createNewChat() },
        onBranch = { sessionUiCoordinator.createBranch() },
        onDeleteChat = { sessionUiCoordinator.deleteCurrentChat() },
        onOpenPrompts = { environmentActions.openPrompts() },
        onContextFiles = { environmentActions.showContextFilesDialog() },
        onClaudeInstructions = { anchor -> environmentActions.showClaudeInstructions(anchor) },
        onToggleMaximize = { environmentActions.toggleMaximize() },
        onToggleWindowed = { environmentActions.toggleWindowed() },
        onSelectSession = { sessionUiCoordinator.selectSession(it) },
        onRenameSession = { sessionId, title ->
            sessionUiCoordinator.renameSession(sessionId, title)
        }
    )
    private val inputPanel = ChatInputPanel(
        promptBar = buildPromptPanel(),
        usageBar = limitsBar,
        onSend = ::sendMessage,
        onApprove = { messageController.approve() },
        onCopyJson = { messageController.redoClipboardJson() },
        onAttachTrace = { environmentActions.attachTraceFromClipboard() },
        onClearTrace = { messageController.clearTrace() },
        onAttachErrors = { messageController.fetchIdeErrors() },
        onClearErrors = { messageController.clearErrors() },
        onImagePasted = { image -> messageController.attachImage(image) },
        onClearImages = { messageController.clearImages() },
        onClearOneShot = { messageController.clearOneShot() }
    )

    private val messageController: ChatMessageController by lazy {
        ChatMessageController(project, service, this)
    }

    override fun appendToChat(text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        if (t.all { it == '\u2500' || it == '\u2550' || it == '\u2501' || it == '-' }) return
        if (t.contains("Paste this into") || t.contains("JSON copied") || t.startsWith("\uD83D\uDCCB")) return
        conversationPanel.addSystemBubble(t)
    }

    override fun appendAssistantMessage(text: String) {
        conversationPanel.addAssistantBubble(formatMarkdown(text))
    }

    override fun addUserMessageBubble(text: String, images: List<AttachedImage>) {
        conversationPanel.addUserBubble(text, images)
    }

    override fun addAssistantMessageBubble(
        text: String,
        tokenInfo: String?,
        modifications: List<ModificationResult>,
        metaFiles: List<String>,
        reasoning: String?,
        requestedViews: List<com.maxvibes.domain.model.code.RequestedViewInfo>,
        appliedModifications: List<com.maxvibes.domain.model.modification.AppliedModInfo>
    ) {
        conversationPanel.addAssistantBubble(
            text,
            tokenInfo,
            modifications,
            metaFiles,
            reasoning,
            requestedViews,
            appliedModifications
        )
        registerElementPaths(modifications)
    }

    override fun addCommandBubble(
        command: String, reason: String?, warnings: List<String>,
        onRun: () -> Unit, onDecline: (String?) -> Unit
    ): CommandBlockView = conversationPanel.addCommandBubble(command, reason, warnings, onRun, onDecline)

    override fun addQuestionBubble(
        question: String,
        options: List<String>,
        onAnswer: (String) -> Unit
    ): QuestionBlockView = conversationPanel.addQuestionBubble(question, options, onAnswer)

    override fun sendUserMessage(text: String) {
        inputPanel.setText(text)
        sendMessage()
    }

    override fun addCommandBatchBar(count: Int, onRunAll: () -> Unit, onDeclineAll: () -> Unit): CommandBatchBarView =
        conversationPanel.addCommandBatchBar(count, onRunAll, onDeclineAll)

    override fun clearChatDisplay() {
        conversationPanel.clearMessages()
        elementNavRegistry.clear()
    }

    override fun appendIconToLastBubble(icon: String) {
        conversationPanel.appendIconToLastBubble(icon)
    }

    override fun setInputEnabled(enabled: Boolean) {
        inputPanel.setControlsEnabled(enabled)
        headerPanel.setControlsEnabled(enabled)
        specificPromptPanel.setControlsEnabled(enabled)
        claudeCliSettingsPanel.setControlsEnabled(enabled)
    }

    override fun setStatus(text: String) {
        statusLabel.text = text
    }

    override fun updateModeIndicator() {
        render(buildState())
    }

    override fun updateBreadcrumb() {
        val session = chatTreeService.getActiveSession()
        headerPanel.updateBreadcrumb(chatTreeService.getSessionPath(session.id))
    }

    override fun registerElementPaths(modifications: List<ModificationResult>) {
        ChatNavigationHelper.registerElementPaths(modifications, elementNavRegistry)
    }

    override fun formatMarkdown(text: String): String = text

    override fun updateTokenDisplay() {
        val session = chatTreeService.getActiveSession()
        tokenLabel.text = session.tokenUsage.formatDisplay()
    }

    override fun setCommitMessage(message: String) {
        environmentActions.setCommitMessage(message)
    }

    override fun setPlanOnlyMode(enabled: Boolean) {
        inputPanel.setPlanOnly(enabled)
    }

    fun refreshHeader() {
        render(buildState())
    }

    fun loadCurrentSession() {
        sessionUiCoordinator.loadCurrentSession()
    }

    private fun setupUI() {
        border = JBUI.Borders.empty()
        background = JBColor.background()

        val statusBar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(2, 10, 2, 10)
            background = JBColor.background()
            add(JPanel(BorderLayout()).apply {
                background = JBColor.background()
                add(statusLabel, BorderLayout.WEST)
            })
            add(JPanel(BorderLayout()).apply {
                background = JBColor.background()
                add(tokenLabel.apply {
                    horizontalAlignment = SwingConstants.LEFT
                }, BorderLayout.WEST)
            })
        }

        val conversationWithPlan = JPanel(BorderLayout()).apply {
            background = JBColor.background()
            add(planPanel, BorderLayout.NORTH)
            add(conversationPanel, BorderLayout.CENTER)
        }

        val conversationSplitter = com.intellij.ui.OnePixelSplitter(true, 0.72f).apply {
            firstComponent = conversationWithPlan
            secondComponent = liveTurnPanel
            setAndLoadSplitterProportionKey("MaxVibes.liveTurnSplitterProportion")
        }

        add(headerPanel, BorderLayout.NORTH)
        add(conversationSplitter, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(inputPanel, BorderLayout.CENTER)
            add(statusBar, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
    }

    private fun sendMessage() {
        val submission = inputPanel.takeSubmission() ?: return
        val state = buildState()
        messageController.sendMessage(
            submission.text,
            submission.planOnly,
            submission.dryRun,
            modeManager.currentMode,
            submission.addHistory,
            state.selectedSpecificPromptName
        )
    }

    private fun syncComboBoxToMode() {
        headerPanel.selectMode(modeManager.currentMode)
    }

    private fun updateModeUI(state: ChatPanelState) {
        val decision = ModeUiPolicy.decide(state.mode, state.clipboardStatus)
        headerPanel.applyModeDecision(decision)
        inputPanel.applyModeDecision(decision)
    }

    fun render(state: ChatPanelState) {
        headerPanel.updateBreadcrumb(state.sessionPath)
        updateModeUI(state)
        planPanel.update(state.plan)
        claudeCliSettingsPanel.setClaudeCodeVisible(
            state.mode == InteractionMode.CLAUDE_CODE
        )
        inputPanel.updateIndicators(state.attachedTrace, state.attachedErrors)
        tokenLabel.text = state.currentSession?.tokenUsage?.formatDisplay().orEmpty()
        headerPanel.updateContextCount(state.contextFilesCount)
        environmentActions.refreshToolWindowState()
        specificPromptPanel.render(
            availablePrompts = state.availablePrompts,
            selectedPromptName = state.selectedSpecificPromptName
        )
        inputPanel.applyApproveState(state.claudeCodeApproveVisible)
    }

    private fun buildPromptPanel(): JPanel {
        return JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            background = JBColor.background()
            add(claudeCliSettingsPanel)
            add(specificPromptPanel)
        }
    }

    private fun buildState(): ChatPanelState {
        val session = chatTreeService.getActiveSession()
        val mode = modeManager.currentMode
        val approveVisible = mode == InteractionMode.CLAUDE_CODE &&
                session.clipboardStatus == ClipboardSessionStatus.AWAITING_APPROVE
        return ChatPanelState(
            currentSession = session,
            sessionPath = chatTreeService.getSessionPath(session.id),
            mode = mode,
            attachedTrace = messageController.attachedTrace,
            attachedErrors = messageController.attachedErrors,
            contextFilesCount = chatTreeService.getGlobalContextFiles().size,
            tokenUsage = session.tokenUsage.takeIf { !it.isEmpty() },
            clipboardStatus = session.clipboardStatus,
            availablePrompts = service.specificPromptService.getAvailablePromptNames(),
            selectedSpecificPromptName = service.specificPromptService
                .validatePromptName(chatTreeService.getActiveSession()?.selectedSpecificPromptName),
            claudeCodeApproveVisible = approveVisible,
            claudeCodeSending = false,
            plan = session.plan
        )
    }

    override fun onAttachmentsChanged(trace: String?, errors: String?) {
        render(buildState())
    }

    override fun onImagesChanged(images: List<AttachedImage>) {
        inputPanel.showImages(images)
    }

    override fun onError(message: String) {
        setStatus(message)
    }

    override fun onSessionChanged(session: ChatSession?) {
        sessionUiCoordinator.loadCurrentSession()
    }

    override fun onSessionRenamed(session: ChatSession) {
        render(buildState())
    }

    override fun onShowWelcome() {
        sessionUiCoordinator.showWelcome()
    }

    /**
     * Called by IntelliJ via the Disposer chain when [toolWindow] is being closed.
     * Releases the stream-hub subscription, stops the live panel's drain timer and
     * the OAuth usage poller. Safe to call multiple times.
     */
    override fun dispose() {
        runCatching { service.agentStreamHub.removeListener(streamListener) }
        runCatching { liveTurnPanel.dispose() }
        runCatching { usagePoller.stop() }
    }

    override fun addPostApplyErrorsBubble(
        summary: String, details: String, onSend: () -> Unit, onDismiss: () -> Unit
    ): PostApplyErrorsView = conversationPanel.addPostApplyErrorsBubble(summary, details, onSend, onDismiss)

    fun acceptPrefill(prefill: EditorPrefill) {
        inputPanel.prefill(prefill.text, prefill.append)
        if (prefill.oneShotSkillName != null || prefill.elementContext != null) {
            messageController.armOneShot(
                prefill.oneShotSkillName,
                prefill.elementContext,
                prefill.elementLabel ?: "element"
            )
        }
    }

    override fun onOneShotChanged(label: String?) {
        inputPanel.showOneShot(label)
    }

    /**
     * Adds a "Схема" button under the last assistant bubble; each click opens a fresh
     * non-modal [DiagramViewerDialog] for the diagram carried by that turn's response.
     */
    override fun showDiagramButton(diagram: PlanDiagram) {
        conversationPanel.addDiagramButton {
            DiagramViewerDialog(project, diagram).show()
        }
    }
    private fun handleModeSelection(newMode: InteractionMode) {
        if (newMode == modeManager.currentMode) return

        if (modeManager.currentMode == InteractionMode.CLIPBOARD &&
            buildState().clipboardStatus == ClipboardSessionStatus.AWAITING_PASTE
        ) {
            val confirm = JOptionPane.showConfirmDialog(
                this,
                "Active clipboard session will be reset. Continue?",
                "Switch Mode",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (confirm != JOptionPane.YES_OPTION) {
                syncComboBoxToMode()
                return
            }
            service.clipboardService.reset(chatTreeService.getActiveSession().id)
        }

        MaxVibesLogger.info(
            "ChatPanel",
            "switchMode",
            mapOf("from" to modeManager.currentMode.name, "to" to newMode.name)
        )
        modeManager.switchMode(newMode)
        val label = MaxVibesSettings.INTERACTION_MODES
            .find { it.first == newMode.name }
            ?.second
            ?: newMode.name
        statusLabel.text = "Mode: $label"
        conversationPanel.addSystemBubble("⚙️ Switched to $label")
    }
    private fun handleIndicatorAction(action: IndicatorAction) {
        val sessionId = chatTreeService.getActiveSession().id
        when (action) {
            IndicatorAction.FORCE_ACTIVATE -> service.clipboardService.forceActivate(sessionId)
            IndicatorAction.FORCE_AWAIT_PASTE -> service.clipboardService.forceAwaitPaste(sessionId)
        }
        render(buildState())
    }

}
