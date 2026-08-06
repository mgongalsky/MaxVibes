package com.maxvibes.plugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.plugin.claudecode.ClaudeOAuthUsageAdapter
import com.maxvibes.plugin.claudecode.SubscriptionUsagePoller
import com.maxvibes.plugin.diagram.DiagramViewerDialog
import com.maxvibes.plugin.service.MaxVibesService
import com.maxvibes.plugin.settings.MaxVibesSettings
import java.awt.BorderLayout
import javax.swing.JOptionPane
import javax.swing.JPanel

class ChatPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
    private val onShowSessions: () -> Unit
) : JPanel(BorderLayout()), Disposable {

    private val service: MaxVibesService by lazy { MaxVibesService.getInstance(project) }
    private val chatTreeService get() = service.chatTreeService
    private val settings: MaxVibesSettings by lazy { MaxVibesSettings.getInstance() }
    private val elementNavRegistry = mutableMapOf<String, String>()

    private val view = ChatPanelView(
        project = project,
        claudeCliSettings = object : ClaudeCliSettings {
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
        actions = ChatPanelViewActions(
            onNavigateToPath = { ChatNavigationHelper.navigateToElement(project, it) },
            onSelectPrompt = { messageController.selectSpecificPrompt(it) },
            onCreatePrompt = { specificPromptFileActions.create() },
            onEditPrompt = { specificPromptFileActions.edit() },
            onDeletePrompt = { specificPromptFileActions.delete() },
            onManagePrompts = {
                SkillManagerDialog(project, service.specificPromptRepository).show()
                render(buildState())
            },
            onStop = { service.abortClaudeCode() },
            onTogglePlanStep = { stepId, newStatus ->
                chatTreeService.setPlanStepStatus(
                    chatTreeService.getActiveSession().id,
                    stepId,
                    newStatus
                )
                render(buildState())
            },
            onOpenPlanDoc = { environmentActions.openPlanDoc(it) },
            onModeSelected = { modeCoordinator.handleSelection(it) },
            onIndicatorAction = { modeCoordinator.handleIndicatorAction(it) },
            onOpenCcLog = { environmentActions.openClaudeCodeLog() },
            onShowSessions = onShowSessions,
            onNewChat = { sessionUiCoordinator.createNewChat() },
            onBranch = { sessionUiCoordinator.createBranch() },
            onDeleteChat = { sessionUiCoordinator.deleteCurrentChat() },
            onOpenPrompts = { environmentActions.openPrompts() },
            onContextFiles = { environmentActions.showContextFilesDialog() },
            onClaudeInstructions = { anchor ->
                environmentActions.showClaudeInstructions(anchor)
            },
            onToggleMaximize = { environmentActions.toggleMaximize() },
            onToggleWindowed = { environmentActions.toggleWindowed() },
            onSelectSession = { sessionUiCoordinator.selectSession(it) },
            onRenameSession = { sessionId, title ->
                sessionUiCoordinator.renameSession(sessionId, title)
            },
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
    )

    private val specificPromptFiles: SpecificPromptFiles? by lazy {
        project.basePath?.let { SpecificPromptFiles(it) }
    }

    private val specificPromptFileActions: SpecificPromptFileActions by lazy {
        SpecificPromptFileActions(
            files = specificPromptFiles,
            selectedPromptName = { buildState().selectedSpecificPromptName },
            persistedPromptName = {
                chatTreeService.getActiveSession().selectedSpecificPromptName
            },
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
            onStatus = view::setStatus,
            onRefresh = { render(buildState()) }
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
            onStatus = view::setStatus,
            onToolWindowState = view::updateToolWindowIcons
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
            transcriptView = view.transcriptView,
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
            onStatus = view::setStatus,
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
            onSelectMode = view::selectMode,
            onApplyDecision = view::applyModeDecision,
            onStatus = view::setStatus,
            onSystemMessage = view::addSystemBubble,
            onRefresh = { render(buildState()) }
        )
    }

    private val runtimeCoordinator: ChatRuntimeCoordinator by lazy {
        val usagePoller = SubscriptionUsagePoller(
            port = ClaudeOAuthUsageAdapter(),
            onUsage = view::onUsage
        )
        ChatRuntimeCoordinator(
            streamHub = service.agentStreamHub,
            activeSessionId = { chatTreeService.getActiveSession().id },
            onActiveEvent = view::onLiveEvent,
            onRateLimit = view::onRateLimit,
            startUsagePolling = usagePoller::start,
            stopUsagePolling = usagePoller::stop
        )
    }

    private val stateFactory: ChatPanelStateFactory by lazy {
        ChatPanelStateFactory(
            activeSession = { chatTreeService.getActiveSession() },
            sessionPath = { chatTreeService.getSessionPath(it) },
            currentMode = { modeCoordinator.currentMode },
            attachedTrace = { messageController.attachedTrace },
            attachedErrors = { messageController.attachedErrors },
            contextFilesCount = { chatTreeService.getGlobalContextFiles().size },
            availablePrompts = {
                service.specificPromptService.getAvailablePromptNames()
            },
            validatePromptName = {
                service.specificPromptService.validatePromptName(it)
            }
        )
    }

    private val callbacksAdapter: ChatPanelCallbacksAdapter by lazy {
        ChatPanelCallbacksAdapter(
            conversationPanel = view.conversationPanel,
            inputPanel = view.inputPanel,
            headerPanel = view.headerPanel,
            specificPromptPanel = view.specificPromptPanel,
            claudeCliSettingsPanel = view.claudeCliSettingsPanel,
            onStatus = view::setStatus,
            onRender = { render(buildState()) },
            onUpdateBreadcrumb = {
                val session = chatTreeService.getActiveSession()
                view.updateBreadcrumb(chatTreeService.getSessionPath(session.id))
            },
            onUpdateTokenDisplay = {
                view.updateTokenDisplay(
                    chatTreeService.getActiveSession().tokenUsage.formatDisplay()
                )
            },
            onRegisterElementPaths = {
                ChatNavigationHelper.registerElementPaths(it, elementNavRegistry)
            },
            onCommitMessage = { environmentActions.setCommitMessage(it) },
            onLoadSession = { sessionUiCoordinator.loadCurrentSession() },
            onShowWelcome = { sessionUiCoordinator.showWelcome() },
            onSendCurrentInput = ::sendMessage,
            onOpenDiagram = { diagram ->
                DiagramViewerDialog(project, diagram).show()
            },
            onClearNavigation = { elementNavRegistry.clear() }
        )
    }

    private val messageController: ChatMessageController by lazy {
        ChatMessageController(project, service, callbacksAdapter)
    }

    init {
        add(view, BorderLayout.CENTER)
        sessionUiCoordinator.loadCurrentSession()
        modeCoordinator.initialize()
        runtimeCoordinator.start()
        Disposer.register(toolWindow.disposable, this)
    }

    private fun sendMessage() {
        val submission = view.takeSubmission() ?: return
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
        modeCoordinator.applyUi(state.mode, state.clipboardStatus)
        view.render(state)
        environmentActions.refreshToolWindowState()
    }

    private fun buildState(): ChatPanelState = stateFactory.build()

    fun refreshHeader() {
        render(buildState())
    }

    fun loadCurrentSession() {
        sessionUiCoordinator.loadCurrentSession()
    }

    fun acceptPrefill(prefill: EditorPrefill) {
        view.prefill(prefill)
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
        runCatching { view.disposeView() }
    }
}
