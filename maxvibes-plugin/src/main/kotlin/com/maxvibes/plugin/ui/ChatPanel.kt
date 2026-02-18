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

/**
 * Main chat panel: UI fields, layout, mode switching, breadcrumb,
 * trace management, session loading.
 *
 * Delegates to:
 * - [ChatMessageController] for send/handle logic
 * - [ChatNavigationHelper] for click-to-navigate
 * - [ChatDialogsHelper] for context files and Claude instructions dialogs
 */
class ChatPanel(
    private val project: Project,
    private val onShowSessions: () -> Unit
) : JPanel(BorderLayout()), ChatPanelCallbacks {

    // --- UI Components ---
    private val chatArea = JBTextArea().apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        background = JBColor.background()
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

    private val attachTraceButton = JButton("\uD83D\uDCCE Trace").apply {
        toolTipText = "Paste error/stacktrace/logs (Ctrl+Shift+V)"; font = font.deriveFont(11f)
    }
    private val traceIndicator = JBLabel("").apply {
        foreground = JBColor(Color(0xFF9800), Color(0xFFB74D)); font = font.deriveFont(Font.BOLD, 11f); isVisible = false
    }
    private val clearTraceButton = JButton("\u2715").apply {
        toolTipText = "Remove attached trace"; font = font.deriveFont(9f); preferredSize = Dimension(20, 20); isVisible = false
    }

    private val statusLabel = JBLabel("Ready").apply { foreground = JBColor.GRAY }
    private val modeIndicator = JBLabel("").apply {
        foreground = JBColor(Color(0x2196F3), Color(0x64B5F6)); font = font.deriveFont(Font.BOLD, 11f); isVisible = false
    }
    private val breadcrumbPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { background = JBColor.background() }

    private val sessionsButton = JButton("\uD83D\uDCC2 Sessions").apply { font = font.deriveFont(11f); isFocusPainted = false }
    private val branchButton = JButton("\u2442 Branch").apply { font = font.deriveFont(11f); isFocusPainted = false }
    private val newChatButton = JButton("+ New").apply { font = font.deriveFont(11f); isFocusPainted = false }
    private val deleteButton = JButton("\uD83D\uDDD1 Del").apply { font = font.deriveFont(11f); isFocusPainted = false }
    private val promptsButton = JButton("\u2699").apply { toolTipText = "Edit prompts"; font = font.deriveFont(11f) }
    private val contextFilesButton = JButton("\uD83D\uDCCE Ctx").apply { font = font.deriveFont(11f); isFocusPainted = false }
    private val claudeInstrButton = JButton("\uD83D\uDCCB").apply { font = font.deriveFont(11f); isFocusPainted = false }

    // --- Services & State ---
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
        setupUI(); setupListeners(); setupClickableLinks()
        loadCurrentSession(); syncModeFromSettings()
    }

    // ==================== ChatPanelCallbacks ====================

    override fun appendToChat(text: String) {
        chatArea.append(text); chatArea.caretPosition = chatArea.document.length
    }

    override fun appendAssistantMessage(text: String) {
        appendToChat("\n\uD83E\uDD16 MaxVibes:\n${formatMarkdown(text)}\n")
    }

    override fun setInputEnabled(enabled: Boolean) {
        inputArea.isEnabled = enabled; sendButton.isEnabled = enabled
        dryRunCheckbox.isEnabled = enabled; planOnlyCheckbox.isEnabled = enabled
        attachTraceButton.isEnabled = enabled; clearTraceButton.isEnabled = enabled
        promptsButton.isEnabled = enabled; modeComboBox.isEnabled = enabled
        sessionsButton.isEnabled = enabled; branchButton.isEnabled = enabled
        newChatButton.isEnabled = enabled; deleteButton.isEnabled = enabled
        contextFilesButton.isEnabled = enabled; claudeInstrButton.isEnabled = enabled
    }

    override fun setStatus(text: String) { statusLabel.text = text }

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
                            ClipboardPhase.PLANNING -> "\u23F3 Paste response (planning)"
                            ClipboardPhase.CHAT -> "\u23F3 Paste response (chat)"
                            else -> "\u23F3 Paste response"
                        }
                        modeIndicator.isVisible = true; sendButton.text = "Paste"
                    }
                    cs.hasActiveSession() -> {
                        modeIndicator.text = "\uD83D\uDCCB Active"
                        modeIndicator.isVisible = true; sendButton.text = "Send / Paste"
                    }
                    else -> {
                        modeIndicator.text = "\uD83D\uDCCB"
                        modeIndicator.isVisible = true; sendButton.text = "Generate"
                    }
                }
                dryRunCheckbox.isVisible = false
            }
            InteractionMode.CHEAP_API -> {
                modeIndicator.text = "\uD83D\uDCB0"; modeIndicator.isVisible = true
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
                breadcrumbPanel.add(JBLabel(" \u203A ").apply { foreground = JBColor.GRAY; font = font.deriveFont(11f) })
            }
            if (isLast) {
                val titleText = s.title.take(30) + if (s.title.length > 30) ".." else ""
                val label = JBLabel(titleText).apply {
                    font = font.deriveFont(Font.BOLD, 11f); foreground = JBColor.foreground()
                    border = JBUI.Borders.empty(2, 3); cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
                    toolTipText = "Click to rename"
                }
                label.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) { startInlineRename(label, s.id, s.title) }
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
                    override fun mouseClicked(e: MouseEvent) { chatHistory.setActiveSession(sid); loadCurrentSession() }
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
        return text.lines().joinToString("\n") { line ->
            var l = line
            Regex("^###\\s+(.+)").find(l)?.let { return@joinToString "  \u2500\u2500\u2500 ${it.groupValues[1].trim()} \u2500\u2500\u2500" }
            Regex("^##\\s+(.+)").find(l)?.let { return@joinToString "\u2550\u2550 ${it.groupValues[1].trim().uppercase()} \u2550\u2550" }
            Regex("^#\\s+(.+)").find(l)?.let { return@joinToString "\u2550\u2550\u2550 ${it.groupValues[1].trim().uppercase()} \u2550\u2550\u2550" }
            if (l.trim().matches(Regex("^[-*_]{3,}$"))) return@joinToString "\u2500".repeat(40)
            l = l.replace(Regex("^(\\s*)[-*]\\s+"), "$1\u2022 ")
            l = l.replace(Regex("\\*{3}(.+?)\\*{3}")) { it.groupValues[1].uppercase() }
            l = l.replace(Regex("\\*{2}(.+?)\\*{2}")) { it.groupValues[1].uppercase() }
            l = l.replace(Regex("(?<![*])\\*([^*]+?)\\*(?![*])")) { it.groupValues[1] }
            l = l.replace(Regex("`([^`]+?)`")) { "[${it.groupValues[1]}]" }
            l
        }
    }

    // ==================== Public API ====================

    fun refreshHeader() { updateBreadcrumb(); updateModeIndicator(); updateContextIndicator() }

    fun loadCurrentSession() {
        val session = chatHistory.getActiveSession()
        chatArea.text = ""; elementNavRegistry.clear()
        updateBreadcrumb(); updateModeIndicator(); updateContextIndicator()

        if (session.messages.isEmpty()) {
            showWelcome()
        } else {
            val path = chatHistory.getSessionPath(session.id)
            if (path.size > 1) {
                val chain = path.dropLast(1).joinToString(" \u203A ") { it.title.take(25) }
                appendToChat("\u2514 Branch of: $chain\n" + "\u2500".repeat(50) + "\n")
            }
            session.messages.forEach { msg ->
                when (msg.role) {
                    MessageRole.USER -> appendToChat("\n\uD83D\uDC64 You:\n${msg.content}\n")
                    MessageRole.ASSISTANT -> appendAssistantMessage(msg.content)
                    MessageRole.SYSTEM -> appendToChat("\n\u2699\uFE0F ${msg.content}\n")
                }
            }
        }
    }

    fun resetClipboard() { service.clipboardService.reset() }

    // ==================== UI Setup ====================

    private fun setupUI() {
        border = JBUI.Borders.empty(); background = JBColor.background()

        // Header
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); background = JBColor.background()
            border = JBUI.Borders.compound(JBUI.Borders.empty(4, 8), JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0))

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

        val chatScroll = JBScrollPane(chatArea).apply {
            border = JBUI.Borders.empty(); verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        val traceBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            background = JBColor.background(); border = JBUI.Borders.empty(2, 8, 0, 8)
            add(traceIndicator); add(clearTraceButton); isVisible = false
        }

        val inputPanel = JPanel(BorderLayout(5, 4)).apply {
            border = JBUI.Borders.empty(4, 8, 8, 8); background = JBColor.background()
            add(traceBar, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.customLine(JBColor.border(), 1); add(inputArea, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                background = JBColor.background()
                add(attachTraceButton.apply { preferredSize = Dimension(80, 26) })
                add(planOnlyCheckbox); add(dryRunCheckbox); add(sendButton)
            }, BorderLayout.SOUTH)
        }

        val statusBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 10); background = JBColor.background()
            add(statusLabel, BorderLayout.WEST)
            add(JBLabel("<html><small>Ctrl+Enter send | Click file paths to open</small></html>").apply {
                foreground = JBColor.GRAY
            }, BorderLayout.EAST)
        }

        add(headerPanel, BorderLayout.NORTH)
        add(chatScroll, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(inputPanel, BorderLayout.CENTER); add(statusBar, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
    }

    // ==================== Listeners ====================

    private fun setupListeners() {
        sendButton.addActionListener { sendMessage() }
        sessionsButton.addActionListener { onShowSessions() }

        newChatButton.addActionListener {
            resetClipboard(); chatHistory.createNewSession(); clearAttachedTrace()
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
                clearAttachedTrace(); loadCurrentSession(); updateModeIndicator()
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

        attachTraceButton.addActionListener { attachTraceFromClipboard() }
        clearTraceButton.addActionListener { clearAttachedTrace() }

        modeComboBox.addActionListener {
            val selected = modeComboBox.selectedItem as? ModeItem ?: return@addActionListener
            val newMode = InteractionMode.valueOf(selected.id)
            if (newMode != currentMode) switchMode(newMode)
        }

        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) { sendMessage(); e.consume() }
                else if (e.keyCode == KeyEvent.VK_V && e.isControlDown && e.isShiftDown) { attachTraceFromClipboard(); e.consume() }
            }
        })
    }

    private fun setupClickableLinks() {
        chatArea.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val target = ChatNavigationHelper.getClickTargetAtPosition(chatArea, e.point, elementNavRegistry) ?: return
                statusLabel.text = when (target) {
                    is ClickTarget.Element -> ChatNavigationHelper.navigateToElement(project, target.fullPath)
                    is ClickTarget.File -> ChatNavigationHelper.openFileInEditor(project, target.relativePath)
                }
            }
        })
        chatArea.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val target = ChatNavigationHelper.getClickTargetAtPosition(chatArea, e.point, elementNavRegistry)
                chatArea.cursor = if (target != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
        })
    }

    // ==================== Send ====================

    private fun sendMessage() {
        val userInput = inputArea.text.trim()
        if (userInput.isBlank()) return
        val trace = attachedTrace; clearAttachedTrace()
        when (currentMode) {
            InteractionMode.API -> sendApiMessage(userInput, trace)
            InteractionMode.CLIPBOARD -> sendClipboardMessage(userInput, trace)
            InteractionMode.CHEAP_API -> sendCheapApiMessage(userInput, trace)
        }
    }

    private fun sendApiMessage(msg: String, trace: String?) {
        val session = chatHistory.getActiveSession()
        appendToChat("\n\uD83D\uDC64 You:\n$msg\n")
        if (!trace.isNullOrBlank()) appendToChat("\uD83D\uDCCE [trace: ${trace.lines().size} lines]\n")
        if (planOnlyCheckbox.isSelected) appendToChat("\uD83D\uDCAC [plan-only mode]\n")
        val fullTask = ChatMessageController.buildTaskWithTrace(msg, trace)
        session.addMessage(MessageRole.USER, fullTask)
        inputArea.text = ""; setInputEnabled(false)
        statusLabel.text = if (planOnlyCheckbox.isSelected) "Planning..." else "Thinking..."
        val history = session.messages.dropLast(1).map { it.toChatMessageDTO() }
        messageController.sendApiMessage(
            fullTask, session, history, dryRunCheckbox.isSelected,
            planOnlyCheckbox.isSelected, chatHistory.getGlobalContextFiles()
        )
    }

    private fun sendClipboardMessage(userInput: String, trace: String?) {
        val cs = service.clipboardService
        val session = chatHistory.getActiveSession()
        inputArea.text = ""
        when {
            cs.isWaitingForResponse() -> {
                session.addMessage(MessageRole.USER, "[Pasted LLM response]")
                appendToChat("\n\uD83D\uDC64 You:\n[Pasted LLM response]\n")
                setInputEnabled(false); statusLabel.text = "Processing..."
                messageController.runClipboardBg("Processing response...", session) { cs.handlePastedResponse(userInput) }
            }
            cs.hasActiveSession() -> {
                appendToChat("\n\uD83D\uDC64 You:\n$userInput\n")
                if (!trace.isNullOrBlank()) appendToChat("\uD83D\uDCCE [trace: ${trace.lines().size} lines]\n")
                session.addMessage(MessageRole.USER, userInput)
                setInputEnabled(false); statusLabel.text = "Continuing..."
                messageController.runClipboardBg("Generating follow-up...", session) { cs.continueDialog(userInput, trace) }
            }
            else -> {
                appendToChat("\n\uD83D\uDC64 You:\n$userInput\n")
                if (!trace.isNullOrBlank()) appendToChat("\uD83D\uDCCE [trace: ${trace.lines().size} lines]\n")
                session.addMessage(MessageRole.USER, userInput)
                setInputEnabled(false); statusLabel.text = "Generating JSON..."
                messageController.runClipboardBg("Generating request...", session) {
                    val dtos = session.messages.dropLast(1).map { it.toChatMessageDTO() }
                    cs.startTask(userInput, dtos, trace)
                }
            }
        }
    }

    private fun sendCheapApiMessage(msg: String, trace: String?) {
        val session = chatHistory.getActiveSession()
        appendToChat("\n\uD83D\uDC64 You:\n$msg\n")
        if (!trace.isNullOrBlank()) appendToChat("\uD83D\uDCCE [trace: ${trace.lines().size} lines]\n")
        if (planOnlyCheckbox.isSelected) appendToChat("\uD83D\uDCAC [plan-only mode]\n")
        val fullTask = ChatMessageController.buildTaskWithTrace(msg, trace)
        session.addMessage(MessageRole.USER, fullTask)
        inputArea.text = ""; setInputEnabled(false); statusLabel.text = "Thinking (cheap)..."
        val history = session.messages.dropLast(1).map { it.toChatMessageDTO() }
        service.ensureCheapLLMService()
        messageController.sendCheapApiMessage(
            fullTask, session, history, dryRunCheckbox.isSelected,
            planOnlyCheckbox.isSelected, chatHistory.getGlobalContextFiles()
        )
    }

    // ==================== Mode ====================

    private fun syncModeFromSettings() {
        currentMode = try { InteractionMode.valueOf(settings.interactionMode) } catch (_: Exception) { InteractionMode.API }
        for (i in 0 until modeComboBox.itemCount) {
            if (modeComboBox.getItemAt(i).id == currentMode.name) { modeComboBox.selectedIndex = i; break }
        }
        updateModeIndicator()
    }

    private fun switchMode(newMode: InteractionMode) {
        if (currentMode == InteractionMode.CLIPBOARD && service.clipboardService.isWaitingForResponse()) {
            val confirm = JOptionPane.showConfirmDialog(
                this, "Active clipboard session will be reset. Continue?",
                "Switch Mode", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
            )
            if (confirm != JOptionPane.YES_OPTION) { syncModeFromSettings(); return }
            service.clipboardService.reset()
        }
        currentMode = newMode; settings.interactionMode = newMode.name; updateModeIndicator()
        if (newMode == InteractionMode.CHEAP_API) service.ensureCheapLLMService()
        val label = MaxVibesSettings.INTERACTION_MODES.find { it.first == newMode.name }?.second ?: newMode.name
        statusLabel.text = "Mode: $label"; appendToChat("\n\u2699\uFE0F Switched to $label\n")
    }

    // ==================== Trace ====================

    private fun attachTraceFromClipboard() {
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val content = clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
            if (content.isNullOrBlank()) { statusLabel.text = "Clipboard is empty"; return }
            attachedTrace = content; updateTraceIndicator()
            val lines = content.lines().size
            val preview = content.lines().take(5).joinToString("\n")
            val suffix = if (lines > 5) "\n   ... ($lines lines total)" else ""
            appendToChat("\n\uD83D\uDCCE Trace attached (${content.length} chars):\n   $preview$suffix\n")
            statusLabel.text = "Trace attached"
        } catch (e: Exception) { statusLabel.text = "Clipboard error: ${e.message}" }
    }

    private fun clearAttachedTrace() {
        if (attachedTrace != null) { attachedTrace = null; updateTraceIndicator(); statusLabel.text = "Trace removed" }
    }

    private fun updateTraceIndicator() {
        val trace = attachedTrace; val has = !trace.isNullOrBlank()
        traceIndicator.isVisible = has; clearTraceButton.isVisible = has; traceIndicator.parent?.isVisible = has
        if (has) traceIndicator.text = "\uD83D\uDCCE Trace: ${trace!!.lines().size}L / ${trace.length}ch"
    }

    // ==================== Inline Rename ====================

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
        fun cancelRename() { if (committed) return; committed = true; updateBreadcrumb() }

        textField.addActionListener { commitRename() }
        textField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ESCAPE) { cancelRename(); e.consume() } }
        })
        textField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent?) { commitRename() }
        })

        parent.remove(idx); parent.add(textField, idx); parent.revalidate(); parent.repaint()
        textField.requestFocusInWindow()
    }

    // ==================== Helpers ====================

    private fun deleteCurrentChat() {
        val session = chatHistory.getActiveSession()
        val childCount = chatHistory.getChildCount(session.id)
        val msg = if (childCount > 0) "Delete \"${session.title}\"?\n$childCount branch(es) will be re-attached to parent."
        else "Delete \"${session.title}\"?"
        val confirm = JOptionPane.showConfirmDialog(this, msg, "Delete Chat", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
        if (confirm != JOptionPane.YES_OPTION) return
        resetClipboard(); clearAttachedTrace(); chatHistory.deleteSession(session.id)
        if (chatHistory.getAllSessions().isEmpty()) chatHistory.createNewSession()
        loadCurrentSession(); updateModeIndicator(); statusLabel.text = "Chat deleted"
    }

    private fun updateContextIndicator() {
        val count = chatHistory.getGlobalContextFiles().size
        contextFilesButton.text = if (count > 0) "\uD83D\uDCCE Ctx($count)" else "\uD83D\uDCCE Ctx"
    }

    private fun showWelcome() {
        val mode = when (currentMode) {
            InteractionMode.API -> "API \u2014 direct LLM calls"
            InteractionMode.CLIPBOARD -> "Clipboard \u2014 paste JSON into Claude/ChatGPT"
            InteractionMode.CHEAP_API -> "Cheap API \u2014 budget model"
        }
        val session = chatHistory.getActiveSession()
        val branchHint = if (session.depth > 0) {
            "\n  \u2514 Branch from: \"${chatHistory.getParent(session.id)?.title ?: "?"}\""
        } else ""
        val ctxCount = chatHistory.getGlobalContextFiles().size
        val ctxHint = if (ctxCount > 0) "\n  \uD83D\uDCCE $ctxCount global context file(s) active" else ""
        appendToChat("  MaxVibes  \u2022  $mode$branchHint$ctxHint\n\n  Type your task, or use Sessions to browse dialogs.\n  Ctrl+Enter send | Click file paths to open\n\n")
    }
}