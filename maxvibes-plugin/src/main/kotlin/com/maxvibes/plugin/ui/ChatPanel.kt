package com.maxvibes.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.maxvibes.domain.model.interaction.ClipboardPhase
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.plugin.chat.ChatHistoryService
import com.maxvibes.plugin.chat.MessageRole
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
    private val planOnlyCheckbox = JBCheckBox("💬 Plan").apply { toolTipText = "Plan-only mode" }

    private val attachErrorsButton = JButton("🐞 Errors").apply {
        toolTipText = "Attach IDE errors from open files"; font = font.deriveFont(11f)
    }
    private val errorsIndicator = JBLabel("").apply {
        foreground = JBColor(Color(0xD32F2F), Color(0xEF5350)); font = font.deriveFont(Font.BOLD, 11f); isVisible =
        false
    }
    private val clearErrorsButton = JButton("✕").apply {
        toolTipText = "Remove attached errors"; font = font.deriveFont(9f); preferredSize =
        Dimension(20, 20); isVisible = false
    }
    private var attachedErrors: String? = null

    private val attachTraceButton = JButton("📎 Trace").apply {
        toolTipText = "Paste error/stacktrace/logs (Ctrl+Shift+V)"; font = font.deriveFont(11f)
    }
    private val traceIndicator = JBLabel("").apply {
        foreground = JBColor(Color(0xFF9800), Color(0xFFB74D)); font = font.deriveFont(Font.BOLD, 11f); isVisible =
        false
    }
    private val clearTraceButton = JButton("✕").apply {
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

    private val sessionsButton = JButton("📂 Sessions").apply { font = font.deriveFont(11f); isFocusPainted = false }
    private val branchButton = JButton("⑂ Branch").apply { font = font.deriveFont(11f); isFocusPainted = false }
    private val newChatButton = JButton("+ New").apply { font = font.deriveFont(11f); isFocusPainted = false }
    private val deleteButton = JButton("🗑 Del").apply { font = font.deriveFont(11f); isFocusPainted = false }
    private val promptsButton = JButton("⚙").apply { toolTipText = "Edit prompts"; font = font.deriveFont(11f) }
    private val contextFilesButton = JButton("📎 Ctx").apply { font = font.deriveFont(11f); isFocusPainted = false }
    private val claudeInstrButton = JButton("📋").apply { font = font.deriveFont(11f); isFocusPainted = false }
    private val maximizeButton = JButton(AllIcons.General.ExpandComponent).apply {
        toolTipText = "Maximize / Restore"; font = font.deriveFont(11f); isFocusPainted = false
    }
    private val windowedButton = JButton(AllIcons.Actions.MoveToWindow).apply {
        toolTipText = "Floating Mode / Dock"; font = font.deriveFont(11f); isFocusPainted = false
    }

    private val service: MaxVibesService by lazy { MaxVibesService.getInstance(project) }
    private val chatHistory: ChatHistoryService by lazy { ChatHistoryService.getInstance(project) }
    private val promptService: PromptService by lazy { PromptService.getInstance(project) }
    private val settings: MaxVibesSettings by lazy { MaxVibesSettings.getInstance() }

    private var currentMode: InteractionMode = InteractionMode.API
    private var attachedTrace: String? = null
    private val elementNavRegistry = mutableMapOf<String, String>()

    private val messageController: ChatMessageController by lazy {
        ChatMessageController(project, service, this)
    }

    init {
        setupUI(); setupListeners()
        loadCurrentSession(); syncModeFromSettings()
    }

    override fun appendToChat(text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        if (t.all { it == '─' || it == '═' || it == '━' || it == '-' }) return
        if (t.contains("Paste this into") || t.contains("JSON copied") || t.startsWith("📋")) return
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
        inputArea.isEnabled = enabled; sendButton.isEnabled = enabled
        dryRunCheckbox.isEnabled = enabled; planOnlyCheckbox.isEnabled = enabled
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

    override fun updateModeIndicator() {
        when (currentMode) {
            InteractionMode.API -> {
                modeIndicator.isVisible = false; sendButton.text = "Send"; dryRunCheckbox.isVisible = true
            }

            InteractionMode.CLIPBOARD -> {
                val cs = service.clipboardService
                when {
                    cs.isWaitingForResponse() -> {
                        val phase = cs.getCurrentPhase()
                        modeIndicator.text = when (phase) {
                            ClipboardPhase.PLANNING -> "⏳ Paste response (planning)"
                            ClipboardPhase.CHAT -> "⏳ Paste response (chat)"
                            else -> "⏳ Paste response"
                        }
                        modeIndicator.isVisible = true; sendButton.text = "Paste"
                    }

                    cs.hasActiveSession() -> {
                        modeIndicator.text = "📋 Active"
                        modeIndicator.isVisible = true; sendButton.text = "Send / Paste"
                    }

                    else -> {
                        modeIndicator.text = "📋"
                        modeIndicator.isVisible = true; sendButton.text = "Generate"
                    }
                }
                dryRunCheckbox.isVisible = false
            }

            InteractionMode.CHEAP_API -> {
                modeIndicator.text = "💰"; modeIndicator.isVisible = true
                sendButton.text = "Send"; dryRunCheckbox.isVisible = true
            }
        }
    }

    override fun updateBreadcrumb() {
        breadcrumbPanel.removeAll()
        val session = chatHistory.getActiveSession()
        val path = chatHistory.getSessionPath(session.id)

        for ((i, s) in path.withIndex()) {
            val isLast = i == path.size - 1
            if (i > 0) {
                breadcrumbPanel.add(JBLabel(" › ").apply {
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
                        chatHistory.setActiveSession(sid); loadCurrentSession()
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
        val session = chatHistory.getActiveSession()
        tokenLabel.text = session.formatTokenDisplay()
    }

    override fun setPlanOnlyMode(enabled: Boolean) {
        planOnlyCheckbox.isSelected = enabled
    }

    fun refreshHeader() {
        updateBreadcrumb(); updateModeIndicator(); updateContextIndicator(); updateToolWindowIcons()
    }

    fun loadCurrentSession() {
        val session = chatHistory.getActiveSession()
        conversationPanel.clearMessages()
        elementNavRegistry.clear()
        updateBreadcrumb(); updateModeIndicator(); updateContextIndicator(); updateTokenDisplay(); updateToolWindowIcons()

        if (session.messages.isEmpty()) {
            showWelcome()
        } else {
            val path = chatHistory.getSessionPath(session.id)
            if (path.size > 1) {
                val chain = path.dropLast(1).joinToString(" › ") { it.title.take(25) }
                conversationPanel.addSystemBubble("└ Branch of: $chain")
            }
            session.messages.forEach { msg ->
                when (msg.role) {
                    MessageRole.USER -> conversationPanel.addUserBubble(msg.content)
                    MessageRole.ASSISTANT -> conversationPanel.addAssistantBubble(msg.content)
                    MessageRole.SYSTEM -> conversationPanel.addSystemBubble(msg.content)
                }
            }
        }
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
                add(planOnlyCheckbox); add(dryRunCheckbox); add(sendButton)
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
        sendButton.addActionListener { sendMessage() }
        sessionsButton.addActionListener { onShowSessions() }

        newChatButton.addActionListener {
            resetClipboard(); chatHistory.createNewSession(); clearAttachedTrace(); clearAttachedErrors()
            loadCurrentSession(); updateModeIndicator(); statusLabel.text = "New dialog"
        }

        branchButton.addActionListener {
            val active = chatHistory.getActiveSession()
            val title = JOptionPane.showInputDialog(
                this, "Name for the new branch:", "New Branch", JOptionPane.PLAIN_MESSAGE,
                null, null, "Branch: ${active.title.take(25)}"
            ) as? String ?: return@addActionListener
            resetClipboard()
            val branch = chatHistory.createBranch(active.id, title)
            if (branch != null) {
                clearAttachedTrace(); clearAttachedErrors(); loadCurrentSession(); updateModeIndicator()
                statusLabel.text = "Branch: ${branch.title}"
            }
        }

        deleteButton.addActionListener { deleteCurrentChat() }
        promptsButton.addActionListener { promptService.openOrCreatePrompts(); statusLabel.text = "Prompts opened" }

        contextFilesButton.addActionListener {
            val result = ChatDialogsHelper.showContextFilesDialog(this, project, chatHistory)
            if (result != null) {
                chatHistory.setGlobalContextFiles(result)
                updateContextIndicator(); statusLabel.text = "Context files: ${result.size}"
            }
        }

        claudeInstrButton.addActionListener {
            ChatDialogsHelper.showClaudeInstructionsPopup(project, claudeInstrButton) { statusLabel.text = it }
        }

        maximizeButton.addActionListener {
            val manager = ToolWindowManager.getInstance(project)
            manager.setMaximized(toolWindow, !manager.isMaximized(toolWindow))
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

        attachTraceButton.addActionListener { attachTraceFromClipboard() }
        clearTraceButton.addActionListener { clearAttachedTrace() }
        attachErrorsButton.addActionListener { fetchIdeErrors() }
        clearErrorsButton.addActionListener { clearAttachedErrors() }

        modeComboBox.addActionListener {
            val selected = modeComboBox.selectedItem as? ModeItem ?: return@addActionListener
            val newMode = InteractionMode.valueOf(selected.id)
            if (newMode != currentMode) switchMode(newMode)
        }

        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) {
                    sendMessage(); e.consume()
                } else if (e.keyCode == KeyEvent.VK_V && e.isControlDown && e.isShiftDown) {
                    attachTraceFromClipboard(); e.consume()
                }
            }
        })
    }

    private fun sendMessage() {
        val userInput = inputArea.text.trim()
        if (userInput.isBlank()) return
        val trace = attachedTrace; clearAttachedTrace()
        val errs = attachedErrors; clearAttachedErrors()
        val isPlanOnly = planOnlyCheckbox.isSelected
        when (currentMode) {
            InteractionMode.API -> sendApiMessage(userInput, trace, errs)
            InteractionMode.CLIPBOARD -> sendClipboardMessage(userInput, trace, errs, isPlanOnly)
            InteractionMode.CHEAP_API -> sendCheapApiMessage(userInput, trace, errs)
        }
    }

    private fun sendApiMessage(msg: String, trace: String?, errs: String?) {
        val session = chatHistory.getActiveSession()
        val isPlanOnly = planOnlyCheckbox.isSelected
        conversationPanel.addUserBubble(msg)
        val fullTask = buildString {
            append(msg)
            if (!trace.isNullOrBlank()) append("\n[trace: ${trace.lines().size} lines]")
            if (!errs.isNullOrBlank()) append("\n[attached ide errors]")
            if (isPlanOnly) append("\n[plan-only]")
        }
        session.addMessage(MessageRole.USER, fullTask)
        inputArea.text = ""; setInputEnabled(false)
        statusLabel.text = if (isPlanOnly) "Planning..." else "Thinking..."
        val history = session.messages.dropLast(1).map { it.toChatMessageDTO() }
        messageController.sendApiMessage(
            fullTask, session, history, dryRunCheckbox.isSelected,
            isPlanOnly, chatHistory.getGlobalContextFiles(), errs
        )
    }

    private fun sendClipboardMessage(userInput: String, trace: String?, errs: String?, isPlanOnly: Boolean) {
        val cs = service.clipboardService
        val session = chatHistory.getActiveSession()
        inputArea.text = ""
        when {
            cs.isWaitingForResponse() -> {
                session.addMessage(MessageRole.USER, "[Pasted LLM response]")
                conversationPanel.appendIconToLastBubble("📥")
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
                session.addMessage(MessageRole.USER, fullMsg)
                setInputEnabled(false); statusLabel.text = "Continuing..."
                messageController.runClipboardBg("Continuing...", session) {
                    cs.continueDialog(userInput, trace, isPlanOnly, errs)
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
                session.addMessage(MessageRole.USER, fullMsg)
                setInputEnabled(false); statusLabel.text = "Generating JSON..."
                messageController.runClipboardBg("Generating request...", session) {
                    val dtos = session.messages.dropLast(1).map { it.toChatMessageDTO() }
                    cs.startTask(userInput, dtos, trace, isPlanOnly, errs)
                }
            }
        }
    }

    private fun sendCheapApiMessage(msg: String, trace: String?, errs: String?) {
        val session = chatHistory.getActiveSession()
        val isPlanOnly = planOnlyCheckbox.isSelected
        conversationPanel.addUserBubble(msg)
        val fullTask = ChatMessageController.buildTaskWithContext(msg, trace, errs)
        session.addMessage(MessageRole.USER, fullTask)
        inputArea.text = ""; setInputEnabled(false); statusLabel.text = "Thinking (cheap)..."
        val history = session.messages.dropLast(1).map { it.toChatMessageDTO() }
        service.ensureCheapLLMService()
        messageController.sendCheapApiMessage(
            fullTask, session, history, dryRunCheckbox.isSelected,
            isPlanOnly, chatHistory.getGlobalContextFiles(), errs
        )
    }

    private fun syncModeFromSettings() {
        currentMode = try {
            InteractionMode.valueOf(settings.interactionMode)
        } catch (_: Exception) {
            InteractionMode.API
        }
        for (i in 0 until modeComboBox.itemCount) {
            if (modeComboBox.getItemAt(i).id == currentMode.name) {
                modeComboBox.selectedIndex = i; break
            }
        }
        updateModeIndicator()
    }

    private fun switchMode(newMode: InteractionMode) {
        if (currentMode == InteractionMode.CLIPBOARD && service.clipboardService.isWaitingForResponse()) {
            val confirm = JOptionPane.showConfirmDialog(
                this, "Active clipboard session will be reset. Continue?",
                "Switch Mode", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
            )
            if (confirm != JOptionPane.YES_OPTION) {
                syncModeFromSettings(); return
            }
            service.clipboardService.reset()
        }
        currentMode = newMode; settings.interactionMode = newMode.name; updateModeIndicator()
        if (newMode == InteractionMode.CHEAP_API) service.ensureCheapLLMService()
        val label = MaxVibesSettings.INTERACTION_MODES.find { it.first == newMode.name }?.second ?: newMode.name
        statusLabel.text = "Mode: $label"
        conversationPanel.addSystemBubble("⚙️ Switched to $label")
    }

    private fun attachTraceFromClipboard() {
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val content = clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
            if (content.isNullOrBlank()) {
                statusLabel.text = "Clipboard is empty"; return
            }
            attachedTrace = content; updateIndicators()
            statusLabel.text = "Trace attached (${content.lines().size} lines)"
        } catch (e: Exception) {
            statusLabel.text = "Clipboard error: ${e.message}"
        }
    }

    private fun fetchIdeErrors() {
        statusLabel.text = "Fetching IDE errors..."
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            kotlinx.coroutines.runBlocking {
                val result = service.ideErrorsPort.getCompilerErrors()
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    if (result is com.maxvibes.shared.result.Result.Success) {
                        val errors = result.value
                        if (errors.isEmpty()) {
                            statusLabel.text = "No IDE errors found in open files"
                        } else {
                            attachedErrors = errors.joinToString("\n") { it.formatForLlm() }
                            statusLabel.text = "Attached ${errors.size} IDE errors"
                            updateIndicators()
                        }
                    } else {
                        statusLabel.text = "Failed to fetch errors"
                    }
                }
            }
        }
    }

    private fun clearAttachedTrace() {
        if (attachedTrace != null) {
            attachedTrace = null; updateIndicators(); statusLabel.text = "Trace removed"
        }
    }

    private fun clearAttachedErrors() {
        if (attachedErrors != null) {
            attachedErrors = null; updateIndicators(); statusLabel.text = "Errors removed"
        }
    }

    private fun updateIndicators() {
        val trace = attachedTrace
        val hasTrace = !trace.isNullOrBlank()
        traceIndicator.isVisible = hasTrace
        clearTraceButton.isVisible = hasTrace
        if (hasTrace) traceIndicator.text = "📎 Trace: ${trace!!.lines().size}L"

        val errs = attachedErrors
        val hasErrs = !errs.isNullOrBlank()
        errorsIndicator.isVisible = hasErrs
        clearErrorsButton.isVisible = hasErrs
        if (hasErrs) {
            val count = errs!!.split("File:").size - 1
            errorsIndicator.text = "🐞 Errors: $count"
        }

        val showBar = hasTrace || hasErrs
        val bar = traceIndicator.parent
        bar?.isVisible = showBar
        bar?.parent?.isVisible = showBar
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
                chatHistory.renameSession(sessionId, newTitle); statusLabel.text = "Renamed to \"$newTitle\""
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
        val session = chatHistory.getActiveSession()
        val childCount = chatHistory.getChildCount(session.id)
        val msg =
            if (childCount > 0) "Delete \"${session.title}\"?\n$childCount branch(es) will be re-attached to parent."
            else "Delete \"${session.title}\"?"
        val confirm = JOptionPane.showConfirmDialog(
            this, msg, "Delete Chat", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        )
        if (confirm != JOptionPane.YES_OPTION) return
        resetClipboard(); clearAttachedTrace(); clearAttachedErrors(); chatHistory.deleteSession(session.id)
        if (chatHistory.getAllSessions().isEmpty()) chatHistory.createNewSession()
        loadCurrentSession(); updateModeIndicator(); statusLabel.text = "Chat deleted"
    }

    private fun updateContextIndicator() {
        val count = chatHistory.getGlobalContextFiles().size
        contextFilesButton.text = if (count > 0) "📎 Ctx($count)" else "📎 Ctx"
    }

    private fun showWelcome() {
        val mode = when (currentMode) {
            InteractionMode.API -> "API — direct LLM calls"
            InteractionMode.CLIPBOARD -> "Clipboard — paste JSON into Claude/ChatGPT"
            InteractionMode.CHEAP_API -> "Cheap API — budget model"
        }
        val session = chatHistory.getActiveSession()
        val ctxCount = chatHistory.getGlobalContextFiles().size
        val lines = mutableListOf("MaxVibes  •  $mode")
        if (session.depth > 0) lines += "└ Branch from: \"${chatHistory.getParent(session.id)?.title ?: "?\""}"
        if (ctxCount > 0) lines += "📎 $ctxCount global context file(s) active"
        lines += "Type your task • Ctrl+Enter to send"
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
}
