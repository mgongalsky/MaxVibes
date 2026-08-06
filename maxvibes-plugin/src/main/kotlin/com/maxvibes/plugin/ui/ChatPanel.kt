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

    private fun syncModelComboFromSettings() {
        cliSettingsBinder.syncModel { claudeModelCombo.selectedItem = it }
    }

    private fun commitModelComboToSettings() {
        cliSettingsBinder.commitModel(claudeModelCombo.editor.item ?: claudeModelCombo.selectedItem)
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

    private fun syncEffortComboFromSettings() {
        cliSettingsBinder.syncEffort { claudeEffortCombo.selectedItem = it }
    }

    private fun commitEffortComboToSettings() {
        cliSettingsBinder.commitEffort(claudeEffortCombo.selectedItem)
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

    /** Null when the project has no base path (default/light projects). */
    private val specificPromptFiles: SpecificPromptFiles? by lazy {
        project.basePath?.let { SpecificPromptFiles(it) }
    }
    private val cliSettingsBinder: ClaudeCliSettingsBinder by lazy {
        ClaudeCliSettingsBinder(
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

    private val headerPanel = ChatHeaderPanel(
        onModeSelected = ::handleModeSelection,
        onIndicatorAction = ::handleIndicatorAction,
        onOpenCcLog = ::openClaudeCodeLog,
        onShowSessions = onShowSessions,
        onNewChat = ::createNewChat,
        onBranch = ::createBranch,
        onDeleteChat = ::deleteCurrentChat,
        onOpenPrompts = {
            promptService.openOrCreatePrompts()
            statusLabel.text = "Prompts opened"
        },
        onContextFiles = ::showContextFilesDialog,
        onClaudeInstructions = { anchor ->
            ChatDialogsHelper.showClaudeInstructionsPopup(project, anchor) {
                statusLabel.text = it
            }
        },
        onToggleMaximize = ::toggleMaximize,
        onToggleWindowed = ::toggleWindowed,
        onSelectSession = { sessionId ->
            chatTreeService.setActiveSession(sessionId)
            loadCurrentSession()
        },
        onRenameSession = { sessionId, title ->
            messageController.renameSession(sessionId, title)
            statusLabel.text = "Renamed to '$title'"
        }
    )
    private val inputPanel = ChatInputPanel(
        promptBar = buildPromptPanel(),
        usageBar = limitsBar,
        onSend = ::sendMessage,
        onApprove = { messageController.approve() },
        onCopyJson = { messageController.redoClipboardJson() },
        onAttachTrace = ::attachTraceFromClipboard,
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
        claudeModelCombo.isEnabled = enabled
        claudeEffortCombo.isEnabled = enabled
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
        inputPanel.setPlanOnly(enabled)
    }

    fun refreshHeader() {
        render(buildState())
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

    private fun attachTraceFromClipboard() {
        val content = try {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
        } catch (e: Exception) {
            statusLabel.text = "Clipboard error: ${e.message}"
            return
        }

        if (content.isNullOrBlank()) {
            statusLabel.text = "Clipboard is empty"
            return
        }

        messageController.attachTrace(content)
    }

    private fun setupListeners() {
        promptSelectButton.addActionListener { showPromptSelectionPopup() }
        newPromptButton.addActionListener { createNewPromptFile() }
        editPromptButton.addActionListener { editCurrentPromptFile() }
        deletePromptButton.addActionListener { deleteCurrentPromptFile() }
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

    private fun updateIndicators() {
        inputPanel.updateIndicators(
            messageController.attachedTrace,
            messageController.attachedErrors
        )
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
        headerPanel.updateContextCount(chatTreeService.getGlobalContextFiles().size)
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
        val floating = toolWindow.type == ToolWindowType.FLOATING ||
                toolWindow.type == ToolWindowType.WINDOWED
        headerPanel.updateToolWindowIcons(
            maximized = manager.isMaximized(toolWindow),
            floating = floating
        )
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
        headerPanel.updateBreadcrumb(state.sessionPath)
        updateModeUI(state)
        planPanel.update(state.plan)
        claudeModelCombo.isVisible = state.mode == InteractionMode.CLAUDE_CODE
        claudeEffortCombo.isVisible = state.mode == InteractionMode.CLAUDE_CODE
        inputPanel.updateIndicators(state.attachedTrace, state.attachedErrors)
        tokenLabel.text = state.currentSession?.tokenUsage?.formatDisplay().orEmpty()
        headerPanel.updateContextCount(state.contextFilesCount)
        updateToolWindowIcons()

        val displayName = state.selectedSpecificPromptName ?: "Just Code"
        promptSelectButton.text = "$displayName ▾"
        promptSelectButton.toolTipText = if (state.selectedSpecificPromptName != null) {
            "Active prompt: $displayName — click to change"
        } else {
            "No specific prompt active — click to select"
        }

        val hasPrompt = state.selectedSpecificPromptName != null
        editPromptButton.isEnabled = hasPrompt
        deletePromptButton.isEnabled = hasPrompt
        inputPanel.applyApproveState(state.claudeCodeApproveVisible)
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
        val files = specificPromptFiles ?: return
        files.create().fold(
            onSuccess = { file ->
                com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .refreshAndFindFileByIoFile(file)
                    ?.let { com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(it, true) }
                statusLabel.text = "Created: ${file.name} \u2014 add your prompt text and save"
                render(buildState())
            },
            onFailure = { statusLabel.text = "Failed to create prompt file: ${it.message}" }
        )
    }

    private fun editCurrentPromptFile() {
        val name = buildState().selectedSpecificPromptName ?: return
        val file = specificPromptFiles?.resolve(name) ?: return
        com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(file)
            ?.let { com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(it, true) }
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

        if (specificPromptFiles?.delete(name) != true) {
            statusLabel.text = "Failed to delete prompt file"
            return
        }

        if (chatTreeService.getActiveSession().selectedSpecificPromptName == name) {
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

    override fun onImagesChanged(images: List<AttachedImage>) {
        inputPanel.showImages(images)
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
    private fun createNewChat() {
        messageController.clearAttachmentsAfterSend()
        messageController.createNewSession()
        statusLabel.text = "New dialog"
    }
    private fun createBranch() {
        val active = chatTreeService.getActiveSession()
        val title = JOptionPane.showInputDialog(
            this,
            "Name for the new branch:",
            "New Branch",
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            "Branch: ${active.title.take(25)}"
        ) as? String ?: return

        messageController.clearAttachmentsAfterSend()
        messageController.branchSession(active.id, title)
        statusLabel.text = "Branch: $title"
    }
    private fun showContextFilesDialog() {
        val result = ChatDialogsHelper.showContextFilesDialog(this, project, chatTreeService)
            ?: return
        chatTreeService.setGlobalContextFiles(result)
        updateContextIndicator()
        statusLabel.text = "Context files: ${result.size}"
    }
    private fun toggleMaximize() {
        val manager = ToolWindowManager.getInstance(project)
        manager.setMaximized(toolWindow, !manager.isMaximized(toolWindow))
        updateToolWindowIcons()
    }
    private fun toggleWindowed() {
        val floating = toolWindow.type == ToolWindowType.FLOATING ||
                toolWindow.type == ToolWindowType.WINDOWED
        toolWindow.setType(
            if (floating) ToolWindowType.DOCKED else ToolWindowType.FLOATING,
            null
        )
        updateToolWindowIcons()
    }
}
