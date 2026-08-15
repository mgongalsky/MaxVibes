package com.maxvibes.plugin.ui

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.maxvibes.application.port.output.AgentStreamEvent
import com.maxvibes.application.port.output.SubscriptionUsage
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.domain.model.planning.PlanStepStatus
import com.maxvibes.plugin.settings.MaxVibesSettingsConfigurable
import com.maxvibes.plugin.settings.VoiceTranscriptionSettings
import com.maxvibes.plugin.voice.VoiceInputCoordinator
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants
import com.maxvibes.domain.model.chat.CodingAgentProvider

data class ChatPanelViewActions(
    val onNavigateToPath: (String) -> String,
    val onSelectPrompt: (String?) -> Unit,
    val onCreatePrompt: () -> Unit,
    val onEditPrompt: () -> Unit,
    val onDeletePrompt: () -> Unit,
    val onManagePrompts: () -> Unit,
    val onStop: () -> Unit,
    val onTogglePlanStep: (String, PlanStepStatus) -> Unit,
    val onOpenPlanDoc: (String) -> Unit,
    val onModeSelected: (InteractionMode) -> Unit,
    val onAgentSelected: (CodingAgentProvider) -> Unit,
    val onIndicatorAction: (IndicatorAction) -> Unit,
    val onOpenCcLog: () -> Unit,
    val onShowSessions: () -> Unit,
    val onNewChat: () -> Unit,
    val onBranch: () -> Unit,
    val onDeleteChat: () -> Unit,
    val onOpenPrompts: () -> Unit,
    val onContextFiles: () -> Unit,
    val onClaudeInstructions: (JButton) -> Unit,
    val onToggleMaximize: () -> Unit,
    val onToggleWindowed: () -> Unit,
    val onSelectSession: (String) -> Unit,
    val onRenameSession: (String, String) -> Unit,
    val onSend: () -> Unit,
    val onApprove: () -> Unit,
    val onCopyJson: () -> Unit,
    val onAttachTrace: () -> Unit,
    val onClearTrace: () -> Unit,
    val onAttachErrors: () -> Unit,
    val onClearErrors: () -> Unit,
    val onImagePasted: (AttachedImage) -> Unit,
    val onClearImages: () -> Unit,
    val onClearOneShot: () -> Unit
)

/** Swing surface of the chat tool window. */
class ChatPanelView(
    project: Project,
    claudeCliSettings: ClaudeCliSettings,
    actions: ChatPanelViewActions
) : JPanel(BorderLayout()) {
    private val statusLabel = JBLabel("Ready").apply { foreground = JBColor.GRAY }
    private val tokenLabel = JBLabel("").apply {
        foreground = JBColor.GRAY
        font = font.deriveFont(10f)
        horizontalAlignment = SwingConstants.CENTER
    }

    internal val conversationPanel = ConversationPanel(project) { path ->
        setStatus(actions.onNavigateToPath(path))
    }
    internal val limitsBar = LimitsBarPanel()
    internal val specificPromptPanel = SpecificPromptPanel(
        onSelectPrompt = actions.onSelectPrompt,
        onCreatePrompt = actions.onCreatePrompt,
        onEditPrompt = actions.onEditPrompt,
        onDeletePrompt = actions.onDeletePrompt,
        onManagePrompts = actions.onManagePrompts
    )
    internal val claudeCliSettingsPanel = ClaudeCliSettingsPanel(
        settings = claudeCliSettings,
        onStatus = ::setStatus,
        onAgentChanged = actions.onAgentSelected
    )
    internal val liveTurnPanel = LiveTurnPanel(
        onStop = actions.onStop,
        onPartialFlush = { partial, reason ->
            conversationPanel.addSystemBubble("⚠ Turn ended: $reason")
            if (partial.isNotBlank()) conversationPanel.addAssistantBubble(partial)
        }
    )
    internal val planPanel = PlanPanel(
        onToggleStep = actions.onTogglePlanStep,
        onOpenDoc = actions.onOpenPlanDoc
    )
    internal val headerPanel = ChatHeaderPanel(
        onModeSelected = actions.onModeSelected,
        onIndicatorAction = actions.onIndicatorAction,
        onOpenCcLog = actions.onOpenCcLog,
        onShowSessions = actions.onShowSessions,
        onNewChat = actions.onNewChat,
        onBranch = actions.onBranch,
        onDeleteChat = actions.onDeleteChat,
        onOpenPrompts = actions.onOpenPrompts,
        onContextFiles = actions.onContextFiles,
        onClaudeInstructions = actions.onClaudeInstructions,
        onToggleMaximize = actions.onToggleMaximize,
        onToggleWindowed = actions.onToggleWindowed,
        onSelectSession = actions.onSelectSession,
        onRenameSession = actions.onRenameSession
    )
    internal val inputPanel = ChatInputPanel(
        promptBar = buildPromptPanel(),
        usageBar = limitsBar,
        onSend = actions.onSend,
        onApprove = actions.onApprove,
        onCopyJson = actions.onCopyJson,
        onAttachTrace = actions.onAttachTrace,
        onClearTrace = actions.onClearTrace,
        onAttachErrors = actions.onAttachErrors,
        onClearErrors = actions.onClearErrors,
        onImagePasted = actions.onImagePasted,
        onClearImages = actions.onClearImages,
        onClearOneShot = actions.onClearOneShot
    )
    private val voiceCoordinator = VoiceInputCoordinator(
        projectName = project.name,
        configuration = { VoiceTranscriptionSettings.getInstance().configuration() },
        openSettings = {
            ShowSettingsUtil.getInstance().showSettingsDialog(
                project,
                MaxVibesSettingsConfigurable::class.java
            )
        },
        onState = inputPanel::setVoiceState,
        onTranscript = inputPanel::insertTranscript,
        onStatus = ::setStatus
    )

    val transcriptView: SessionTranscriptView = ConversationPanelTranscriptView(conversationPanel)

    init {
        inputPanel.setVoiceToggleAction { voiceCoordinator.toggle() }
        setupUI()
    }

    fun render(state: ChatPanelState) {
        headerPanel.updateBreadcrumb(state.sessionPath)
        planPanel.update(state.plan)
        claudeCliSettingsPanel.setClaudeCodeVisible(state.mode == InteractionMode.CLAUDE_CODE)
        inputPanel.updateIndicators(state.attachedTrace, state.attachedErrors)
        tokenLabel.text = state.currentSession?.tokenUsage?.formatDisplay().orEmpty()
        headerPanel.updateContextCount(state.contextFilesCount)
        specificPromptPanel.render(
            availablePrompts = state.availablePrompts,
            selectedPromptName = state.selectedSpecificPromptName
        )
        inputPanel.applyApproveState(state.claudeCodeApproveVisible)
    }

    fun applyModeDecision(decision: ModeUiDecision) {
        headerPanel.applyModeDecision(decision)
        inputPanel.applyModeDecision(decision)
    }

    fun selectMode(mode: InteractionMode) {
        headerPanel.selectMode(mode)
    }

    fun updateBreadcrumb(path: List<ChatSession>) {
        headerPanel.updateBreadcrumb(path)
    }

    fun updateToolWindowIcons(maximized: Boolean, floating: Boolean) {
        headerPanel.updateToolWindowIcons(maximized, floating)
    }

    fun updateTokenDisplay(text: String) {
        tokenLabel.text = text
    }

    fun setStatus(text: String) {
        statusLabel.text = text
    }

    fun takeSubmission(): InputSubmission? = inputPanel.takeSubmission()

    fun prefill(prefill: EditorPrefill) {
        inputPanel.prefill(prefill.text, prefill.append)
    }

    fun onUsage(usage: SubscriptionUsage) {
        if (!usageSupported) return
        limitsBar.onUsage(usage)
    }

    fun onRateLimit(event: AgentStreamEvent.RateLimitUpdate) {
        if (!usageSupported) return
        limitsBar.onRateLimit(event)
    }

    fun onLiveEvent(event: AgentStreamEvent) {
        liveTurnPanel.onEvent(event)
    }

    fun addSystemBubble(text: String) {
        conversationPanel.addSystemBubble(text)
    }

    fun disposeView() {
        voiceCoordinator.close()
        liveTurnPanel.dispose()
    }

    private fun buildPromptPanel(): JPanel =
        JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            background = JBColor.background()
            add(claudeCliSettingsPanel)
            add(specificPromptPanel)
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
                add(tokenLabel.apply { horizontalAlignment = SwingConstants.LEFT }, BorderLayout.WEST)
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

    /** False for agents without a subscription-usage API; keeps stale numbers off the screen. */
    private var usageSupported = true
    fun setUsageSupported(supported: Boolean) {
        usageSupported = supported
        if (!supported) limitsBar.isVisible = false
    }
}
