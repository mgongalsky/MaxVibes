package com.maxvibes.plugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.plugin.claudecode.ClaudeOAuthUsageAdapter
import com.maxvibes.plugin.claudecode.SubscriptionUsagePoller
import com.maxvibes.plugin.diagram.DiagramViewerDialog
import com.maxvibes.plugin.service.MaxVibesService
import com.maxvibes.plugin.settings.MaxVibesSettings
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JOptionPane
import javax.swing.SwingConstants

class ChatPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
    private val onShowSessions: () -> Unit
) : JPanel(BorderLayout()), Disposable {

    private val conversationPanel = ConversationPanel(project) { path ->
        statusLabel.text = ChatNavigationHelper.navigateToElement(project, path)
    }

    private val statusLabel = JBLabel("Ready").apply { foreground = JBColor.GRAY }
    private val tokenLabel = JBLabel("").apply {
        foreground = JBColor.GRAY
        font = font.deriveFont(10f)
        horizontalAlignment = SwingConstants.CENTER
    }

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

    private val liveTurnPanel = LiveTurnPanel(
        onStop = { service.abortClaudeCode() },
        onPartialFlush = { partial, reason ->
            conversationPanel.addSystemBubble("⚠ Turn ended: $reason")
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
            claudeCodeLogPath = { sessionId ->
                service.claudeCodeSessionLog.logFilePath(sessionId)
            },
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
            currentMode = { modeCoordinator.currentMode },
            contextFilesCount = { chatTreeService.getGlobalContextFiles().size },
            clearNavigation = { elementNavRegistry.clear() },
            registerModifications = {
                ChatNavigationHelper.registerElementPaths(it, elementNavRegistry)
            },
            clearAttachments = { messageController.clearAttachmentsAfterSend() },
            createSession = { messageController.createNewSession() },
            branchSession = { parentId, title ->
                messageController.branchSession(parentId, title)
            },
            deleteSession = { messageController.deleteCurrentSession(it) },
            renameSession = { sessionId, title ->
                messageController.renameSession(sessionId, title)
            },
            onStatus = { statusLabel.text = it },
            onRefresh = { render(buildState()) }
        )
    }

    private val modeCoordinator: ChatModeCoordinator by lazy {
        val modeState = InteractionModeManager(
            settings = settings,
            onModeChanged = { mode ->
                settings.interactionMode = mode.name
                if (mode == InteractionMode.CHEAP_API) {
                    @Suppress("DEPRECATION")
                    service.ensureCheapLLMService()
                }
                render(buildState())
            }
        )
        ChatModeCoordinator(
            modeState = modeState,
            dialogs = SwingChatModeDialogs(this),
            clipboardStatus = { chatTreeService.getActiveSession().clipboardStatus },
            activeSessionId = { chatTreeService.getActiveSession().id },
            resetClipboard = { service.clipboardService.reset(it) },
            forceActivate = { service.clipboardService.forceActivate(it) },
            forceAwaitPaste = { service.clipboardService.forceAwaitPaste(it) },
            onSelectMode = { headerPanel.selectMode(it) },
            onApplyDecision = { decision ->
                headerPanel.applyModeDecision(decision)
                inputPanel.applyModeDecision(decision)
            },
            onStatus = { statusLabel.text = it },
            onSystemMessage = { conversationPanel.addSystemBubble(it) },
            onRefresh = { render(buildState()) }
        )
    }

    private val runtimeCoordinator: ChatRuntimeCoordinator by lazy {
        val usagePoller = SubscriptionUsagePoller(
            port = ClaudeOAuthUsageAdapter(),
            onUsage = { limitsBar.onUsage(it) }
        )
        ChatRuntimeCoordinator(
            streamHub = service.agentStreamHub,
            activeSessionId = { chatTreeService.getActiveSession().id },
            onActiveEvent = { liveTurnPanel.onEvent(it) },
            onRateLimit = { limitsBar.onRateLimit(it) },
            startUsagePolling = usagePoller::start,
            stopUsagePolling = usagePoller::stop
        )
    }

    private val elementNavRegistry = mutableMapOf<String, String>()

    private val headerPanel = ChatHeaderPanel(
        onModeSelected = { modeCoordinator.handleSelection(it) },
        onIndicatorAction = { modeCoordinator.handleIndicatorAction(it) },
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

    private val stateFactory: ChatPanelStateFactory by lazy {
        ChatPanelStateFactory(
            activeSession = { chatTreeService.getActiveSession() },
            sessionPath = { chatTreeService.getSessionPath(it) },
            currentMode = { modeCoordinator.currentMode },
            attachedTrace = { messageController.attachedTrace },
            attachedErrors = { messageController.attachedErrors },
            contextFilesCount = { chatTreeService.getGlobalContextFiles().size },
            availablePrompts = { service.specificPromptService.getAvailablePromptNames() },
            validatePromptName = { service.specificPromptService.validatePromptName(it) }
        )
    }

    private val callbacksAdapter: ChatPanelCallbacksAdapter by lazy {
        ChatPanelCallbacksAdapter(
            conversationPanel = conversationPanel,
            inputPanel = inputPanel,
            headerPanel = headerPanel,
            specificPromptPanel = specificPromptPanel,
            claudeCliSettingsPanel = claudeCliSettingsPanel,
            onStatus = { statusLabel.text = it },
            onRender = { render(buildState()) },
            onUpdateBreadcrumb = {
                val session = chatTreeService.getActiveSession()
                headerPanel.updateBreadcrumb(chatTreeService.getSessionPath(session.id))
            },
            onUpdateTokenDisplay = {
                tokenLabel.text = chatTreeService.getActiveSession().tokenUsage.formatDisplay()
            },
            onRegisterElementPaths = {
                ChatNavigationHelper.registerElementPaths(it, elementNavRegistry)
            },
            onCommitMessage = { environmentActions.setCommitMessage(it) },
            onLoadSession = { sessionUiCoordinator.loadCurrentSession() },
            onShowWelcome = { sessionUiCoordinator.showWelcome() },
            onSendCurrentInput = ::sendMessage,
            onOpenDiagram = { diagram -> DiagramViewerDialog(project, diagram).show() },
            onClearNavigation = { elementNavRegistry.clear() }
        )
    }

    private val messageController: ChatMessageController by lazy {
        ChatMessageController(project, service, callbacksAdapter)
    }

    init {
        setupUI()
        sessionUiCoordinator.loadCurrentSession()
        modeCoordinator.initialize()
        runtimeCoordinator.start()
        Disposer.register(toolWindow.disposable, this)
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
            modeCoordinator.currentMode,
            submission.addHistory,
            state.selectedSpecificPromptName
        )
    }

    fun render(state: ChatPanelState) {
        headerPanel.updateBreadcrumb(state.sessionPath)
        modeCoordinator.applyUi(state.mode, state.clipboardStatus)
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

    private fun buildPromptPanel(): JPanel =
        JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            background = JBColor.background()
            add(claudeCliSettingsPanel)
            add(specificPromptPanel)
        }

    private fun buildState(): ChatPanelState = stateFactory.build()

    fun refreshHeader() {
        render(buildState())
    }

    fun loadCurrentSession() {
        sessionUiCoordinator.loadCurrentSession()
    }

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

    override fun dispose() {
        runCatching { runtimeCoordinator.dispose() }
        runCatching { liveTurnPanel.dispose() }
    }
}
