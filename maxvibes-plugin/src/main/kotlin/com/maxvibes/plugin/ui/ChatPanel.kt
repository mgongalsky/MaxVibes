package com.maxvibes.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.VcsConfiguration
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.application.service.AgentStreamHub
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.plugin.service.MaxVibesLogger
import com.maxvibes.plugin.service.MaxVibesService
import com.maxvibes.plugin.service.PromptService
import com.maxvibes.plugin.settings.MaxVibesSettings
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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

    private val inputArea = JBTextArea(3, 40).apply {
        lineWrap = true; wrapStyleWord = true; border = JBUI.Borders.empty(8)
    }

    private val sendButton = JButton("Send").apply { toolTipText = "Send message (Ctrl+Enter)" }

    /**
     * Approve button: visible only in [InteractionMode.CLAUDE_CODE] when the active
     * session is in [ClipboardSessionStatus.AWAITING_APPROVE]. Clicking it gathers
     * the files the LLM requested and sends a follow-up to the same Claude Code process.
     */
    private val approveButton = JButton("\u2705 Approve").apply {
        toolTipText = "Approve & gather requested files (Claude Code)"
        isVisible = false
        // Amber highlight so the button is unmissable the moment it pops up.
        // Client properties are the platform way to recolor buttons (Darcula's
        // ButtonUI ignores plain setBackground); JBColor adapts light/dark.
        foreground = JBColor(java.awt.Color(0x3E2C00), java.awt.Color(0x241C00))
        putClientProperty("JButton.backgroundColor", JBColor(java.awt.Color(0xF5C518), java.awt.Color(0xD4AC0D)))
        putClientProperty("JButton.borderColor", JBColor(java.awt.Color(0xC9A227), java.awt.Color(0x9A7D0A)))
    }

    private val modeComboBox = ComboBox<ModeItem>().apply {
        MaxVibesSettings.INTERACTION_MODES.forEach { (id, label) -> addItem(ModeItem(id, label)) }
        toolTipText = "Interaction mode"
    }

    private val dryRunCheckbox = JBCheckBox("Dry run").apply { toolTipText = "Show plan without applying changes" }
    private val planOnlyCheckbox = JBCheckBox("\uD83D\uDCAC Plan").apply { toolTipText = "Plan-only mode" }

    /**
     * "Add History" checkbox: when checked, the list of previously gathered file paths
     * is included in the next Clipboard request (paths only, no content).
     *
     * Useful when starting a fresh LLM chat so the model knows which files were already
     * in context and can re-request what it needs via [requestedFiles].
     *
     * Visible only in Clipboard mode; auto-resets to unchecked after each send (one-shot).
     */
    private val addHistoryCheckbox = JBCheckBox("Add History").apply {
        toolTipText = "Share gathered file list with LLM (use when starting a new LLM chat)"
        isVisible = false
    }
    private val copyJsonButton =
        JButton("\uD83D\uDCCB Copy JSON").apply { toolTipText = "Re-copy last generated JSON"; isVisible = false }

    private val attachErrorsButton = JButton("\uD83D\uDC1E Errors").apply {
        toolTipText = "Attach IDE errors from open files"; font = font.deriveFont(11f)
    }
    private val errorsIndicator = JBLabel("").apply {
        foreground = JBColor(Color(0xD32F2F), Color(0xEF5350)); font = font.deriveFont(Font.BOLD, 11f); isVisible =
        false
    }
    private val clearErrorsButton = JButton("\u2715").apply {
        toolTipText = "Remove attached errors"; font = font.deriveFont(9f); preferredSize =
        Dimension(20, 20); isVisible = false
    }
    private val attachTraceButton = JButton("\uD83D\uDCCE Trace").apply {
        toolTipText = "Paste error/stacktrace/logs (Ctrl+Shift+V)"; font = font.deriveFont(11f)
    }
    private val traceIndicator = JBLabel("").apply {
        foreground = JBColor(Color(0xFF9800), Color(0xFFB74D)); font = font.deriveFont(Font.BOLD, 11f); isVisible =
        false
    }
    private val clearTraceButton = JButton("\u2715").apply {
        toolTipText = "Remove attached trace"; font = font.deriveFont(9f); preferredSize =
        Dimension(20, 20); isVisible = false
    }
    private val attachmentsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        background = JBColor.background()
        isVisible = false
    }

    private val statusLabel = JBLabel("Ready").apply { foreground = JBColor.GRAY }
    private val tokenLabel = JBLabel("").apply {
        foreground = JBColor.GRAY
        font = font.deriveFont(10f)
        horizontalAlignment = SwingConstants.CENTER
    }
    private val modeIndicator = JBLabel("").apply {
        foreground = JBColor(Color(0x2196F3), Color(0x64B5F6)); font = font.deriveFont(Font.BOLD, 11f); isVisible =
        false
    }

    /**
     * Clickable link to the per-dialog Claude Code transcript
     * (`.maxvibes/logs/claude-code/<chatSessionId>.log`). Visible only in
     * [InteractionMode.CLAUDE_CODE]; click opens the file in the editor
     * via [openClaudeCodeLog].
     */
    private val ccLogLink = JBLabel("\uD83D\uDCC4 CC log").apply {
        foreground = JBColor(Color(0x2196F3), Color(0x64B5F6))
        font = font.deriveFont(11f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Open Claude Code dialog transcript (re-click to refresh)"
        isVisible = false
    }

    /**
     * Subscription usage row (Set 9): 5h and 7d bars mounted under the send-button row.
     * Fed from [streamListener] via [updateLimitsChip]; hidden until the first
     * rate_limit_event. All rendering, colors and thresholds live in [LimitsBarPanel].
     */
    private val limitsBar = LimitsBarPanel()

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

    private val rateLimits =
        java.util.concurrent.ConcurrentHashMap<String, com.maxvibes.application.port.output.AgentStreamEvent.RateLimitUpdate>()

    private fun updateLimitsChip(e: com.maxvibes.application.port.output.AgentStreamEvent.RateLimitUpdate) {
        MaxVibesLogger.info(
            "ChatPanel",
            "limits event",
            mapOf("kind" to e.kind, "pct" to (e.utilizationPct ?: -1), "status" to e.status)
        )
        limitsBar.onRateLimit(e)
    }

    private fun fmtReset(epochSec: Long?): String? = epochSec?.let {
        val t = java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault())
        val pattern = if (t.toLocalDate() == java.time.LocalDate.now()) "HH:mm" else "EEE HH:mm"
        t.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
    }

    private fun limitsColor(worstPct: Int, worstStatus: String): JBColor? = when {
        worstStatus == "rejected" || worstStatus == "exceeded" || worstPct >= 95 ->
            JBColor(java.awt.Color(0xC0392B), java.awt.Color(0xE74C3C))
        worstStatus == "allowed_warning" || worstPct >= 80 ->
            JBColor(java.awt.Color(0xCA6F1E), java.awt.Color(0xE67E22))
        worstPct >= 60 -> JBColor(java.awt.Color(0xB7950B), java.awt.Color(0xF1C40F))
        else -> null // theme default
    }

    private val breadcrumbPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { background = JBColor.background() }

    private val sessionsButton =
        JButton("\uD83D\uDCC2 Sessions").apply { font = font.deriveFont(11f); isFocusPainted = false }
    private val branchButton = JButton("\u2442 Branch").apply { font = font.deriveFont(11f); isFocusPainted = false }
    private val newChatButton = JButton("+ New").apply { font = font.deriveFont(11f); isFocusPainted = false }
    private val deleteButton = JButton("\uD83D\uDDD1 Del").apply { font = font.deriveFont(11f); isFocusPainted = false }
    private val promptsButton = JButton("\u2699").apply { toolTipText = "Edit prompts"; font = font.deriveFont(11f) }
    private val contextFilesButton =
        JButton("\uD83D\uDCCE Ctx").apply { font = font.deriveFont(11f); isFocusPainted = false }
    private val claudeInstrButton =
        JButton("\uD83D\uDCCB").apply { font = font.deriveFont(11f); isFocusPainted = false }
    private val maximizeButton = JButton(AllIcons.General.ExpandComponent).apply {
        toolTipText = "Maximize / Restore"; font = font.deriveFont(11f); isFocusPainted = false
    }
    private val windowedButton = JButton(AllIcons.Actions.MoveToWindow).apply {
        toolTipText = "Floating Mode / Dock"; font = font.deriveFont(11f); isFocusPainted = false
    }

    /** Single dropdown button showing the active specific prompt name. */
    private val promptNameLabel = JBLabel("") // unused placeholder \u2014 kept for binary compat

    /** Button to create a new specific prompt file. */
    private val newPromptButton = JButton("+").apply {
        font = font.deriveFont(Font.BOLD, 12f)
        toolTipText = "Create new task prompt file in .maxvibes/prompts/specific/"
        preferredSize = Dimension(26, 22)
        isFocusPainted = false
    }

    /** Button to edit the currently selected specific prompt file. */
    private val editPromptButton = JButton("\u270F").apply {
        font = font.deriveFont(12f)
        toolTipText = "Open current prompt file for editing"
        preferredSize = Dimension(26, 22)
        isFocusPainted = false
        isEnabled = false
    }

    /** Button to delete the currently selected specific prompt file. */
    private val deletePromptButton = JButton("\u2212").apply {
        font = font.deriveFont(Font.BOLD, 13f)
        toolTipText = "Delete current prompt file"
        preferredSize = Dimension(26, 22)
        isFocusPainted = false
        isEnabled = false
    }

    /** Button to open the skill/specific prompt manager. */
    private val manageSpecificButton = JButton("\u2699").apply {
        font = font.deriveFont(12f)
        toolTipText = "Manage skills & prompts"
        preferredSize = Dimension(26, 22)
        isFocusPainted = false
        addActionListener {
            SkillManagerDialog(project, service.specificPromptRepository).show()
            render(buildState())
        }
    }

    /** Single dropdown button showing the active specific prompt. */
    private val promptSelectButton = JButton("Just Code \u25BE").apply {
        font = font.deriveFont(11f)
        toolTipText = "Select task prompt"
        preferredSize = Dimension(200, 22)
        isFocusPainted = false
    }

    /**
     * Claude Code CLI model selector. Writes to settings.claudeCodeModel; the
     * adapter's SpawnConfig snapshot check picks the change up on the next send
     * and respawns the process. Visible only in CLAUDE_CODE mode.
     */
    private val claudeModelCombo = ComboBox<String>().apply {
        isEditable = true
        addItem("Auto")
        addItem("haiku")
        addItem("sonnet")
        addItem("opus")
        preferredSize = Dimension(120, 22)
        font = font.deriveFont(11f)
        toolTipText =
            "Claude Code CLI model. Auto = CLI default; type a full model name for anything else. Applies on next send."
        isVisible = false

        addActionListener { commitModelComboToSettings() }
        editor.editorComponent.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent?) = commitModelComboToSettings()
        })
        addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent?) = syncModelComboFromSettings()
            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent?) {}
            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent?) {}
        })
    }

    /** Guards claudeModelCombo listeners during programmatic sync. */
    private var suppressModelCombo = false

    /** Re-reads settings.claudeCodeModel into the combo without firing commit. */
    private fun syncModelComboFromSettings() {
        suppressModelCombo = true
        try {
            val value = settings.claudeCodeModel.trim()
            claudeModelCombo.selectedItem = if (value.isEmpty()) "Auto" else value
        } finally {
            suppressModelCombo = false
        }
    }

    /** Commits the combo editor value into settings.claudeCodeModel (Auto becomes blank). */
    private fun commitModelComboToSettings() {
        if (suppressModelCombo) return
        val raw = (claudeModelCombo.editor.item ?: claudeModelCombo.selectedItem)
            ?.toString()
            ?.trim()
            ?: return
        val value = if (raw.isEmpty() || raw.equals("Auto", ignoreCase = true)) "" else raw
        if (settings.claudeCodeModel == value) return
        settings.claudeCodeModel = value
        statusLabel.text = "CLI model: ${value.ifEmpty { "Auto" }} — applies on next send"
    }

    /**
     * Claude Code reasoning-effort selector (CLAUDE_CODE_EFFORT_LEVEL). Same
     * lifecycle as the model selector: writes to settings, adapter respawns on
     * next send. Unsupported levels clamp down; pre-4.6 models ignore effort.
     */
    private val claudeEffortCombo = ComboBox(arrayOf("Auto", "low", "medium", "high", "xhigh", "max")).apply {
        preferredSize = Dimension(90, 22)
        font = font.deriveFont(11f)
        toolTipText = "Reasoning effort (Claude Code). Auto = model default. Applies on next send."
        isVisible = false

        addActionListener { commitEffortComboToSettings() }
        addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent?) =
                syncEffortComboFromSettings()

            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent?) {}
            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent?) {}
        })
    }

    /** Guards claudeEffortCombo listener during programmatic sync. */
    private var suppressEffortCombo = false
    private fun syncEffortComboFromSettings() {
        suppressEffortCombo = true
        try {
            claudeEffortCombo.selectedItem = settings.claudeCodeEffortLevel.ifBlank { "Auto" }
        } finally {
            suppressEffortCombo = false
        }
    }

    private fun commitEffortComboToSettings() {
        if (suppressEffortCombo) return
        val raw = claudeEffortCombo.selectedItem as? String ?: return
        val value = if (raw == "Auto") "" else raw
        if (settings.claudeCodeEffortLevel == value) return
        settings.claudeCodeEffortLevel = value
        statusLabel.text = "CLI effort: ${value.ifEmpty { "Auto" }} — applies on next send"
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

    /**
     * Pinned planner panel: collapsible checklist of the active session's TaskPlan.
     * Mounted above the conversation in [setupUI]; fed snapshots via [render].
     * Manual checkbox toggles persist through ChatTreeService.setPlanStepStatus and
     * reach the model with the next request (currentPlan field); doc links open
     * PLAN.md / STEP_N.md via [openPlanDoc].
     */
    private val planPanel = PlanPanel(
        onToggleStep = { stepId, newStatus ->
            chatTreeService.setPlanStepStatus(chatTreeService.getActiveSession().id, stepId, newStatus)
            render(buildState())
        },
        onOpenDoc = { docPath -> openPlanDoc(docPath) }
    )

    private val service: MaxVibesService by lazy { MaxVibesService.getInstance(project) }
    private val chatTreeService get() = service.chatTreeService
    private val promptService: PromptService by lazy { PromptService.getInstance(project) }
    private val settings: MaxVibesSettings by lazy { MaxVibesSettings.getInstance() }
    private val initialModelComboSync: Unit = run {
        syncModelComboFromSettings()
        syncEffortComboFromSettings()
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

    // ConversationRenderer handles all message filtering and formatting for display.
    // Extracted here to keep ChatPanel free from knowledge of the internal message storage format.
    private val conversationRenderer = ConversationRenderer()

    /**
     * Holds the active force-activate MouseListener on [modeIndicator] so it can be
     * removed before re-attaching on the next [updateModeUI] call, preventing listener accumulation.
     */
    private var forceActivateListener: MouseAdapter? = null

    private val messageController: ChatMessageController by lazy {
        ChatMessageController(project, service, this)
    }

    init {
        setupUI(); setupListeners()
        loadCurrentSession()
        modeManager.syncFromSettings()
        syncComboBoxToMode()
        service.agentStreamHub.addListener(streamListener)
        // Tie our teardown to the tool window's lifetime \u2014 when the tool window
        // closes, IntelliJ disposes its children, which triggers our dispose().
        Disposer.register(toolWindow.disposable, this)
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
        inputArea.text = text
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
        inputArea.isEnabled = enabled
        sendButton.isEnabled = enabled
        approveButton.isEnabled = enabled
        dryRunCheckbox.isEnabled = enabled
        planOnlyCheckbox.isEnabled = enabled
        addHistoryCheckbox.isEnabled = enabled
        copyJsonButton.isEnabled = enabled
        attachTraceButton.isEnabled = enabled
        clearTraceButton.isEnabled = enabled
        attachErrorsButton.isEnabled = enabled
        clearErrorsButton.isEnabled = enabled
        promptsButton.isEnabled = enabled
        modeComboBox.isEnabled = enabled
        claudeModelCombo.isEnabled = enabled
        claudeEffortCombo.isEnabled = enabled
        sessionsButton.isEnabled = enabled
        branchButton.isEnabled = enabled
        newChatButton.isEnabled = enabled
        deleteButton.isEnabled = enabled
        contextFilesButton.isEnabled = enabled
        claudeInstrButton.isEnabled = enabled
        maximizeButton.isEnabled = enabled
        promptSelectButton.isEnabled = enabled
        newPromptButton.isEnabled = enabled
        editPromptButton.isEnabled = enabled
        deletePromptButton.isEnabled = enabled
        manageSpecificButton.isEnabled = enabled
    }

    override fun setStatus(text: String) {
        statusLabel.text = text
    }

    override fun updateModeIndicator() {
        render(buildState())
    }

    override fun updateBreadcrumb() {
        breadcrumbPanel.removeAll()
        val session = chatTreeService.getActiveSession()
        val path = chatTreeService.getSessionPath(session.id)

        for ((i, s) in path.withIndex()) {
            val isLast = i == path.size - 1
            if (i > 0) {
                breadcrumbPanel.add(JBLabel(" \u203A ").apply {
                    foreground = JBColor.GRAY; font = font.deriveFont(11f)
                })
            }
            if (isLast) {
                val titleText = s.title.take(30) + if (s.title.length > 30) ".." else ""
                val label = JBLabel(titleText).apply {
                    font = font.deriveFont(Font.BOLD, 11f); foreground = JBColor.foreground()
                    border = JBUI.Borders.empty(2, 3); cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
                    toolTipText = "Click to rename"
                }
                label.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        startInlineRename(label, s.id, s.title)
                    }
                })
                breadcrumbPanel.add(label)
            } else {
                val label = JBLabel(s.title.take(20) + if (s.title.length > 20) ".." else "").apply {
                    font = font.deriveFont(11f)
                    foreground = JBColor(Color(0x2196F3), Color(0x64B5F6))
                    border = JBUI.Borders.empty(2, 3); cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }
                val sid = s.id
                label.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        chatTreeService.setActiveSession(sid); loadCurrentSession()
                    }
                })
                breadcrumbPanel.add(label)
            }
        }
        breadcrumbPanel.revalidate(); breadcrumbPanel.repaint()
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
        try {
            VcsConfiguration.getInstance(project).saveCommitMessage(message)

            fun tryInject(component: java.awt.Component): Boolean {
                val dataContext = DataManager.getInstance().getDataContext(component)
                val control = dataContext.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return false
                return try {
                    control.javaClass.getMethod("setCommitMessage", String::class.java).invoke(control, message)
                    true
                } catch (_: Exception) {
                    false
                }
            }

            val frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)
            if (frame != null && tryInject(frame)) {
                MaxVibesLogger.info("ChatPanel", "setCommitMessage: injected via frame", mapOf("len" to message.length))
                return
            }

            val commitTw = ToolWindowManager.getInstance(project).getToolWindow("Commit")
            val contentComponent = commitTw?.takeIf { it.isVisible }?.contentManager?.selectedContent?.component
            if (contentComponent != null && tryInject(contentComponent)) {
                MaxVibesLogger.info(
                    "ChatPanel",
                    "setCommitMessage: injected via Commit tool window",
                    mapOf("len" to message.length)
                )
                return
            }

            MaxVibesLogger.info(
                "ChatPanel",
                "setCommitMessage: saved to VCS history (commit UI not open)",
                mapOf("len" to message.length)
            )
        } catch (e: Exception) {
            MaxVibesLogger.error("ChatPanel", "setCommitMessage failed", e)
        }
    }

    override fun setPlanOnlyMode(enabled: Boolean) {
        planOnlyCheckbox.isSelected = enabled
    }

    fun refreshHeader() {
        updateBreadcrumb(); updateModeIndicator(); updateContextIndicator(); updateToolWindowIcons()
    }

    fun loadCurrentSession() {
        val session = chatTreeService.getActiveSession()
        conversationPanel.clearMessages()
        elementNavRegistry.clear()
        updateBreadcrumb(); updateModeIndicator(); updateContextIndicator(); updateTokenDisplay(); updateToolWindowIcons()

        if (session.messages.isEmpty()) {
            showWelcome()
        } else {
            val path = chatTreeService.getSessionPath(session.id)
            if (path.size > 1) {
                val chain = path.dropLast(1).joinToString(" \u203A ") { it.title.take(25) }
                conversationPanel.addSystemBubble("\u2514 Branch of: $chain")
            }

            conversationRenderer.render(session.messages).forEach { msg ->
                when (msg.role) {
                    MessageRole.USER -> conversationPanel.addUserBubble(msg.content)

                    MessageRole.ASSISTANT -> {
                        val persistedMods = msg.appliedModificationPaths.mapNotNull { pathStr ->
                            runCatching {
                                val elemPath = com.maxvibes.domain.model.code.ElementPath(pathStr)
                                ModificationResult.Success(
                                    modification = com.maxvibes.domain.model.modification.Modification.ReplaceElement(
                                        targetPath = elemPath,
                                        newContent = ""
                                    ),
                                    affectedPath = elemPath,
                                    resultContent = null
                                )
                            }.getOrNull()
                        }
                        conversationPanel.addAssistantBubble(
                            text = msg.content,
                            tokenInfo = msg.tokenInfo,
                            modifications = persistedMods,
                            metaFiles = msg.attachedFiles,
                            reasoning = msg.reasoning,
                            requestedViews = msg.requestedViews,
                            appliedModifications = msg.appliedModifications
                        )
                        registerElementPaths(persistedMods)
                    }

                    MessageRole.SYSTEM -> conversationPanel.addSystemBubble(msg.content)
                }
            }
        }
        render(buildState())
    }

    private fun setupUI() {
        border = JBUI.Borders.empty()
        background = JBColor.background()

        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = JBColor.background()
            border = JBUI.Borders.compound(
                JBUI.Borders.empty(4, 8),
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            )

            val controlRow = JPanel(BorderLayout()).apply {
                background = JBColor.background()
                maximumSize = Dimension(Int.MAX_VALUE, 30)
                val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    background = JBColor.background()
                    add(modeComboBox.apply {
                        preferredSize = Dimension(180, 24)
                        font = font.deriveFont(11f)
                    })
                    add(modeIndicator)
                    add(ccLogLink)
                }
                val right = JPanel(FlowLayout(FlowLayout.RIGHT, 3, 0)).apply {
                    background = JBColor.background()
                    add(contextFilesButton.apply { preferredSize = Dimension(56, 24) })
                    add(claudeInstrButton.apply { preferredSize = Dimension(26, 24) })
                    add(windowedButton.apply { preferredSize = Dimension(26, 24) })
                    add(maximizeButton.apply { preferredSize = Dimension(26, 24) })
                    add(promptsButton.apply { preferredSize = Dimension(26, 24) })
                }
                add(left, BorderLayout.WEST)
                add(right, BorderLayout.EAST)
            }

            val navRow = JPanel(BorderLayout(4, 0)).apply {
                background = JBColor.background()
                maximumSize = Dimension(Int.MAX_VALUE, 28)
                border = JBUI.Borders.empty(2, 0, 0, 0)
                val scroll = JScrollPane(breadcrumbPanel).apply {
                    border = JBUI.Borders.empty()
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                    background = JBColor.background()
                    viewport.background = JBColor.background()
                }
                val navButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 3, 0)).apply {
                    background = JBColor.background()
                    add(newChatButton.apply { preferredSize = Dimension(52, 22) })
                    add(branchButton.apply { preferredSize = Dimension(64, 22) })
                    add(deleteButton.apply { preferredSize = Dimension(52, 22) })
                    add(sessionsButton.apply { preferredSize = Dimension(86, 22) })
                }
                add(scroll, BorderLayout.CENTER)
                add(navButtons, BorderLayout.EAST)
            }

            add(controlRow)
            add(navRow)
        }

        val traceBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            background = JBColor.background()
            border = JBUI.Borders.empty(2, 8, 0, 8)
            add(traceIndicator)
            add(clearTraceButton)
            add(errorsIndicator)
            add(clearErrorsButton)
            add(attachmentsPanel)
            isVisible = false
        }

        val inputPanel = JPanel(BorderLayout(5, 4)).apply {
            border = JBUI.Borders.empty(4, 8, 8, 8)
            background = JBColor.background()
            add(traceBar, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.customLine(JBColor.border(), 1)
                // Viewport wrapper: without it JBTextArea grows unbounded with content,
                // the SOUTH block clips, and caret navigation above the fold misbehaves.
                add(com.intellij.ui.components.JBScrollPane(inputArea).apply {
                    border = JBUI.Borders.empty()
                    preferredSize = Dimension(10, 96)
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                }, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                background = JBColor.background()
                add(buildPromptPanel(), BorderLayout.NORTH)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                    background = JBColor.background()
                    add(attachErrorsButton.apply { preferredSize = Dimension(85, 26) })
                    add(attachTraceButton.apply { preferredSize = Dimension(80, 26) })
                    add(addHistoryCheckbox)
                    add(planOnlyCheckbox)
                    add(dryRunCheckbox)
                    add(copyJsonButton)
                    add(approveButton)
                    add(sendButton)
                }, BorderLayout.CENTER)
                // Set 9: subscription usage row (5h / 7d bars) - full-width line under the buttons.
                add(limitsBar, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }

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

        // Planner panel pinned above the conversation: both live inside the first
        // splitter section so the plan scrolls with nothing — it stays fixed while
        // only the conversation below it scrolls.
        val conversationWithPlan = JPanel(BorderLayout()).apply {
            background = JBColor.background()
            add(planPanel, BorderLayout.NORTH)
            add(conversationPanel, BorderLayout.CENTER)
        }

        // Conversation on top, live turn block below, separated by a draggable one-pixel
        // splitter. The proportion is persisted via PropertiesComponent, so a size dragged
        // once survives IDE restarts. While the live panel is invisible between turns,
        // the conversation takes the full available height.
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

    /** Paste handler: a clipboard image becomes an attachment, anything else pastes as text. */
    private fun pasteImageOrText() {
        val img = ImageAttachments.fromClipboard()
        if (img != null) messageController.attachImage(img) else inputArea.paste()
    }

    private fun setupListeners() {
        fun attachFromClipboard() {
            val content = try {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
            } catch (e: Exception) {
                statusLabel.text = "Clipboard error: ${e.message}"; return
            }
            if (content.isNullOrBlank()) {
                statusLabel.text = "Clipboard is empty"; return
            }
            messageController.attachTrace(content)
        }

        sendButton.addActionListener { sendMessage() }
        approveButton.addActionListener { messageController.approve() }
        ccLogLink.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                openClaudeCodeLog()
            }
        })
        copyJsonButton.addActionListener { messageController.redoClipboardJson() }
        sessionsButton.addActionListener { onShowSessions() }
        promptSelectButton.addActionListener { showPromptSelectionPopup() }
        newPromptButton.addActionListener { createNewPromptFile() }
        editPromptButton.addActionListener { editCurrentPromptFile() }
        deletePromptButton.addActionListener { deleteCurrentPromptFile() }

        newChatButton.addActionListener {
            messageController.clearAttachmentsAfterSend()
            messageController.createNewSession()
            statusLabel.text = "New dialog"
        }

        branchButton.addActionListener {
            val active = chatTreeService.getActiveSession()
            val title = JOptionPane.showInputDialog(
                this, "Name for the new branch:", "New Branch", JOptionPane.PLAIN_MESSAGE,
                null, null, "Branch: ${active.title.take(25)}"
            ) as? String ?: return@addActionListener
            messageController.clearAttachmentsAfterSend()
            messageController.branchSession(active.id, title)
            statusLabel.text = "Branch: $title"
        }

        deleteButton.addActionListener { deleteCurrentChat() }
        promptsButton.addActionListener { promptService.openOrCreatePrompts(); statusLabel.text = "Prompts opened" }

        contextFilesButton.addActionListener {
            val result = ChatDialogsHelper.showContextFilesDialog(this, project, chatTreeService)
            if (result != null) {
                chatTreeService.setGlobalContextFiles(result)
                updateContextIndicator(); statusLabel.text = "Context files: ${result.size}"
            }
        }

        claudeInstrButton.addActionListener {
            ChatDialogsHelper.showClaudeInstructionsPopup(project, claudeInstrButton) { statusLabel.text = it }
        }

        maximizeButton.addActionListener {
            val manager = ToolWindowManager.getInstance(project)
            val newState = !manager.isMaximized(toolWindow)
            manager.setMaximized(toolWindow, newState)
            updateToolWindowIcons()
        }

        windowedButton.addActionListener {
            if (toolWindow.type == ToolWindowType.FLOATING || toolWindow.type == ToolWindowType.WINDOWED) {
                toolWindow.setType(ToolWindowType.DOCKED, null)
            } else {
                toolWindow.setType(ToolWindowType.FLOATING, null)
            }
            updateToolWindowIcons()
        }

        attachTraceButton.addActionListener { attachFromClipboard() }
        clearTraceButton.addActionListener { messageController.clearTrace() }
        attachErrorsButton.addActionListener { messageController.fetchIdeErrors() }
        clearErrorsButton.addActionListener { messageController.clearErrors() }

        modeComboBox.addActionListener {
            val selected = modeComboBox.selectedItem as? ModeItem ?: return@addActionListener
            val newMode = try {
                InteractionMode.valueOf(selected.id)
            } catch (_: Exception) {
                return@addActionListener
            }
            if (newMode == modeManager.currentMode) return@addActionListener
            if (modeManager.currentMode == InteractionMode.CLIPBOARD &&
                buildState().clipboardStatus == ClipboardSessionStatus.AWAITING_PASTE
            ) {
                val confirm = JOptionPane.showConfirmDialog(
                    this, "Active clipboard session will be reset. Continue?",
                    "Switch Mode", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
                )
                if (confirm != JOptionPane.YES_OPTION) {
                    syncComboBoxToMode(); return@addActionListener
                }
                val sessionId = chatTreeService.getActiveSession().id
                service.clipboardService.reset(sessionId)
            }
            MaxVibesLogger.info(
                "ChatPanel",
                "switchMode",
                mapOf("from" to modeManager.currentMode.name, "to" to newMode.name)
            )
            modeManager.switchMode(newMode)
            val label = MaxVibesSettings.INTERACTION_MODES.find { it.first == newMode.name }?.second ?: newMode.name
            statusLabel.text = "Mode: $label"
            conversationPanel.addSystemBubble("\u2699\uFE0F Switched to $label")
        }

        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) {
                    sendMessage(); e.consume()
                } else if (e.keyCode == KeyEvent.VK_V && e.isControlDown && e.isShiftDown) {
                    attachFromClipboard(); e.consume()
                }
            }
        })

        // IntelliJ's action system consumes Ctrl+V ($Paste via CutCopyPasteSupport for
        // Swing text components) BEFORE Swing InputMap bindings, so the interception
        // must be a component-registered action \u2014 those take precedence in the dispatcher.
        val pasteOverride = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = pasteImageOrText()
        }
        pasteOverride.registerCustomShortcutSet(
            ActionManager.getInstance().getAction(IdeActions.ACTION_PASTE).shortcutSet,
            inputArea
        )
        // Belt and suspenders: Swing-level bindings to the same handler for focus states
        // where the event does reach the component directly.
        inputArea.actionMap.put("maxvibes-paste", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = pasteImageOrText()
        })
        inputArea.inputMap.put(javax.swing.KeyStroke.getKeyStroke("ctrl V"), "maxvibes-paste")
        inputArea.inputMap.put(javax.swing.KeyStroke.getKeyStroke("shift INSERT"), "maxvibes-paste")
    }

    private fun sendMessage() {
        val userInput = inputArea.text.trim()
        if (userInput.isBlank()) return
        val addHistory = addHistoryCheckbox.isSelected
        val state = buildState()
        inputArea.text = ""
        addHistoryCheckbox.isSelected = false
        messageController.sendMessage(
            userInput,
            planOnlyCheckbox.isSelected,
            dryRunCheckbox.isSelected,
            modeManager.currentMode,
            addHistory,
            state.selectedSpecificPromptName
        )
    }

    /**
     * Syncs the combo box selection to match [modeManager].currentMode.
     * Called after syncFromSettings to keep UI in sync without triggering the listener.
     */
    private fun syncComboBoxToMode() {
        val mode = modeManager.currentMode
        for (i in 0 until modeComboBox.itemCount) {
            if (modeComboBox.getItemAt(i).id == mode.name) {
                modeComboBox.selectedIndex = i; break
            }
        }
    }

    /**
     * Updates mode-specific UI components based on the provided panel state.
     *
     * Reads [ChatPanelState.clipboardStatus] instead of querying the clipboard service directly.
     * In Clipboard mode, [modeIndicator] is clickable in both AWAITING_PASTE and SESSION_ACTIVE,
     * allowing the user to toggle between the two states manually.
     *
     * The [forceActivateListener] is removed at the top of every call so it never accumulates
     * across renders or leaks across mode switches (e.g. CLIPBOARD \u2192 CLAUDE_CODE).
     */
    private fun updateModeUI(state: ChatPanelState) {
        // Always tear down any previously attached force-activate listener BEFORE the when \u2014
        // the listener is only meaningful in CLIPBOARD's AWAITING_PASTE / SESSION_ACTIVE branches
        // and must not survive a mode switch or a state transition out of those branches.
        forceActivateListener?.let { modeIndicator.removeMouseListener(it) }
        forceActivateListener = null

        // The Claude Code transcript link is meaningful only in CLAUDE_CODE mode \u2014
        // one switch here covers every branch of the when below.
        ccLogLink.isVisible = state.mode == InteractionMode.CLAUDE_CODE

        when (state.mode) {
            InteractionMode.API -> {
                modeIndicator.isVisible = false
                sendButton.text = "Send"
                dryRunCheckbox.isVisible = true
                copyJsonButton.isVisible = false
                addHistoryCheckbox.isVisible = false
            }

            InteractionMode.CLIPBOARD -> {
                addHistoryCheckbox.isVisible = true

                when (state.clipboardStatus) {
                    ClipboardSessionStatus.AWAITING_PASTE -> {
                        modeIndicator.text = "\u23F3 Paste response"
                        modeIndicator.isVisible = true
                        sendButton.text = "Paste"
                        copyJsonButton.isVisible = true

                        modeIndicator.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        modeIndicator.toolTipText = "Click to skip paste and continue dialog"
                        val listener = object : MouseAdapter() {
                            override fun mouseClicked(e: MouseEvent) {
                                val sessionId = chatTreeService.getActiveSession().id
                                service.clipboardService.forceActivate(sessionId)
                                render(buildState())
                            }
                        }
                        forceActivateListener = listener
                        modeIndicator.addMouseListener(listener)
                    }

                    ClipboardSessionStatus.SESSION_ACTIVE -> {
                        modeIndicator.text = "\uD83D\uDCCB Active"
                        modeIndicator.isVisible = true
                        sendButton.text = "Send / Paste"
                        copyJsonButton.isVisible = false

                        modeIndicator.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        modeIndicator.toolTipText = "Click to go back to paste mode"
                        val listener = object : MouseAdapter() {
                            override fun mouseClicked(e: MouseEvent) {
                                val sessionId = chatTreeService.getActiveSession().id
                                service.clipboardService.forceAwaitPaste(sessionId)
                                render(buildState())
                            }
                        }
                        forceActivateListener = listener
                        modeIndicator.addMouseListener(listener)
                    }

                    ClipboardSessionStatus.IDLE -> {
                        modeIndicator.text = "\uD83D\uDCCB"
                        modeIndicator.isVisible = true
                        sendButton.text = "Generate"
                        copyJsonButton.isVisible = false
                        modeIndicator.cursor = Cursor.getDefaultCursor()
                        modeIndicator.toolTipText = null
                    }

                    // Clipboard mode should never see AWAITING_APPROVE (Claude Code-only);
                    // fall back to IDLE-equivalent visuals defensively.
                    ClipboardSessionStatus.AWAITING_APPROVE -> {
                        modeIndicator.text = "\uD83D\uDCCB"
                        modeIndicator.isVisible = true
                        sendButton.text = "Generate"
                        copyJsonButton.isVisible = false
                        modeIndicator.cursor = Cursor.getDefaultCursor()
                        modeIndicator.toolTipText = null
                    }
                }
                dryRunCheckbox.isVisible = false
            }

            InteractionMode.CHEAP_API -> {
                modeIndicator.text = "\uD83D\uDCB0"
                modeIndicator.isVisible = true
                sendButton.text = "Send"
                dryRunCheckbox.isVisible = true
                copyJsonButton.isVisible = false
                addHistoryCheckbox.isVisible = false
            }

            InteractionMode.CLAUDE_CODE -> {
                // Clipboard-specific controls have no meaning in Claude Code mode.
                addHistoryCheckbox.isVisible = false
                copyJsonButton.isVisible = false
                dryRunCheckbox.isVisible = false

                sendButton.text = "Send"
                modeIndicator.cursor = Cursor.getDefaultCursor()
                modeIndicator.toolTipText = null
                modeIndicator.isVisible = true

                modeIndicator.text = when (state.clipboardStatus) {
                    ClipboardSessionStatus.AWAITING_APPROVE -> "\uD83E\uDD16 Awaiting Approve"
                    ClipboardSessionStatus.SESSION_ACTIVE -> "\uD83E\uDD16 Active"
                    ClipboardSessionStatus.IDLE,
                    ClipboardSessionStatus.AWAITING_PASTE -> "\uD83E\uDD16 Claude Code"
                }
            }
        }
    }

    private fun updateIndicators() {
        val trace = messageController.attachedTrace
        val hasTrace = !trace.isNullOrBlank()
        traceIndicator.isVisible = hasTrace
        clearTraceButton.isVisible = hasTrace
        if (hasTrace) traceIndicator.text = "\uD83D\uDCCE Trace: ${trace!!.lines().size}L"

        val errs = messageController.attachedErrors
        val hasErrs = !errs.isNullOrBlank()
        errorsIndicator.isVisible = hasErrs
        clearErrorsButton.isVisible = hasErrs
        if (hasErrs) {
            val count = errs!!.split("File:").size - 1
            errorsIndicator.text = "\uD83D\uDC1E Errors: $count"
        }

        val showBar = hasTrace || hasErrs || attachmentsPanel.isVisible
        val bar = traceIndicator.parent
        bar?.isVisible = showBar
        bar?.revalidate(); bar?.repaint()
    }

    private fun startInlineRename(label: JBLabel, sessionId: String, currentTitle: String) {
        val parent = label.parent ?: return
        val idx = (0 until parent.componentCount).firstOrNull { parent.getComponent(it) === label } ?: return

        val textField = JTextField(currentTitle).apply {
            font = label.font
            preferredSize = Dimension(maxOf(label.preferredSize.width + 40, 120), label.preferredSize.height + 2)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor(Color(0x2196F3), Color(0x64B5F6)), 1), JBUI.Borders.empty(1, 3)
            )
            selectAll()
        }

        var committed = false
        fun commitRename() {
            if (committed) return; committed = true
            val newTitle = textField.text.trim()
            if (newTitle.isNotBlank() && newTitle != currentTitle) {
                messageController.renameSession(sessionId, newTitle)
                statusLabel.text = "Renamed to \"$newTitle\""
            } else {
                updateBreadcrumb()
            }
        }

        fun cancelRename() {
            if (committed) return; committed = true; updateBreadcrumb()
        }

        textField.addActionListener { commitRename() }
        textField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    cancelRename(); e.consume()
                }
            }
        })
        textField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent?) {
                commitRename()
            }
        })

        parent.remove(idx); parent.add(textField, idx); parent.revalidate(); parent.repaint()
        textField.requestFocusInWindow()
    }

    private fun deleteCurrentChat() {
        val session = chatTreeService.getActiveSession()
        val childCount = chatTreeService.getChildCount(session.id)
        val msg =
            if (childCount > 0) "Delete \"${session.title}\"?\n$childCount branch(es) will be re-attached to parent."
            else "Delete \"${session.title}\"?"
        val confirm = JOptionPane.showConfirmDialog(
            this,
            msg,
            "Delete Chat",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (confirm != JOptionPane.YES_OPTION) return
        messageController.clearAttachmentsAfterSend()
        messageController.deleteCurrentSession(session.id)
        statusLabel.text = "Chat deleted"
    }

    private fun updateContextIndicator() {
        val count = chatTreeService.getGlobalContextFiles().size
        contextFilesButton.text = if (count > 0) "\uD83D\uDCCE Ctx($count)" else "\uD83D\uDCCE Ctx"
    }

    private fun showWelcome() {
        val mode = when (modeManager.currentMode) {
            InteractionMode.API -> "API \u2014 direct LLM calls"
            InteractionMode.CLIPBOARD -> "Clipboard \u2014 paste JSON into Claude/ChatGPT"
            InteractionMode.CHEAP_API -> "Cheap API \u2014 budget model"
            InteractionMode.CLAUDE_CODE -> "Claude Code \u2014 local CLI process"
        }
        val session = chatTreeService.getActiveSession()
        val ctxCount = chatTreeService.getGlobalContextFiles().size
        val lines = mutableListOf("MaxVibes  \u2022  $mode")
        if (session.depth > 0) lines += "\u2514 Branch from: \"${chatTreeService.getParent(session.id)?.title ?: "?\""}"
        if (ctxCount > 0) lines += "\uD83D\uDCCE $ctxCount global context file(s) active"
        lines += "Type your task \u2022 Ctrl+Enter to send"
        lines.forEach { conversationPanel.addSystemBubble(it) }
    }

    private fun updateToolWindowIcons() {
        val manager = ToolWindowManager.getInstance(project)
        maximizeButton.icon =
            if (manager.isMaximized(toolWindow)) AllIcons.General.CollapseComponent else AllIcons.General.ExpandComponent
        val isFloating = toolWindow.type == ToolWindowType.FLOATING || toolWindow.type == ToolWindowType.WINDOWED
        windowedButton.icon = AllIcons.Actions.MoveToWindow
        windowedButton.toolTipText = if (isFloating) "Dock Tool Window" else "Floating Mode"
    }

    /**
     * Opens the per-dialog Claude Code transcript for the active session in the editor.
     *
     * The transcript is appended by an external writer ([com.maxvibes.plugin.claudecode.ClaudeCodeSessionLogWriter]),
     * so the VFS copy may be stale \u2014 `refresh(false, false)` before opening picks up
     * everything written so far. While a send is in flight, re-clicking the link
     * refreshes the editor content again.
     */
    private fun openClaudeCodeLog() {
        val sessionId = chatTreeService.getActiveSession().id
        val path = service.claudeCodeSessionLog.logFilePath(sessionId)
        if (path == null) {
            statusLabel.text = "No Claude Code log for this dialog yet \u2014 send a message first"
            return
        }
        val vFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(java.io.File(path))
        if (vFile == null) {
            statusLabel.text = "Log file not found: $path"
            return
        }
        vFile.refresh(false, false)
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vFile, true)
        statusLabel.text = "Opened Claude Code log"
    }

    /**
     * Opens a plan document (PLAN.md / STEP_N.md) referenced from the planner panel.
     * Paths are project-relative. A missing file reports to the status bar instead of
     * throwing — the LLM may reference docs it has not created yet.
     */
    private fun openPlanDoc(docPath: String) {
        val basePath = project.basePath ?: return
        val vFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(java.io.File(basePath, docPath))
        if (vFile == null) {
            statusLabel.text = "Doc not found: $docPath"
            return
        }
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vFile, true)
        statusLabel.text = "Opened $docPath"
    }

    fun render(state: ChatPanelState) {
        updateBreadcrumb()
        updateModeUI(state)
        planPanel.update(state.plan)
        claudeModelCombo.isVisible = state.mode == InteractionMode.CLAUDE_CODE
        claudeEffortCombo.isVisible = state.mode == InteractionMode.CLAUDE_CODE
        updateIndicators()
        updateTokenDisplay()
        updateContextIndicator()
        updateToolWindowIcons()
        val displayName = state.selectedSpecificPromptName ?: "Just Code"
        promptSelectButton.text = "$displayName ▾"
        promptSelectButton.toolTipText = if (state.selectedSpecificPromptName != null)
            "Active prompt: $displayName — click to change"
        else
            "No specific prompt active — click to select"
        val hasPrompt = state.selectedSpecificPromptName != null
        editPromptButton.isEnabled = hasPrompt
        deletePromptButton.isEnabled = hasPrompt

        approveButton.isVisible = state.claudeCodeApproveVisible

        if (state.claudeCodeApproveVisible) {
            sendButton.isEnabled = false
            sendButton.toolTipText = "Press Approve to continue, or start a new chat (+ New)"
        } else {
            sendButton.toolTipText = "Send message (Ctrl+Enter)"
        }
    }

    private fun buildPromptPanel(): JPanel {
        return JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            background = JBColor.background()
            add(claudeModelCombo)
            add(claudeEffortCombo)
            add(newPromptButton)
            add(editPromptButton)
            add(deletePromptButton)
            add(manageSpecificButton)
            add(promptSelectButton)
        }
    }

    private fun showPromptSelectionPopup() {
        val state = buildState()
        val items = mutableListOf("Just Code") + state.availablePrompts
        val popup = JPopupMenu()
        items.forEach { name ->
            val item = JMenuItem(name).apply {
                val isSelected = if (name == "Just Code")
                    state.selectedSpecificPromptName == null
                else
                    name == state.selectedSpecificPromptName
                font = if (isSelected) font.deriveFont(Font.BOLD) else font
                addActionListener {
                    val selectedName = if (name == "Just Code") null else name
                    messageController.selectSpecificPrompt(selectedName)
                }
            }
            popup.add(item)
        }
        popup.show(promptSelectButton, 0, promptSelectButton.height)
    }

    private fun createNewPromptFile() {
        val basePath = project.basePath ?: return
        val dir = java.io.File(basePath, ".maxvibes/prompts/specific")
        if (!dir.exists()) dir.mkdirs()

        // Find a unique filename
        var candidate = java.io.File(dir, "new_prompt.md")
        var counter = 1
        while (candidate.exists()) {
            candidate = java.io.File(dir, "new_prompt_$counter.md")
            counter++
        }

        try {
            candidate.writeText("# ${candidate.nameWithoutExtension}\n\nDescribe your task-specific prompt here.\n")
        } catch (e: Exception) {
            statusLabel.text = "Failed to create prompt file: ${e.message}"
            return
        }

        // Open the file in the IDE editor
        val vFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(candidate)
        if (vFile != null) {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vFile, true)
        }

        statusLabel.text = "Created: ${candidate.name} \u2014 add your prompt text and save"
        // Refresh the dropdown so the new file appears immediately
        render(buildState())
    }

    private fun editCurrentPromptFile() {
        val name = buildState().selectedSpecificPromptName ?: return
        val basePath = project.basePath ?: return
        val dir = java.io.File(basePath, ".maxvibes/prompts/specific")
        val file = listOf("md", "txt")
            .map { java.io.File(dir, "$name.$it") }
            .firstOrNull { it.exists() } ?: return
        val vFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(file)
        if (vFile != null) {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vFile, true)
        }
    }

    private fun deleteCurrentPromptFile() {
        val name = buildState().selectedSpecificPromptName ?: return
        val confirm = JOptionPane.showConfirmDialog(
            this,
            "Delete prompt \"$name\"?\nThe file will be permanently removed from disk.",
            "Delete Prompt",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (confirm != JOptionPane.YES_OPTION) return

        val basePath = project.basePath ?: return
        val dir = java.io.File(basePath, ".maxvibes/prompts/specific")
        val file = listOf("md", "txt")
            .map { java.io.File(dir, "$name.$it") }
            .firstOrNull { it.exists() }

        if (file == null || !file.delete()) {
            statusLabel.text = "Failed to delete prompt file"
            return
        }

        // Reset selection in all sessions that had this prompt
        val session = chatTreeService.getActiveSession()
        if (session.selectedSpecificPromptName == name) {
            messageController.selectSpecificPrompt(null)
        }

        statusLabel.text = "Deleted prompt: $name"
        render(buildState())
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

    private fun createAttachmentThumbnail(image: AttachedImage, index: Int): JComponent {
        val clearListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = messageController.clearImages()
        }

        val previewIcon = runCatching {
            val bytes = java.util.Base64.getDecoder().decode(image.base64Data)
            val buffered = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) ?: return@runCatching null
            val maxSize = 40
            val scale = minOf(
                maxSize.toDouble() / buffered.width.toDouble(),
                maxSize.toDouble() / buffered.height.toDouble(),
                1.0
            )
            val width = maxOf(1, (buffered.width * scale).toInt())
            val height = maxOf(1, (buffered.height * scale).toInt())
            ImageIcon(buffered.getScaledInstance(width, height, Image.SCALE_SMOOTH))
        }.getOrNull()

        return JPanel(FlowLayout(FlowLayout.LEFT, 3, 0)).apply {
            background = JBColor.background()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(2, 4)
            )
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Attached image ${index + 1} — click to remove"

            val previewLabel = if (previewIcon != null) JBLabel(previewIcon) else JBLabel("\uD83D\uDDBC")
            val indexLabel = JBLabel("#${index + 1}").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(10f)
            }

            add(previewLabel)
            add(indexLabel)
            addMouseListener(clearListener)
            previewLabel.addMouseListener(clearListener)
            indexLabel.addMouseListener(clearListener)
        }
    }

    override fun onImagesChanged(images: List<AttachedImage>) {
        attachmentsPanel.removeAll()
        images.forEachIndexed { index, image ->
            attachmentsPanel.add(createAttachmentThumbnail(image, index))
        }

        val hasImages = images.isNotEmpty()
        attachmentsPanel.isVisible = hasImages

        val bar = attachmentsPanel.parent
        bar?.isVisible = hasImages || traceIndicator.isVisible || errorsIndicator.isVisible
        attachmentsPanel.revalidate()
        attachmentsPanel.repaint()
        bar?.revalidate()
        bar?.repaint()
    }

    override fun onError(message: String) {
        setStatus(message)
    }

    override fun onSessionChanged(session: ChatSession?) {
        loadCurrentSession()
    }

    override fun onSessionRenamed(session: ChatSession) {
        render(buildState())
    }

    override fun onShowWelcome() {
        showWelcome()
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

    /** One-shot editor-skill chip; lazily added to [attachmentsPanel] on first use. */
    private val oneShotChip = JBLabel("").apply {
        foreground = JBColor(Color(0x7B1FA2), Color(0xBA68C8))
        font = font.deriveFont(Font.BOLD, 11f)
        isVisible = false
    }

    /** Cancels the armed one-shot skill. */
    private val clearOneShotButton = JButton("\u2715").apply {
        toolTipText = "Cancel one-shot skill"
        font = font.deriveFont(9f)
        preferredSize = Dimension(20, 20)
        isVisible = false
        addActionListener { messageController.clearOneShot() }
    }

    /**
     * Accepts a prefill from an editor action: fills or appends the input and arms
     * a one-shot skill/context when present. Does NOT send — the user reviews and
     * presses Send. EDT only (invoked from the message-bus subscriber).
     */
    fun acceptPrefill(prefill: EditorPrefill) {
        if (prefill.append && inputArea.text.isNotBlank()) {
            inputArea.text = inputArea.text.trimEnd() + " " + prefill.text
        } else if (prefill.text.isNotBlank()) {
            inputArea.text = prefill.text
        }
        inputArea.caretPosition = inputArea.text.length
        inputArea.requestFocusInWindow()
        if (prefill.oneShotSkillName != null || prefill.elementContext != null) {
            messageController.armOneShot(
                prefill.oneShotSkillName,
                prefill.elementContext,
                prefill.elementLabel ?: "element"
            )
        }
    }

    /**
     * Shows/hides the one-shot skill chip. Chip components are lazily added to
     * [attachmentsPanel] on first call so the existing setupUI stays untouched.
     * Known cosmetic limitation: an unrelated render() while armed may re-hide the
     * attachments panel; the armed state lives in the controller, so Send still works.
     */
    override fun onOneShotChanged(label: String?) {
        if (oneShotChip.parent == null) {
            attachmentsPanel.add(oneShotChip)
            attachmentsPanel.add(clearOneShotButton)
        }
        val armed = label != null
        oneShotChip.text = if (armed) "\u26A1 " + label + " (1\u00D7)" else ""
        oneShotChip.isVisible = armed
        clearOneShotButton.isVisible = armed
        if (armed) {
            attachmentsPanel.isVisible = true
            attachmentsPanel.revalidate()
            attachmentsPanel.repaint()
        } else {
            render(buildState())
        }
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
}
