package com.maxvibes.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.maxvibes.domain.model.interaction.ClipboardPhase
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.plugin.service.MaxVibesService
import com.maxvibes.plugin.service.PromptService
import com.maxvibes.plugin.settings.MaxVibesSettings
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import com.intellij.openapi.wm.ToolWindow
import com.intellij.icons.AllIcons
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.maxvibes.plugin.service.MaxVibesLogger
import com.intellij.openapi.vcs.VcsConfiguration
import com.intellij.ide.DataManager
import com.intellij.openapi.vcs.VcsDataKeys
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatRole

class ChatPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
    private val onShowSessions: () -> Unit
) : JPanel(BorderLayout()), ChatPanelCallbacks {

    private val conversationPanel = ConversationPanel(project) { path ->
        statusLabel.text = ChatNavigationHelper.navigateToElement(project, path)
    }

    private val inputArea = JBTextArea(3, 40).apply {
        lineWrap = true; wrapStyleWord = true; border = JBUI.Borders.empty(8)
    }

    private val sendButton = JButton("Send").apply { toolTipText = "Send message (Ctrl+Enter)" }

    private val modeComboBox = ComboBox<ModeItem>().apply {
        MaxVibesSettings.INTERACTION_MODES.forEach { (id, label) -> addItem(ModeItem(id, label)) }
        toolTipText = "Interaction mode"
    }

    private val dryRunCheckbox = JBCheckBox("Dry run").apply { toolTipText = "Show plan without applying changes" }
    private val planOnlyCheckbox = JBCheckBox("\uD83D\uDCAC Plan").apply { toolTipText = "Plan-only mode" }
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

    private val service: MaxVibesService by lazy { MaxVibesService.getInstance(project) }
    private val chatTreeService get() = service.chatTreeService
    private val promptService: PromptService by lazy { PromptService.getInstance(project) }
    private val settings: MaxVibesSettings by lazy { MaxVibesSettings.getInstance() }

    // Manages interaction mode state (API / Clipboard / CheapAPI).
// Extracted from ChatPanel to separate state logic from UI.
    private val modeManager: InteractionModeManager by lazy {
        InteractionModeManager(
            settings = settings,
            onModeChanged = { mode ->
                settings.interactionMode = mode.name
                updateModeUI(mode)
                if (mode == InteractionMode.CHEAP_API) service.ensureCheapLLMService()
                render(buildState())
            }
        )
    }

    private var isWaitingForResponse: Boolean = false
    private val elementNavRegistry = mutableMapOf<String, String>()

    // ConversationRenderer handles all message filtering and formatting for display.
    // Extracted here to keep ChatPanel free from knowledge of the internal message storage format.
    private val conversationRenderer = ConversationRenderer()

    private val messageController: ChatMessageController by lazy {
        ChatMessageController(project, service, this)
    }

    init {
        setupUI(); setupListeners()
        loadCurrentSession()
        modeManager.syncFromSettings()
        syncComboBoxToMode()
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

    override fun addUserMessageBubble(text: String) {
        conversationPanel.addUserBubble(text)
    }

    override fun addAssistantMessageBubble(
        text: String,
        tokenInfo: String?,
        modifications: List<ModificationResult>,
        metaFiles: List<String>,
        reasoning: String?
    ) {
        conversationPanel.addAssistantBubble(text, tokenInfo, modifications, metaFiles, reasoning)
        registerElementPaths(modifications)
    }

    override fun clearChatDisplay() {
        conversationPanel.clearMessages()
        elementNavRegistry.clear()
    }

    override fun appendIconToLastBubble(icon: String) {
        conversationPanel.appendIconToLastBubble(icon)
    }

    override fun setInputEnabled(enabled: Boolean) {
        isWaitingForResponse = !enabled
        inputArea.isEnabled = enabled; sendButton.isEnabled = enabled
        dryRunCheckbox.isEnabled = enabled; planOnlyCheckbox.isEnabled = enabled
        copyJsonButton.isEnabled = enabled
        attachTraceButton.isEnabled = enabled; clearTraceButton.isEnabled = enabled
        attachErrorsButton.isEnabled = enabled; clearErrorsButton.isEnabled = enabled
        promptsButton.isEnabled = enabled; modeComboBox.isEnabled = enabled
        sessionsButton.isEnabled = enabled; branchButton.isEnabled = enabled
        newChatButton.isEnabled = enabled; deleteButton.isEnabled = enabled
        contextFilesButton.isEnabled = enabled; claudeInstrButton.isEnabled = enabled
        maximizeButton.isEnabled = enabled
    }

    override fun setStatus(text: String) {
        statusLabel.text = text
    }

    // Interface method (no-arg) — delegates to private implementation with current mode.
    override fun updateModeIndicator() {
        updateModeUI(modeManager.currentMode)
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

    override fun formatMarkdown(text: String): String {
        return text
    }

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
            // Show branch ancestry as a system bubble at the top of the conversation.
            val path = chatTreeService.getSessionPath(session.id)
            if (path.size > 1) {
                val chain = path.dropLast(1).joinToString(" \u203A ") { it.title.take(25) }
                conversationPanel.addSystemBubble("\u2514 Branch of: $chain")
            }

            // Delegate filtering and formatting to ConversationRenderer.
            // This replaces the inline when/filter logic that previously lived here.
            conversationRenderer.render(session.messages).forEach { msg ->
                when (msg.role) {
                    MessageRole.USER -> conversationPanel.addUserBubble(msg.content)
                    MessageRole.ASSISTANT -> conversationPanel.addAssistantBubble(msg.content)
                    MessageRole.SYSTEM -> conversationPanel.addSystemBubble(msg.content)
                }
            }
        }
        render(buildState())
    }

    fun resetClipboard() {
        service.clipboardService.reset()
    }

    private fun setupUI() {
        border = JBUI.Borders.empty(); background = JBColor.background()

        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); background = JBColor.background()
            border =
                JBUI.Borders.compound(JBUI.Borders.empty(4, 8), JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0))

            val controlRow = JPanel(BorderLayout()).apply {
                background = JBColor.background(); maximumSize = Dimension(Int.MAX_VALUE, 30)
                val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    background = JBColor.background()
                    add(modeComboBox.apply { preferredSize = Dimension(180, 24); font = font.deriveFont(11f) })
                    add(modeIndicator)
                }
                val right = JPanel(FlowLayout(FlowLayout.RIGHT, 3, 0)).apply {
                    background = JBColor.background()
                    add(contextFilesButton.apply { preferredSize = Dimension(56, 24) })
                    add(claudeInstrButton.apply { preferredSize = Dimension(26, 24) })
                    add(windowedButton.apply { preferredSize = Dimension(26, 24) })
                    add(maximizeButton.apply { preferredSize = Dimension(26, 24) })
                    add(promptsButton.apply { preferredSize = Dimension(26, 24) })
                }
                add(left, BorderLayout.WEST); add(right, BorderLayout.EAST)
            }

            val navRow = JPanel(BorderLayout(4, 0)).apply {
                background = JBColor.background(); maximumSize = Dimension(Int.MAX_VALUE, 28)
                border = JBUI.Borders.empty(2, 0, 0, 0)
                val scroll = JScrollPane(breadcrumbPanel).apply {
                    border = JBUI.Borders.empty()
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                    background = JBColor.background(); viewport.background = JBColor.background()
                }
                val navButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 3, 0)).apply {
                    background = JBColor.background()
                    add(newChatButton.apply { preferredSize = Dimension(52, 22) })
                    add(branchButton.apply { preferredSize = Dimension(64, 22) })
                    add(deleteButton.apply { preferredSize = Dimension(52, 22) })
                    add(sessionsButton.apply { preferredSize = Dimension(86, 22) })
                }
                add(scroll, BorderLayout.CENTER); add(navButtons, BorderLayout.EAST)
            }

            add(controlRow); add(navRow)
        }

        val traceBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            background = JBColor.background(); border = JBUI.Borders.empty(2, 8, 0, 8)
            add(traceIndicator); add(clearTraceButton)
            add(errorsIndicator); add(clearErrorsButton)
            isVisible = false
        }

        val inputPanel = JPanel(BorderLayout(5, 4)).apply {
            border = JBUI.Borders.empty(4, 8, 8, 8); background = JBColor.background()
            add(traceBar, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.customLine(JBColor.border(), 1); add(inputArea, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                background = JBColor.background()
                add(attachErrorsButton.apply { preferredSize = Dimension(85, 26) })
                add(attachTraceButton.apply { preferredSize = Dimension(80, 26) })
                add(planOnlyCheckbox); add(dryRunCheckbox); add(copyJsonButton); add(sendButton)
            }, BorderLayout.SOUTH)
        }

        val statusBar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(2, 10, 2, 10); background = JBColor.background()
            add(JPanel(BorderLayout()).apply {
                background = JBColor.background()
                add(statusLabel, BorderLayout.WEST)
            })
            add(JPanel(BorderLayout()).apply {
                background = JBColor.background()
                add(tokenLabel.apply { horizontalAlignment = SwingConstants.LEFT }, BorderLayout.WEST)
            })
        }

        add(headerPanel, BorderLayout.NORTH)
        add(conversationPanel, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(inputPanel, BorderLayout.CENTER); add(statusBar, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
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
        copyJsonButton.addActionListener {
            if (service.clipboardService.recopyLastRequest()) {
                statusLabel.text = "JSON re-copied to clipboard"
            } else {
                statusLabel.text = "Nothing to copy"
            }
        }
        sessionsButton.addActionListener { onShowSessions() }

        newChatButton.addActionListener {
            resetClipboard(); chatTreeService.createNewSession()
            messageController.clearAttachmentsAfterSend()
            loadCurrentSession(); updateModeIndicator(); statusLabel.text = "New dialog"
        }

        branchButton.addActionListener {
            val active = chatTreeService.getActiveSession()
            val title = JOptionPane.showInputDialog(
                this, "Name for the new branch:", "New Branch", JOptionPane.PLAIN_MESSAGE,
                null, null, "Branch: ${active.title.take(25)}"
            ) as? String ?: return@addActionListener
            resetClipboard()
            val branch = chatTreeService.createBranch(active.id, title)
            if (branch != null) {
                messageController.clearAttachmentsAfterSend()
                loadCurrentSession(); updateModeIndicator()
                statusLabel.text = "Branch: ${branch.title}"
            }
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
            // Confirm before leaving an active clipboard session
            if (modeManager.currentMode == InteractionMode.CLIPBOARD && service.clipboardService.isWaitingForResponse()) {
                val confirm = JOptionPane.showConfirmDialog(
                    this, "Active clipboard session will be reset. Continue?",
                    "Switch Mode", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
                )
                if (confirm != JOptionPane.YES_OPTION) {
                    syncComboBoxToMode(); return@addActionListener
                }
                service.clipboardService.reset()
            }
            MaxVibesLogger.info(
                "ChatPanel",
                "switchMode",
                mapOf("from" to modeManager.currentMode.name, "to" to newMode.name)
            )
            modeManager.switchMode(newMode)  // triggers onModeChanged: saves settings, updates UI, ensureCheapLLM
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
    }

    private fun sendMessage() {
        val userInput = inputArea.text.trim()
        if (userInput.isBlank()) return
        val trace = messageController.attachedTrace
        val errs = messageController.attachedErrors
        messageController.clearAttachmentsAfterSend()
        val isPlanOnly = planOnlyCheckbox.isSelected
        MaxVibesLogger.info(
            "ChatPanel", "sendMessage", mapOf(
                "mode" to modeManager.currentMode.name,
                "msgLen" to userInput.length,
                "isPlanOnly" to isPlanOnly,
                "hasTrace" to (trace != null),
                "hasErrors" to (errs != null)
            )
        )
        when (modeManager.currentMode) {
            InteractionMode.API -> sendApiMessage(userInput, trace, errs)
            InteractionMode.CLIPBOARD -> sendClipboardMessage(userInput, trace, errs, isPlanOnly)
            InteractionMode.CHEAP_API -> sendCheapApiMessage(userInput, trace, errs)
        }
    }

    private fun sendApiMessage(msg: String, trace: String?, errs: String?) {
        var session = chatTreeService.getActiveSession()
        val isPlanOnly = planOnlyCheckbox.isSelected
        conversationPanel.addUserBubble(msg)
        val fullTask = buildString {
            append(msg)
            if (!trace.isNullOrBlank()) append("\n[trace: ${trace.lines().size} lines]")
            if (!errs.isNullOrBlank()) append("\n[attached ide errors]")
            if (isPlanOnly) append("\n[plan-only]")
        }
        val history = session.messages.map { it.toChatMessageDTO() }
        session = chatTreeService.addMessage(session.id, MessageRole.USER, fullTask)
        inputArea.text = ""; setInputEnabled(false)
        statusLabel.text = if (isPlanOnly) "Planning..." else "Thinking..."
        messageController.sendApiMessage(
            fullTask, session, history, dryRunCheckbox.isSelected,
            isPlanOnly, chatTreeService.getGlobalContextFiles(), errs
        )
    }

    private fun sendClipboardMessage(userInput: String, trace: String?, errs: String?, isPlanOnly: Boolean) {
        val cs = service.clipboardService
        var session = chatTreeService.getActiveSession()
        val globalContextFiles = chatTreeService.getGlobalContextFiles()
        inputArea.text = ""
        when {
            cs.isWaitingForResponse() -> {
                conversationPanel.appendIconToLastBubble("\uD83D\uDCE5")
                setInputEnabled(false); statusLabel.text = "Processing..."
                messageController.runClipboardBg("Processing response...", session) {
                    cs.handlePastedResponse(userInput)
                }
            }

            cs.hasActiveSession() -> {
                conversationPanel.addUserBubble(userInput)
                val fullMsg = buildString {
                    append(userInput)
                    if (!trace.isNullOrBlank()) append("\n[trace: ${trace.lines().size} lines]")
                    if (!errs.isNullOrBlank()) append("\n[attached ide errors]")
                    if (isPlanOnly) append("\n[plan-only]")
                }
                session = chatTreeService.addMessage(session.id, MessageRole.USER, fullMsg)
                setInputEnabled(false); statusLabel.text = "Continuing..."
                messageController.runClipboardBg("Continuing...", session) {
                    cs.continueDialog(userInput, trace, isPlanOnly, errs, globalContextFiles)
                }
            }

            else -> {
                conversationPanel.addUserBubble(userInput)
                val fullMsg = buildString {
                    append(userInput)
                    if (!trace.isNullOrBlank()) append("\n[trace: ${trace.lines().size} lines]")
                    if (!errs.isNullOrBlank()) append("\n[attached ide errors]")
                    if (isPlanOnly) append("\n[plan-only]")
                }
                val dtos = session.messages.map { it.toChatMessageDTO() }
                session = chatTreeService.addMessage(session.id, MessageRole.USER, fullMsg)
                setInputEnabled(false); statusLabel.text = "Generating JSON..."
                messageController.runClipboardBg("Generating request...", session) {
                    cs.startTask(userInput, dtos, trace, isPlanOnly, errs, globalContextFiles)
                }
            }
        }
    }

    private fun sendCheapApiMessage(msg: String, trace: String?, errs: String?) {
        var session = chatTreeService.getActiveSession()
        val isPlanOnly = planOnlyCheckbox.isSelected
        conversationPanel.addUserBubble(msg)
        val fullTask = ChatMessageController.buildTaskWithContext(msg, trace, errs)
        val history = session.messages.map { it.toChatMessageDTO() }
        session = chatTreeService.addMessage(session.id, MessageRole.USER, fullTask)
        inputArea.text = ""; setInputEnabled(false); statusLabel.text = "Thinking (cheap)..."
        service.ensureCheapLLMService()
        messageController.sendCheapApiMessage(
            fullTask, session, history, dryRunCheckbox.isSelected,
            isPlanOnly, chatTreeService.getGlobalContextFiles(), errs
        )
    }

    /**
     * Syncs the combo box selection to match modeManager.currentMode.
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
     * Updates the mode indicator UI components based on the given mode.
     * Called via the InteractionModeManager.onModeChanged callback.
     */
    private fun updateModeUI(mode: InteractionMode) {
        when (mode) {
            InteractionMode.API -> {
                modeIndicator.isVisible = false; sendButton.text = "Send"; dryRunCheckbox.isVisible = true
                copyJsonButton.isVisible = false
            }

            InteractionMode.CLIPBOARD -> {
                val cs = service.clipboardService
                when {
                    cs.isWaitingForResponse() -> {
                        val phase = cs.getCurrentPhase()
                        modeIndicator.text = when (phase) {
                            ClipboardPhase.PLANNING -> "\u23F3 Paste response (planning)"
                            ClipboardPhase.CHAT -> "\u23F3 Paste response (chat)"
                            else -> "\u23F3 Paste response"
                        }
                        modeIndicator.isVisible = true; sendButton.text = "Paste"
                        copyJsonButton.isVisible = true
                    }

                    cs.hasActiveSession() -> {
                        modeIndicator.text = "\uD83D\uDCCB Active"
                        modeIndicator.isVisible = true; sendButton.text = "Send / Paste"
                        copyJsonButton.isVisible = false
                    }

                    else -> {
                        modeIndicator.text = "\uD83D\uDCCB"
                        modeIndicator.isVisible = true; sendButton.text = "Generate"
                        copyJsonButton.isVisible = false
                    }
                }
                dryRunCheckbox.isVisible = false
            }

            InteractionMode.CHEAP_API -> {
                modeIndicator.text = "\uD83D\uDCB0"; modeIndicator.isVisible = true
                sendButton.text = "Send"; dryRunCheckbox.isVisible = true
                copyJsonButton.isVisible = false
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

        val showBar = hasTrace || hasErrs
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
                chatTreeService.renameSession(sessionId, newTitle); statusLabel.text = "Renamed to \"$newTitle\""
            }
            updateBreadcrumb()
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
            this, msg, "Delete Chat", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        )
        if (confirm != JOptionPane.YES_OPTION) return
        resetClipboard()
        messageController.clearAttachmentsAfterSend()
        chatTreeService.deleteSession(session.id)
        if (chatTreeService.getAllSessions().isEmpty()) chatTreeService.createNewSession()
        loadCurrentSession(); updateModeIndicator(); statusLabel.text = "Chat deleted"
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

    private fun ChatMessage.toChatMessageDTO() = ChatMessageDTO(
        role = when (role) {
            MessageRole.USER -> ChatRole.USER
            MessageRole.ASSISTANT -> ChatRole.ASSISTANT
            MessageRole.SYSTEM -> ChatRole.SYSTEM
        },
        content = content
    )

    /**
     * Обновляет все UI-компоненты на основе переданного состояния.
     * Единая точка обновления View — фундамент для MVP-паттерна (Steps 4-5).
     */
    fun render(state: ChatPanelState) {
        // Хлебные крошки / заголовок
        updateBreadcrumb()

        // Индикатор режима
        updateModeUI(state.mode)

        // Кнопка отправки и поле ввода
        sendButton.isEnabled = !state.isWaitingResponse
        inputArea.isEnabled = !state.isWaitingResponse

        // Индикаторы прикреплений (trace / errors)
        updateIndicators()

        // Токены текущей сессии
        updateTokenDisplay()

        // Количество файлов контекста
        updateContextIndicator()

        // Иконки тулбара (maximize / windowed)
        updateToolWindowIcons()
    }

    private fun buildState(): ChatPanelState {
        val session = chatTreeService.getActiveSession()
        return ChatPanelState(
            currentSession = session,
            sessionPath = chatTreeService.getSessionPath(session.id),
            mode = modeManager.currentMode,
            isWaitingResponse = isWaitingForResponse,
            attachedTrace = messageController.attachedTrace,
            attachedErrors = messageController.attachedErrors,
            contextFilesCount = chatTreeService.getGlobalContextFiles().size,
            tokenUsage = session.tokenUsage.takeIf { !it.isEmpty() }
        )
    }
    override fun onAttachmentsChanged(trace: String?, errors: String?) {
        render(buildState())
    }
    override fun onError(message: String) {
        setStatus(message)
    }
}
