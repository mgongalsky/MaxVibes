package com.maxvibes.plugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.plugin.claudecode.ClaudeOAuthUsageAdapter
import com.maxvibes.plugin.claudecode.SubscriptionUsagePoller
import com.maxvibes.plugin.diagram.DiagramViewerDialog
import com.maxvibes.plugin.service.MaxVibesService
import com.maxvibes.plugin.settings.MaxVibesSettings
import javax.swing.JComponent
import javax.swing.JOptionPane
import com.intellij.openapi.options.ShowSettingsUtil
import com.maxvibes.plugin.settings.MaxVibesSettingsConfigurable
import com.maxvibes.plugin.settings.VoiceTranscriptionSettings
import com.maxvibes.plugin.voice.VoiceInputCoordinator
import com.maxvibes.domain.model.chat.CodingAgentProvider
import com.maxvibes.domain.model.interaction.CodingAgentCapabilities
import com.maxvibes.plugin.service.MaxVibesLogger
import com.maxvibes.plugin.settings.ApprovalPolicySettings
import com.maxvibes.plugin.claudecode.ClaudeUsageSnapshotCache

/**
 * Composition root behind the thin [ChatPanel] facade.
 *
 * Owns controller/coordinator construction, wiring and lifecycle. The Swing hierarchy
 * remains in [ChatPanelView]; application behavior remains in the extracted coordinators.
 */
class ChatPanelComposition(
    private val project: Project,
    private val toolWindow: ToolWindow,
    private val onShowSessions: () -> Unit,
    private val parent: JComponent
) : Disposable {

    private val service: MaxVibesService by lazy { MaxVibesService.getInstance(project) }
    private val chatTreeService get() = service.chatTreeService
    private val settings: MaxVibesSettings by lazy { MaxVibesSettings.getInstance() }
    private val elementNavRegistry = mutableMapOf<String, String>()

    val view = ChatPanelView(
        project = project,
        claudeCliSettings = AgentCliSettingsAdapter(settings),
        actions = ChatPanelViewActions(
            onNavigateToPath = { ChatNavigationHelper.navigateToElement(project, it) },
            onSelectPrompt = { messageController.selectSpecificPrompt(it) },
            onCreatePrompt = { specificPromptFileActions.create() },
            onEditPrompt = { specificPromptFileActions.edit() },
            onDeletePrompt = { specificPromptFileActions.delete() },
            onManagePrompts = {
                SkillManagerDialog(project, service.specificPromptRepository).show()
                render()
            },
            onStop = { service.abortClaudeCode() },
            onTogglePlanStep = { stepId, newStatus ->
                chatTreeService.setPlanStepStatus(
                    chatTreeService.getActiveSession().id,
                    stepId,
                    newStatus
                )
                render()
            },
            onOpenPlanDoc = { environmentActions.openPlanDoc(it) },
            onModeSelected = { modeCoordinator.handleSelection(it) },
            onAgentSelected = ::switchAgent,
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
                    parent,
                    "Delete prompt '$name'? The file will be permanently removed from disk.",
                    "Delete Prompt",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                ) == JOptionPane.YES_OPTION
            },
            onClearSelection = { messageController.selectSpecificPrompt(null) },
            onStatus = view::setStatus,
            onRefresh = ::render
        )
    }

    private val environmentActions: ChatPanelEnvironmentActions by lazy {
        ChatPanelEnvironmentActions(
            project = project,
            toolWindow = toolWindow,
            parent = parent,
            chatTreeService = chatTreeService,
            promptService = service.promptService,
            claudeCodeLogPath = { sessionId ->
                service.claudeCodeSessionLog.logFilePath(sessionId)
            },
            attachTrace = { messageController.attachTrace(it) },
            onContextChanged = ::render,
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
            dialogs = SwingChatSessionDialogs(parent),
            currentMode = { modeCoordinator.currentMode },
            contextFilesCount = { chatTreeService.getGlobalContextFiles().size },
            clearNavigation = { elementNavRegistry.clear() },
            registerModifications = ::registerElementPaths,
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
            onRefresh = ::render
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
                render()
            }
        )
        ChatModeCoordinator(
            modeState = modeState,
            dialogs = SwingChatModeDialogs(parent),
            clipboardStatus = { chatTreeService.getActiveSession().clipboardStatus },
            activeSessionId = { chatTreeService.getActiveSession().id },
            resetClipboard = { service.clipboardService.reset(it) },
            forceActivate = { service.clipboardService.forceActivate(it) },
            forceAwaitPaste = { service.clipboardService.forceAwaitPaste(it) },
            onSelectMode = view::selectMode,
            onApplyDecision = view::applyModeDecision,
            onStatus = view::setStatus,
            onSystemMessage = view::addSystemBubble,
            onRefresh = ::render
        )
    }

    private val runtimeCoordinator: ChatRuntimeCoordinator by lazy {
        ChatRuntimeCoordinator(
            streamHub = service.agentStreamHub,
            activeSessionId = { chatTreeService.getActiveSession().id },
            onActiveEvent = view::onLiveEvent,
            onRateLimit = view::onRateLimit,
            startUsagePolling = ::refreshUsagePolling,
            stopUsagePolling = ::stopUsagePolling
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
            onRender = ::render,
            onUpdateBreadcrumb = {
                val session = chatTreeService.getActiveSession()
                view.updateBreadcrumb(chatTreeService.getSessionPath(session.id))
            },
            onUpdateTokenDisplay = {
                view.updateTokenDisplay(
                    chatTreeService.getActiveSession().tokenUsage.formatDisplay()
                )
            },
            onRegisterElementPaths = ::registerElementPaths,
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

    fun initialize() {
        sessionUiCoordinator.loadCurrentSession()
        modeCoordinator.initialize()
        runtimeCoordinator.start()
        view.setAutoApproveToggle(
            isOn = { messageController.isAllowAllApprovals() },
            onToggle = { messageController.setAllowAllApprovals(it) }
        )
        view.setAutonomyLimit(
            current = { ApprovalPolicySettings.getInstance(project).loadAutonomousIterations() },
            onChange = { ApprovalPolicySettings.getInstance(project).saveAutonomousIterations(it) }
        )
    }

    fun refreshHeader() {
        render()
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

    private fun render() {
        val state = buildState()
        modeCoordinator.applyUi(state.mode, state.clipboardStatus)
        view.render(state)
        environmentActions.refreshToolWindowState()
    }

    private fun buildState(): ChatPanelState = stateFactory.build()

    private fun registerElementPaths(
        modifications: List<com.maxvibes.domain.model.modification.ModificationResult>
    ) {
        ChatNavigationHelper.registerElementPaths(modifications, elementNavRegistry)
    }

    override fun dispose() {
        runCatching { runtimeCoordinator.dispose() }
        runCatching { view.disposeView() }
    }

    /**
     * The remote session reference is provider-specific, so a resume id from the previous
     * agent must not survive the switch — the next turn starts a fresh agent session.
     */
    private fun switchAgent(provider: CodingAgentProvider) {
        MaxVibesLogger.info(
            "ChatPanelComposition",
            "agent switch requested",
            mapOf(
                "chosen" to provider.name,
                "persisted" to settings.codingAgentProvider
            )
        )
        service.abortClaudeCode()
        val session = chatTreeService.getActiveSession()
        chatTreeService.saveSession(
            session.copy(agentCliSession = null, claudeCodeSessionId = null)
        )
        refreshUsagePolling()
        val name = CodingAgentCapabilities.of(provider).displayName
        view.addSystemBubble("Coding agent switched to $name — the next message starts a fresh agent session.")
        render()
    }
    private var usagePoller: SubscriptionUsagePoller? = null
    private var lastUsageProvider: CodingAgentProvider? = null

    private fun refreshUsagePolling() {
        val provider = AgentCliSettingsAdapter(settings).provider
        val supported = CodingAgentCapabilities.of(provider).supportsSubscriptionUsage
        view.setUsageSupported(supported)
        if (provider != lastUsageProvider) {
            lastUsageProvider = provider
            view.resetUsage()
        }
        if (!supported || provider != CodingAgentProvider.CLAUDE_CODE) {
            stopUsagePolling()
            return
        }
        if (usagePoller == null) {
            val cache = ClaudeUsageSnapshotCache()
            cache.load()?.let { cached ->
                MaxVibesLogger.debug(
                    "ChatPanelComposition",
                    "restored Claude usage snapshot",
                    mapOf("windows" to cached.windows.size)
                )
                view.onUsage(cached)
            }
            usagePoller = SubscriptionUsagePoller(
                port = ClaudeOAuthUsageAdapter(),
                onUsage = { usage ->
                    cache.save(usage)
                    view.onUsage(usage)
                }
            ).also { it.start() }
        }
    }
    private fun stopUsagePolling() {
        usagePoller?.stop()
        usagePoller = null
    }
}
