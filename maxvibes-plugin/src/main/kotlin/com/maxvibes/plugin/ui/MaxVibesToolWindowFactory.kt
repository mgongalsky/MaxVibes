package com.maxvibes.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.maxvibes.application.port.input.ContextAwareRequest
import com.maxvibes.application.port.input.ContextAwareResult
import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.application.service.ClipboardStepResult
import com.maxvibes.domain.model.interaction.ClipboardPhase
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.plugin.chat.ChatHistoryService
import com.maxvibes.plugin.chat.ChatMessage
import com.maxvibes.plugin.chat.ChatSession
import com.maxvibes.plugin.chat.MessageRole
import com.maxvibes.plugin.service.MaxVibesService
import com.maxvibes.plugin.service.PromptService
import com.maxvibes.plugin.settings.MaxVibesSettings
import kotlinx.coroutines.runBlocking
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

// ==================== Factory ====================

class MaxVibesToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MaxVibesToolPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

// ==================== Main Panel (CardLayout) ====================

private const val CARD_CHAT = "chat"
private const val CARD_SESSIONS = "sessions"

class MaxVibesToolPanel(private val project: Project) : JPanel(CardLayout()) {

    private val chatPanel = ChatPanel(project, onShowSessions = { showSessions() })
    private val sessionTreePanel: SessionTreePanel

    private val chatHistory: ChatHistoryService by lazy { ChatHistoryService.getInstance(project) }

    init {
        sessionTreePanel = SessionTreePanel(
            chatHistory = chatHistory,
            onOpenSession = { id -> openSession(id) },
            onNewRoot = { createNewRoot() },
            onNewBranch = { parentId -> createBranch(parentId) },
            onDeleteSession = { id -> deleteSession(id) },
            onBack = { showChat() }
        )

        add(chatPanel, CARD_CHAT)
        add(sessionTreePanel, CARD_SESSIONS)

        showChat()
    }

    private fun showChat() {
        (layout as CardLayout).show(this, CARD_CHAT)
        chatPanel.refreshHeader()
    }

    private fun showSessions() {
        sessionTreePanel.refresh()
        (layout as CardLayout).show(this, CARD_SESSIONS)
    }

    private fun openSession(sessionId: String) {
        chatHistory.setActiveSession(sessionId)
        chatPanel.loadCurrentSession()
        showChat()
    }

    private fun createNewRoot() {
        chatPanel.resetClipboard()
        chatHistory.createNewSession()
        chatPanel.loadCurrentSession()
        showChat()
    }

    private fun createBranch(parentId: String) {
        val parent = chatHistory.getSessionById(parentId) ?: return
        val title = JOptionPane.showInputDialog(
            this,
            "Name for the new branch:",
            "New Branch",
            JOptionPane.PLAIN_MESSAGE,
            null, null,
            "Branch: ${parent.title.take(25)}"
        ) as? String ?: return

        chatPanel.resetClipboard()
        val branch = chatHistory.createBranch(parentId, title) ?: return
        chatPanel.loadCurrentSession()
        showChat()
    }

    private fun deleteSession(sessionId: String) {
        val session = chatHistory.getSessionById(sessionId) ?: return
        val childCount = chatHistory.getChildCount(sessionId)
        val msg = if (childCount > 0) {
            "Delete \"${session.title}\"?\n$childCount branch(es) will be re-attached to parent."
        } else {
            "Delete \"${session.title}\"?"
        }
        val confirm = JOptionPane.showConfirmDialog(
            this, msg, "Delete Dialog", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        )
        if (confirm == JOptionPane.YES_OPTION) {
            chatHistory.deleteSession(sessionId)
            sessionTreePanel.refresh()
        }
    }
}

// ==================== Chat Panel ====================

class ChatPanel(
    private val project: Project,
    private val onShowSessions: () -> Unit
) : JPanel(BorderLayout()) {

    // --- UI ---
    private val chatArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        background = JBColor.background()
    }

    private val inputArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
    }

    private val sendButton = JButton("Send").apply {
        toolTipText = "Send message (Ctrl+Enter)"
    }

    private val modeComboBox = ComboBox<ModeItem>().apply {
        MaxVibesSettings.INTERACTION_MODES.forEach { (id, label) ->
            addItem(ModeItem(id, label))
        }
        toolTipText = "Interaction mode"
    }

    private val dryRunCheckbox = JBCheckBox("Dry run").apply {
        toolTipText = "Show plan without applying changes"
    }

    private val attachTraceButton = JButton("\uD83D\uDCCE Trace").apply {
        toolTipText = "Paste error/stacktrace/logs from clipboard (Ctrl+Shift+V)"
        font = font.deriveFont(11f)
    }

    private val traceIndicator = JBLabel("").apply {
        foreground = JBColor(Color(0xFF9800), Color(0xFFB74D))
        font = font.deriveFont(Font.BOLD, 11f)
        isVisible = false
    }

    private val clearTraceButton = JButton("\u2715").apply {
        toolTipText = "Remove attached trace"
        font = font.deriveFont(9f)
        preferredSize = Dimension(20, 20)
        isVisible = false
    }

    private val statusLabel = JBLabel("Ready").apply {
        foreground = JBColor.GRAY
    }

    private val modeIndicator = JBLabel("").apply {
        foreground = JBColor(Color(0x2196F3), Color(0x64B5F6))
        font = font.deriveFont(Font.BOLD, 11f)
        isVisible = false
    }

    private val breadcrumbPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        background = JBColor.background()
    }

    private val sessionsButton = JButton("\uD83D\uDCC2 Sessions").apply {
        toolTipText = "Browse all dialogs"
        font = font.deriveFont(11f)
        isFocusPainted = false
    }

    private val branchButton = JButton("\u2442 Branch").apply {
        toolTipText = "Create a sub-branch from this dialog"
        font = font.deriveFont(11f)
        isFocusPainted = false
    }

    private val newChatButton = JButton("+ New").apply {
        toolTipText = "Start new root dialog"
        font = font.deriveFont(11f)
        isFocusPainted = false
    }

    private val clearButton = JButton("Clear").apply {
        toolTipText = "Clear current chat"
        font = font.deriveFont(11f)
    }

    private val promptsButton = JButton("\u2699").apply {
        toolTipText = "Edit prompts (.maxvibes/prompts/)"
        font = font.deriveFont(11f)
    }

    // --- Services ---
    private val service: MaxVibesService by lazy { MaxVibesService.getInstance(project) }
    private val chatHistory: ChatHistoryService by lazy { ChatHistoryService.getInstance(project) }
    private val promptService: PromptService by lazy { PromptService.getInstance(project) }
    private val settings: MaxVibesSettings by lazy { MaxVibesSettings.getInstance() }

    private var currentMode: InteractionMode = InteractionMode.API
    private var attachedTrace: String? = null

    init {
        setupUI()
        setupListeners()
        loadCurrentSession()
        syncModeFromSettings()
    }

    // ==================== UI Setup ====================

    private fun setupUI() {
        border = JBUI.Borders.empty()
        background = JBColor.background()

        // ===== Header =====
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = JBColor.background()
            border = JBUI.Borders.compound(
                JBUI.Borders.empty(4, 8),
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            )

            // Row 1: Mode + controls
            val controlRow = JPanel(BorderLayout()).apply {
                background = JBColor.background()
                maximumSize = Dimension(Int.MAX_VALUE, 30)

                val leftControls = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    background = JBColor.background()
                    add(modeComboBox.apply { preferredSize = Dimension(180, 24); font = font.deriveFont(11f) })
                    add(modeIndicator)
                }

                val rightControls = JPanel(FlowLayout(FlowLayout.RIGHT, 3, 0)).apply {
                    background = JBColor.background()
                    add(clearButton.apply { preferredSize = Dimension(48, 24) })
                    add(promptsButton.apply { preferredSize = Dimension(26, 24) })
                }

                add(leftControls, BorderLayout.WEST)
                add(rightControls, BorderLayout.EAST)
            }

            // Row 2: Breadcrumb + navigation
            val navRow = JPanel(BorderLayout(4, 0)).apply {
                background = JBColor.background()
                maximumSize = Dimension(Int.MAX_VALUE, 28)
                border = JBUI.Borders.empty(2, 0, 0, 0)

                val breadcrumbScroll = JScrollPane(breadcrumbPanel).apply {
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
                    add(sessionsButton.apply { preferredSize = Dimension(86, 22) })
                }

                add(breadcrumbScroll, BorderLayout.CENTER)
                add(navButtons, BorderLayout.EAST)
            }

            add(controlRow)
            add(navRow)
        }

        // ===== Chat =====
        val chatScroll = JBScrollPane(chatArea).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        // ===== Trace bar =====
        val traceBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            background = JBColor.background()
            border = JBUI.Borders.empty(2, 8, 0, 8)
            add(traceIndicator)
            add(clearTraceButton)
            isVisible = false
        }

        // ===== Input =====
        val inputPanel = JPanel(BorderLayout(5, 4)).apply {
            border = JBUI.Borders.empty(4, 8, 8, 8)
            background = JBColor.background()

            add(traceBar, BorderLayout.NORTH)

            val inputWrapper = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.customLine(JBColor.border(), 1)
                add(inputArea, BorderLayout.CENTER)
            }
            add(inputWrapper, BorderLayout.CENTER)

            val buttonsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                background = JBColor.background()
                add(attachTraceButton.apply { preferredSize = Dimension(80, 26) })
                add(dryRunCheckbox)
                add(sendButton)
            }
            add(buttonsPanel, BorderLayout.SOUTH)
        }

        // ===== Status =====
        val statusBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 10)
            background = JBColor.background()
            add(statusLabel, BorderLayout.WEST)
            add(JBLabel("<html><small>Ctrl+Enter send | Ctrl+Shift+V trace</small></html>").apply {
                foreground = JBColor.GRAY
            }, BorderLayout.EAST)
        }

        add(headerPanel, BorderLayout.NORTH)
        add(chatScroll, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(inputPanel, BorderLayout.CENTER)
            add(statusBar, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
    }

    private fun setupListeners() {
        sendButton.addActionListener { sendMessage() }
        sessionsButton.addActionListener { onShowSessions() }

        newChatButton.addActionListener {
            resetClipboard()
            chatHistory.createNewSession()
            clearAttachedTrace()
            loadCurrentSession()
            updateModeIndicator()
            statusLabel.text = "New dialog"
        }

        branchButton.addActionListener {
            val active = chatHistory.getActiveSession()
            val title = JOptionPane.showInputDialog(
                this, "Name for the new branch:",
                "New Branch", JOptionPane.PLAIN_MESSAGE,
                null, null, "Branch: ${active.title.take(25)}"
            ) as? String ?: return@addActionListener

            resetClipboard()
            val branch = chatHistory.createBranch(active.id, title)
            if (branch != null) {
                clearAttachedTrace()
                loadCurrentSession()
                updateModeIndicator()
                statusLabel.text = "Branch: ${branch.title}"
            }
        }

        clearButton.addActionListener {
            resetClipboard()
            chatHistory.clearActiveSession()
            clearAttachedTrace()
            loadCurrentSession()
            updateModeIndicator()
        }

        promptsButton.addActionListener {
            promptService.openOrCreatePrompts()
            statusLabel.text = "Prompts opened"
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
                if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) {
                    sendMessage(); e.consume()
                } else if (e.keyCode == KeyEvent.VK_V && e.isControlDown && e.isShiftDown) {
                    attachTraceFromClipboard(); e.consume()
                }
            }
        })
    }

    // ==================== Breadcrumb ====================

    fun refreshHeader() {
        updateBreadcrumb()
        updateModeIndicator()
    }

    private fun updateBreadcrumb() {
        breadcrumbPanel.removeAll()
        val session = chatHistory.getActiveSession()
        val path = chatHistory.getSessionPath(session.id)

        for ((i, s) in path.withIndex()) {
            val isLast = i == path.size - 1
            if (i > 0) {
                breadcrumbPanel.add(JBLabel(" \u203A ").apply {
                    foreground = JBColor.GRAY; font = font.deriveFont(11f)
                })
            }
            val label = JBLabel(s.title.take(20) + if (s.title.length > 20) ".." else "").apply {
                font = if (isLast) font.deriveFont(Font.BOLD, 11f) else font.deriveFont(11f)
                foreground = if (isLast) JBColor.foreground()
                else JBColor(Color(0x2196F3), Color(0x64B5F6))
                border = JBUI.Borders.empty(2, 3)
                if (!isLast) {
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    val sid = s.id
                    addMouseListener(object : java.awt.event.MouseAdapter() {
                        override fun mouseClicked(e: java.awt.event.MouseEvent) {
                            chatHistory.setActiveSession(sid)
                            loadCurrentSession()
                        }
                    })
                }
            }
            breadcrumbPanel.add(label)
        }

        breadcrumbPanel.revalidate()
        breadcrumbPanel.repaint()
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
        currentMode = newMode
        settings.interactionMode = newMode.name
        updateModeIndicator()
        if (newMode == InteractionMode.CHEAP_API) service.ensureCheapLLMService()
        val label = MaxVibesSettings.INTERACTION_MODES.find { it.first == newMode.name }?.second ?: newMode.name
        statusLabel.text = "Mode: $label"
        appendToChat("\n\u2699\uFE0F Switched to $label\n")
    }

    private fun updateModeIndicator() {
        when (currentMode) {
            InteractionMode.API -> {
                modeIndicator.isVisible = false
                sendButton.text = "Send"
                dryRunCheckbox.isVisible = true
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
                        modeIndicator.isVisible = true
                        sendButton.text = "Paste"
                    }
                    cs.hasActiveSession() -> {
                        modeIndicator.text = "\uD83D\uDCCB Active"
                        modeIndicator.isVisible = true
                        sendButton.text = "Send / Paste"
                    }
                    else -> {
                        modeIndicator.text = "\uD83D\uDCCB"
                        modeIndicator.isVisible = true
                        sendButton.text = "Generate"
                    }
                }
                dryRunCheckbox.isVisible = false
            }
            InteractionMode.CHEAP_API -> {
                modeIndicator.text = "\uD83D\uDCB0"
                modeIndicator.isVisible = true
                sendButton.text = "Send"
                dryRunCheckbox.isVisible = true
            }
        }
    }

    // ==================== Trace ====================

    private fun attachTraceFromClipboard() {
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val content = clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
            if (content.isNullOrBlank()) { statusLabel.text = "Clipboard is empty"; return }
            attachedTrace = content
            updateTraceIndicator()
            val lines = content.lines().size
            val preview = content.lines().take(5).joinToString("\n")
            val suffix = if (lines > 5) "\n   ... ($lines lines total)" else ""
            appendToChat("\n\uD83D\uDCCE Trace attached (${content.length} chars):\n   $preview$suffix\n")
            statusLabel.text = "Trace attached"
        } catch (e: Exception) {
            statusLabel.text = "Clipboard error: ${e.message}"
        }
    }

    private fun clearAttachedTrace() {
        if (attachedTrace != null) { attachedTrace = null; updateTraceIndicator(); statusLabel.text = "Trace removed" }
    }

    private fun updateTraceIndicator() {
        val trace = attachedTrace
        val has = !trace.isNullOrBlank()
        traceIndicator.isVisible = has
        clearTraceButton.isVisible = has
        traceIndicator.parent?.isVisible = has
        if (has) traceIndicator.text = "\uD83D\uDCCE Trace: ${trace!!.lines().size}L / ${trace.length}ch"
    }

    // ==================== Send ====================

    private fun sendMessage() {
        val userInput = inputArea.text.trim()
        if (userInput.isBlank()) return
        val trace = attachedTrace
        clearAttachedTrace()
        when (currentMode) {
            InteractionMode.API -> sendApiMessage(userInput, trace)
            InteractionMode.CLIPBOARD -> sendClipboardMessage(userInput, trace)
            InteractionMode.CHEAP_API -> sendCheapApiMessage(userInput, trace)
        }
    }

    private fun sendApiMessage(msg: String, trace: String?) {
        val isDryRun = dryRunCheckbox.isSelected
        val session = chatHistory.getActiveSession()
        appendToChat("\n\uD83D\uDC64 You:\n$msg\n")
        if (!trace.isNullOrBlank()) appendToChat("\uD83D\uDCCE [trace: ${trace.lines().size} lines]\n")
        val fullTask = buildTaskWithTrace(msg, trace)
        session.addMessage(MessageRole.USER, fullTask)
        inputArea.text = ""; setInputEnabled(false); statusLabel.text = "Thinking..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MaxVibes: Processing...", true) {
            override fun run(indicator: ProgressIndicator) {
                service.notificationService.setProgressIndicator(indicator)
                runBlocking {
                    val dtos = session.messages.dropLast(1).map { it.toChatMessageDTO() }
                    val req = ContextAwareRequest(task = fullTask, history = dtos, dryRun = isDryRun)
                    val result = service.contextAwareModifyUseCase.execute(req)
                    ApplicationManager.getApplication().invokeLater { handleApiResult(result, session) }
                }
            }
            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    session.addMessage(MessageRole.SYSTEM, "Cancelled")
                    appendToChat("\n\u26A0\uFE0F Cancelled\n"); setInputEnabled(true); statusLabel.text = "Cancelled"
                }
            }
        })
    }

    private fun sendClipboardMessage(userInput: String, trace: String?) {
        val cs = service.clipboardService
        val session = chatHistory.getActiveSession()
        inputArea.text = ""

        when {
            cs.isWaitingForResponse() -> {
                session.addMessage(MessageRole.USER, "[Pasted LLM response]")
                appendToChat("\n\uD83D\uDCE5 Pasted response (${userInput.length} chars)\n")
                setInputEnabled(false); statusLabel.text = "Processing..."
                runClipboardBg("Processing response...") { cs.handlePastedResponse(userInput) }
            }
            cs.hasActiveSession() -> {
                appendToChat("\n\uD83D\uDC64 You:\n$userInput\n")
                if (!trace.isNullOrBlank()) appendToChat("\uD83D\uDCCE [trace: ${trace.lines().size} lines]\n")
                session.addMessage(MessageRole.USER, userInput)
                setInputEnabled(false); statusLabel.text = "Continuing..."
                runClipboardBg("Generating follow-up...") { cs.continueDialog(userInput, trace) }
            }
            else -> {
                appendToChat("\n\uD83D\uDC64 You:\n$userInput\n")
                if (!trace.isNullOrBlank()) appendToChat("\uD83D\uDCCE [trace: ${trace.lines().size} lines]\n")
                session.addMessage(MessageRole.USER, userInput)
                setInputEnabled(false); statusLabel.text = "Generating JSON..."
                runClipboardBg("Generating request...") {
                    val dtos = session.messages.dropLast(1).map { it.toChatMessageDTO() }
                    cs.startTask(userInput, dtos, trace)
                }
            }
        }
    }

    private fun runClipboardBg(title: String, action: suspend () -> ClipboardStepResult) {
        val session = chatHistory.getActiveSession()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MaxVibes: $title", true) {
            override fun run(indicator: ProgressIndicator) {
                service.notificationService.setProgressIndicator(indicator)
                runBlocking {
                    val result = action()
                    ApplicationManager.getApplication().invokeLater { handleClipboardResult(result, session) }
                }
            }
            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    service.clipboardService.reset()
                    appendToChat("\n\u26A0\uFE0F Cancelled\n"); setInputEnabled(true); updateModeIndicator()
                }
            }
        })
    }

    private fun sendCheapApiMessage(msg: String, trace: String?) {
        val isDryRun = dryRunCheckbox.isSelected
        val session = chatHistory.getActiveSession()
        appendToChat("\n\uD83D\uDC64 You:\n$msg\n")
        if (!trace.isNullOrBlank()) appendToChat("\uD83D\uDCCE [trace: ${trace.lines().size} lines]\n")
        val fullTask = buildTaskWithTrace(msg, trace)
        session.addMessage(MessageRole.USER, fullTask)
        inputArea.text = ""; setInputEnabled(false); statusLabel.text = "Thinking (cheap)..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MaxVibes: Processing (budget)...", true) {
            override fun run(indicator: ProgressIndicator) {
                service.notificationService.setProgressIndicator(indicator)
                runBlocking {
                    val dtos = session.messages.dropLast(1).map { it.toChatMessageDTO() }
                    val req = ContextAwareRequest(task = fullTask, history = dtos, dryRun = isDryRun)
                    val uc = service.cheapContextAwareModifyUseCase ?: service.contextAwareModifyUseCase
                    val result = uc.execute(req)
                    ApplicationManager.getApplication().invokeLater { handleApiResult(result, session) }
                }
            }
            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    session.addMessage(MessageRole.SYSTEM, "Cancelled")
                    appendToChat("\n\u26A0\uFE0F Cancelled\n"); setInputEnabled(true); statusLabel.text = "Cancelled"
                }
            }
        })
    }

    // ==================== Result Handlers ====================

    private fun handleApiResult(result: ContextAwareResult, session: ChatSession) {
        val text = buildResultText(result)
        session.addMessage(MessageRole.ASSISTANT, text)
        appendAssistantMessage(text)
        appendToChat("\u2500".repeat(50) + "\n")
        setInputEnabled(true); statusLabel.text = if (result.success) "Ready" else "Errors"
        updateBreadcrumb()
    }

    private fun handleClipboardResult(result: ClipboardStepResult, session: ChatSession) {
        when (result) {
            is ClipboardStepResult.WaitingForResponse -> {
                session.addMessage(MessageRole.ASSISTANT, result.userMessage)
                appendToChat("\n\uD83D\uDCCB MaxVibes:\n${formatMarkdown(result.userMessage)}\n")
                appendToChat("\u2500".repeat(50) + "\n")
                setInputEnabled(true); updateModeIndicator()
                statusLabel.text = "Waiting for LLM response..."
            }
            is ClipboardStepResult.Completed -> {
                val text = buildString {
                    if (result.message.isNotBlank()) append(result.message)
                    else if (result.modifications.isNotEmpty()) append("Changes applied.")
                    else append("Done.")
                    if (result.modifications.isNotEmpty()) {
                        appendLine("\n───────────────")
                        val ok = result.modifications.filterIsInstance<ModificationResult.Success>()
                        val fail = result.modifications.filterIsInstance<ModificationResult.Failure>()
                        if (ok.isNotEmpty()) { appendLine("\u2705 ${ok.size} applied:"); ok.forEach { appendLine("   \u2022 ${it.affectedPath.value.substringAfterLast('/')}") } }
                        if (fail.isNotEmpty()) { appendLine("\u274C ${fail.size} failed:"); fail.forEach { appendLine("   \u2022 ${it.error.message}") } }
                    }
                }
                session.addMessage(MessageRole.ASSISTANT, text)
                appendAssistantMessage(text)
                appendToChat("\u2500".repeat(50) + "\n")
                setInputEnabled(true); updateModeIndicator()
                val hint = if (service.clipboardService.hasActiveSession()) " \u2022 Session active" else ""
                statusLabel.text = (if (result.success) "Ready" else "Errors") + hint
                updateBreadcrumb()
            }
            is ClipboardStepResult.Error -> {
                session.addMessage(MessageRole.SYSTEM, "Error: ${result.message}")
                appendToChat("\n\u274C ${result.message}\n")
                setInputEnabled(true); updateModeIndicator(); statusLabel.text = "Error"
            }
        }
    }

    private fun buildResultText(result: ContextAwareResult): String = buildString {
        append(result.message)
        if (result.modifications.isNotEmpty()) {
            appendLine("\n\n───────────────")
            val ok = result.modifications.filterIsInstance<ModificationResult.Success>()
            val fail = result.modifications.filterIsInstance<ModificationResult.Failure>()
            if (ok.isNotEmpty()) { appendLine("\u2705 ${ok.size} applied:"); ok.forEach { appendLine("   \u2022 ${it.affectedPath.value.substringAfterLast('/')}") } }
            if (fail.isNotEmpty()) { appendLine("\u274C ${fail.size} failed:"); fail.forEach { appendLine("   \u2022 ${it.error.message}") } }
        }
    }

    // ==================== Session ====================

    fun loadCurrentSession() {
        val session = chatHistory.getActiveSession()
        chatArea.text = ""
        updateBreadcrumb()
        updateModeIndicator()

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

    private fun showWelcome() {
        val mode = when (currentMode) {
            InteractionMode.API -> "API — direct LLM calls"
            InteractionMode.CLIPBOARD -> "Clipboard — paste JSON into Claude/ChatGPT"
            InteractionMode.CHEAP_API -> "Cheap API — budget model"
        }
        val session = chatHistory.getActiveSession()
        val branchHint = if (session.depth > 0) {
            val parent = chatHistory.getParent(session.id)
            "\n  \u2514 Branch from: \"${parent?.title ?: "?"}\""
        } else ""

        appendToChat("""
            |  MaxVibes  \u2022  $mode$branchHint
            |
            |  Type your task, or use Sessions to browse dialogs.
            |  Ctrl+Enter send | Ctrl+Shift+V trace
            |
        """.trimMargin() + "\n")
    }

    fun resetClipboard() { service.clipboardService.reset() }

    // ==================== Utilities ====================

    private fun buildTaskWithTrace(task: String, trace: String?): String {
        if (trace.isNullOrBlank()) return task
        return "$task\n\n--- Error/Trace/Logs ---\n$trace"
    }

    private fun appendToChat(text: String) {
        chatArea.append(text); chatArea.caretPosition = chatArea.document.length
    }

    private fun appendAssistantMessage(text: String) {
        appendToChat("\n\uD83E\uDD16 MaxVibes:\n${formatMarkdown(text)}\n")
    }

    private fun formatMarkdown(text: String): String {
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

    private fun setInputEnabled(enabled: Boolean) {
        inputArea.isEnabled = enabled; sendButton.isEnabled = enabled
        dryRunCheckbox.isEnabled = enabled; attachTraceButton.isEnabled = enabled
        clearTraceButton.isEnabled = enabled; clearButton.isEnabled = enabled
        promptsButton.isEnabled = enabled; modeComboBox.isEnabled = enabled
        sessionsButton.isEnabled = enabled; branchButton.isEnabled = enabled
        newChatButton.isEnabled = enabled
    }
}

// ==================== Helpers ====================

private fun ChatMessage.toChatMessageDTO(): ChatMessageDTO {
    return ChatMessageDTO(
        role = when (this.role) {
            MessageRole.USER -> ChatRole.USER
            MessageRole.ASSISTANT -> ChatRole.ASSISTANT
            MessageRole.SYSTEM -> ChatRole.SYSTEM
        },
        content = this.content
    )
}

data class ModeItem(val id: String, val label: String) {
    override fun toString(): String = label
}