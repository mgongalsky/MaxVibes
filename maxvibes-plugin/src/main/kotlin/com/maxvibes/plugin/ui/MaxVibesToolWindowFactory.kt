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
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*

class MaxVibesToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MaxVibesToolPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class MaxVibesToolPanel(private val project: Project) : JPanel(BorderLayout()) {

    // ==================== UI Components ====================

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

    private val clearButton = JButton("Clear").apply {
        toolTipText = "Clear current chat"
    }

    private val promptsButton = JButton("\u2699").apply {
        toolTipText = "Edit prompts (.maxvibes/prompts/)"
    }

    /** Tree navigator replaces the old sessionComboBox + newChatButton */
    private val treeNavigator: ChatTreeNavigator = ChatTreeNavigator(project) { sessionId ->
        chatHistory.setActiveSession(sessionId)
        loadCurrentSession()
        // refresh() вызывается внутри loadCurrentSession() через treeNavigator.refresh()
    }

    /** Mode selector */
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

    // ==================== Services ====================

    private val service: MaxVibesService by lazy { MaxVibesService.getInstance(project) }
    private val chatHistory: ChatHistoryService by lazy { ChatHistoryService.getInstance(project) }
    private val promptService: PromptService by lazy { PromptService.getInstance(project) }
    private val settings: MaxVibesSettings by lazy { MaxVibesSettings.getInstance() }

    private var currentMode: InteractionMode = InteractionMode.API

    /** Attached error trace / stacktrace / logs */
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

        // ===== Header: Mode row + Tree Navigator =====
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 0)
            background = JBColor.background()

            // Row 1: Mode selector + indicator + prompts
            val modeRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                background = JBColor.background()
                add(JBLabel("<html><b>MaxVibes</b></html>"))
                add(modeComboBox.apply { preferredSize = Dimension(200, 24); font = font.deriveFont(11f) })
                add(modeIndicator)
                add(clearButton.apply { preferredSize = Dimension(50, 24); font = font.deriveFont(11f) })
                add(promptsButton.apply { preferredSize = Dimension(28, 24); font = font.deriveFont(11f) })
                if (promptService.hasCustomPrompts()) {
                    add(JBLabel("\u270E").apply { toolTipText = "Using custom prompts"; foreground = JBColor.BLUE })
                }
            }

            // Row 2-3: Tree navigator (breadcrumb + branch controls)
            add(modeRow)
            add(treeNavigator)
        }

        // ===== Chat Area =====
        val chatScroll = JBScrollPane(chatArea).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        // ===== Trace indicator bar (shown when trace is attached) =====
        val traceBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            background = JBColor.background()
            border = JBUI.Borders.empty(2, 8, 0, 8)
            add(traceIndicator)
            add(clearTraceButton)
            isVisible = false  // controlled by updateTraceIndicator()
        }

        // ===== Input Area =====
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

        // ===== Status Bar =====
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

        // Tree navigator callbacks
        treeNavigator.onNewRoot = {
            service.clipboardService.reset()
            chatHistory.createNewSession()
            clearAttachedTrace()
            loadCurrentSession()
            treeNavigator.refresh()
            updateModeIndicator()
            statusLabel.text = "New root dialog created"
        }

        treeNavigator.onNewBranch = {
            val activeSession = chatHistory.getActiveSession()
            val branchTitle = promptForBranchTitle(activeSession.title)
            if (branchTitle != null) {
                service.clipboardService.reset()
                val branch = chatHistory.createBranch(activeSession.id, branchTitle)
                if (branch != null) {
                    clearAttachedTrace()
                    loadCurrentSession()
                    treeNavigator.refresh()
                    updateModeIndicator()
                    statusLabel.text = "Branch created: ${branch.title}"
                } else {
                    statusLabel.text = "Failed to create branch"
                }
            }
        }

        treeNavigator.onDeleteSession = { sessionId ->
            val session = chatHistory.getSessionById(sessionId)
            if (session != null) {
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
                    loadCurrentSession()
                    treeNavigator.refresh()
                    statusLabel.text = "Dialog deleted"
                }
            }
        }

        clearButton.addActionListener {
            service.clipboardService.reset()
            chatHistory.clearActiveSession()
            clearAttachedTrace()
            loadCurrentSession()
            updateModeIndicator()
        }

        promptsButton.addActionListener {
            promptService.openOrCreatePrompts()
            statusLabel.text = "Prompts opened in editor"
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
                    sendMessage()
                    e.consume()
                } else if (e.keyCode == KeyEvent.VK_V && e.isControlDown && e.isShiftDown) {
                    attachTraceFromClipboard()
                    e.consume()
                }
            }
        })
    }

    // ==================== Branch Title Dialog ====================

    private fun promptForBranchTitle(parentTitle: String): String? {
        val defaultTitle = "Branch: ${parentTitle.take(25)}"
        return JOptionPane.showInputDialog(
            this,
            "Name for the new branch:",
            "New Branch",
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            defaultTitle
        ) as? String
    }

    // ==================== Mode Switching ====================

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

        val modeLabel = MaxVibesSettings.INTERACTION_MODES.find { it.first == newMode.name }?.second ?: newMode.name
        statusLabel.text = "Mode: $modeLabel"
        appendToChat("\n\u2699\uFE0F Switched to $modeLabel\n")
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
                            ClipboardPhase.PLANNING -> "\u23F3 Paste LLM response (planning)"
                            ClipboardPhase.CHAT -> "\u23F3 Paste LLM response (chat)"
                            else -> "\u23F3 Paste LLM response"
                        }
                        modeIndicator.isVisible = true
                        sendButton.text = "Paste Response"
                    }
                    cs.hasActiveSession() -> {
                        modeIndicator.text = "\uD83D\uDCCB Session active — continue or paste"
                        modeIndicator.isVisible = true
                        sendButton.text = "Send / Paste"
                    }
                    else -> {
                        modeIndicator.text = "\uD83D\uDCCB"
                        modeIndicator.isVisible = true
                        sendButton.text = "Generate JSON"
                    }
                }
                dryRunCheckbox.isVisible = false
            }
            InteractionMode.CHEAP_API -> {
                modeIndicator.text = "\uD83D\uDCB0"
                modeIndicator.isVisible = true
                sendButton.text = "Send (Cheap)"
                dryRunCheckbox.isVisible = true
            }
        }
    }

    // ==================== Trace Attachment ====================

    private fun attachTraceFromClipboard() {
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val content = clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
            if (content.isNullOrBlank()) {
                statusLabel.text = "Clipboard is empty"
                return
            }

            attachedTrace = content
            updateTraceIndicator()

            val preview = content.lines().take(5).joinToString("\n")
            val totalLines = content.lines().size
            val suffix = if (totalLines > 5) "\n   ... ($totalLines lines total)" else ""
            appendToChat("\n\uD83D\uDCCE Trace attached (${content.length} chars, $totalLines lines):\n   $preview$suffix\n")

            statusLabel.text = "Trace attached — will be included in next request"
        } catch (e: Exception) {
            statusLabel.text = "Failed to read clipboard: ${e.message}"
        }
    }

    private fun clearAttachedTrace() {
        if (attachedTrace != null) {
            attachedTrace = null
            updateTraceIndicator()
            statusLabel.text = "Trace removed"
        }
    }

    private fun updateTraceIndicator() {
        val trace = attachedTrace
        val hasTrace = !trace.isNullOrBlank()

        traceIndicator.isVisible = hasTrace
        clearTraceButton.isVisible = hasTrace
        traceIndicator.parent?.isVisible = hasTrace

        if (hasTrace) {
            val lines = trace!!.lines().size
            val chars = trace.length
            traceIndicator.text = "\uD83D\uDCCE Trace: ${lines}L / ${chars}ch"
        }
    }

    // ==================== Send Message ====================

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

    // --- API Mode ---

    private fun sendApiMessage(userMessage: String, trace: String? = null) {
        val isDryRun = dryRunCheckbox.isSelected
        val session = chatHistory.getActiveSession()

        appendToChat("\n\uD83D\uDC64 You:\n$userMessage\n")
        if (!trace.isNullOrBlank()) {
            appendToChat("\uD83D\uDCCE [trace attached: ${trace.lines().size} lines]\n")
        }

        val fullTask = buildTaskWithTrace(userMessage, trace)
        session.addMessage(MessageRole.USER, fullTask)
        inputArea.text = ""
        setInputEnabled(false)
        statusLabel.text = "Thinking..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MaxVibes: Processing...", true) {
            override fun run(indicator: ProgressIndicator) {
                service.notificationService.setProgressIndicator(indicator)
                runBlocking {
                    val historyDTOs = session.messages.dropLast(1).map { it.toChatMessageDTO() }
                    val request = ContextAwareRequest(task = fullTask, history = historyDTOs, dryRun = isDryRun)
                    val result = service.contextAwareModifyUseCase.execute(request)
                    ApplicationManager.getApplication().invokeLater { handleApiResult(result, session) }
                }
            }
            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    session.addMessage(MessageRole.SYSTEM, "Operation cancelled")
                    appendToChat("\n\u26A0\uFE0F Operation cancelled\n")
                    setInputEnabled(true); statusLabel.text = "Cancelled"
                }
            }
        })
    }

    // --- Clipboard Mode ---

    /**
     * Clipboard mode has 3 states:
     * 1. Waiting for paste → handlePastedResponse (process LLM output)
     * 2. Active session, not waiting → continueDialog (user sends follow-up)
     * 3. No session → startTask (fresh start)
     */
    private fun sendClipboardMessage(userInput: String, trace: String? = null) {
        val cs = service.clipboardService
        val session = chatHistory.getActiveSession()
        inputArea.text = ""

        when {
            // State 1: Waiting for LLM response paste
            cs.isWaitingForResponse() -> {
                session.addMessage(MessageRole.USER, "[Pasted LLM response]")
                appendToChat("\n\uD83D\uDCE5 Pasted LLM response (${userInput.length} chars)\n")
                setInputEnabled(false); statusLabel.text = "Processing response..."

                runClipboardBackground("MaxVibes: Processing response...") {
                    cs.handlePastedResponse(userInput)
                }
            }

            // State 2: Active session, user continues dialog
            cs.hasActiveSession() -> {
                appendToChat("\n\uD83D\uDC64 You:\n$userInput\n")
                if (!trace.isNullOrBlank()) {
                    appendToChat("\uD83D\uDCCE [trace attached: ${trace.lines().size} lines]\n")
                }
                session.addMessage(MessageRole.USER, userInput)
                setInputEnabled(false); statusLabel.text = "Continuing dialog..."

                runClipboardBackground("MaxVibes: Generating follow-up...") {
                    cs.continueDialog(userInput, attachedContext = trace)
                }
            }

            // State 3: No session — new task
            else -> {
                appendToChat("\n\uD83D\uDC64 You:\n$userInput\n")
                if (!trace.isNullOrBlank()) {
                    appendToChat("\uD83D\uDCCE [trace attached: ${trace.lines().size} lines]\n")
                }
                session.addMessage(MessageRole.USER, userInput)
                setInputEnabled(false); statusLabel.text = "Generating JSON..."

                runClipboardBackground("MaxVibes: Generating request...") {
                    val historyDTOs = session.messages.dropLast(1).map { it.toChatMessageDTO() }
                    cs.startTask(userInput, historyDTOs, attachedContext = trace)
                }
            }
        }
    }

    /** Helper to run clipboard operations in background */
    private fun runClipboardBackground(
        title: String,
        action: suspend () -> ClipboardStepResult
    ) {
        val session = chatHistory.getActiveSession()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
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
                    appendToChat("\n\u26A0\uFE0F Cancelled. Clipboard session reset.\n")
                    setInputEnabled(true); updateModeIndicator()
                }
            }
        })
    }

    // --- Cheap API Mode ---

    private fun sendCheapApiMessage(userMessage: String, trace: String? = null) {
        val isDryRun = dryRunCheckbox.isSelected
        val session = chatHistory.getActiveSession()

        appendToChat("\n\uD83D\uDC64 You:\n$userMessage\n")
        if (!trace.isNullOrBlank()) {
            appendToChat("\uD83D\uDCCE [trace attached: ${trace.lines().size} lines]\n")
        }

        val fullTask = buildTaskWithTrace(userMessage, trace)
        session.addMessage(MessageRole.USER, fullTask)
        inputArea.text = ""
        setInputEnabled(false)
        statusLabel.text = "Thinking (cheap mode)..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MaxVibes: Processing (budget)...", true) {
            override fun run(indicator: ProgressIndicator) {
                service.notificationService.setProgressIndicator(indicator)
                runBlocking {
                    val historyDTOs = session.messages.dropLast(1).map { it.toChatMessageDTO() }
                    val request = ContextAwareRequest(task = fullTask, history = historyDTOs, dryRun = isDryRun)
                    val useCase = service.cheapContextAwareModifyUseCase ?: service.contextAwareModifyUseCase
                    val result = useCase.execute(request)
                    ApplicationManager.getApplication().invokeLater { handleApiResult(result, session) }
                }
            }
            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    session.addMessage(MessageRole.SYSTEM, "Operation cancelled")
                    appendToChat("\n\u26A0\uFE0F Operation cancelled\n")
                    setInputEnabled(true); statusLabel.text = "Cancelled"
                }
            }
        })
    }

    // ==================== Result Handlers ====================

    private fun handleApiResult(result: ContextAwareResult, session: ChatSession) {
        val responseText = buildResultText(result)
        session.addMessage(MessageRole.ASSISTANT, responseText)
        appendAssistantMessage(responseText)
        appendToChat("\u2500".repeat(50) + "\n")
        setInputEnabled(true)
        statusLabel.text = if (result.success) "Ready" else "Completed with errors"
        treeNavigator.refresh()
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
                    if (result.message.isNotBlank()) {
                        append(result.message)
                    } else if (result.modifications.isNotEmpty()) {
                        append("Code changes applied successfully.")
                    } else {
                        append("Done (no changes).")
                    }
                    if (result.modifications.isNotEmpty()) {
                        appendLine("\n\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500")
                        val ok = result.modifications.filterIsInstance<ModificationResult.Success>()
                        val fail = result.modifications.filterIsInstance<ModificationResult.Failure>()
                        if (ok.isNotEmpty()) { appendLine("\u2705 Applied ${ok.size} change(s):"); ok.forEach { appendLine("   \u2022 ${it.affectedPath.value.substringAfterLast('/')}") } }
                        if (fail.isNotEmpty()) { appendLine("\u274C Failed ${fail.size} change(s):"); fail.forEach { appendLine("   \u2022 ${it.error.message}") } }
                    }
                }
                session.addMessage(MessageRole.ASSISTANT, text)
                appendAssistantMessage(text)
                appendToChat("\u2500".repeat(50) + "\n")
                setInputEnabled(true); updateModeIndicator()
                val sessionHint = if (service.clipboardService.hasActiveSession())
                    " \u2022 Session active, you can continue" else ""
                statusLabel.text = (if (result.success) "Ready" else "Completed with errors") + sessionHint
                treeNavigator.refresh()
            }
            is ClipboardStepResult.Error -> {
                session.addMessage(MessageRole.SYSTEM, "Error: ${result.message}")
                appendToChat("\n\u274C Error: ${result.message}\n")
                setInputEnabled(true); updateModeIndicator()
                statusLabel.text = "Error"
            }
        }
    }

    private fun buildResultText(result: ContextAwareResult): String = buildString {
        append(result.message)
        if (result.modifications.isNotEmpty()) {
            appendLine("\n\n\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500")
            val ok = result.modifications.filterIsInstance<ModificationResult.Success>()
            val fail = result.modifications.filterIsInstance<ModificationResult.Failure>()
            if (ok.isNotEmpty()) { appendLine("\u2705 Applied ${ok.size} change(s):"); ok.forEach { appendLine("   \u2022 ${it.affectedPath.value.substringAfterLast('/')}") } }
            if (fail.isNotEmpty()) { appendLine("\u274C Failed ${fail.size} change(s):"); fail.forEach { appendLine("   \u2022 ${it.error.message}") } }
        }
    }

    // ==================== Session Management ====================

    private fun loadCurrentSession() {
        val session = chatHistory.getActiveSession()
        chatArea.text = ""
        treeNavigator.refresh()

        if (session.messages.isEmpty()) {
            showWelcome()
        } else {
            // Показываем breadcrumb-контекст при загрузке ветки
            val path = chatHistory.getSessionPath(session.id)
            if (path.size > 1) {
                val parentChain = path.dropLast(1).joinToString(" \u203A ") { it.title.take(25) }
                appendToChat("\u2514 Branch of: $parentChain\n")
                appendToChat("\u2500".repeat(50) + "\n")
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
        val modeHint = when (currentMode) {
            InteractionMode.API -> "Mode: API — direct LLM calls"
            InteractionMode.CLIPBOARD -> "Mode: Clipboard — paste JSON into Claude chat"
            InteractionMode.CHEAP_API -> "Mode: Cheap API — budget-friendly model"
        }

        val session = chatHistory.getActiveSession()
        val depth = session.depth
        val branchHint = if (depth > 0) {
            val parent = chatHistory.getParent(session.id)
            "\n  \u2514 This is a branch (depth $depth) from: \"${parent?.title ?: "?"}\""
        } else ""

        appendToChat("""
            |  Welcome to MaxVibes!
            |  $modeHint$branchHint
            |
            |  Just type your task:
            |  \u2022 "Add a Logger class with debug and error methods"
            |  \u2022 "Create unit tests for UserService"
            |  \u2022 "Fix this error" + \uD83D\uDCCE Trace button to attach stacktrace
            |
            |  Navigation: use breadcrumbs above or \u25BC for tree view
            |  Branch: split current dialog into a sub-branch
            |
        """.trimMargin() + "\n")
    }

    // ==================== Utilities ====================

    private fun buildTaskWithTrace(task: String, trace: String?): String {
        if (trace.isNullOrBlank()) return task
        return buildString {
            appendLine(task)
            appendLine()
            appendLine("--- Error/Trace/Logs ---")
            append(trace)
        }
    }

    private fun appendToChat(text: String) {
        chatArea.append(text)
        chatArea.caretPosition = chatArea.document.length
    }

    private fun appendAssistantMessage(text: String) {
        appendToChat("\n\uD83E\uDD16 MaxVibes:\n${formatMarkdown(text)}\n")
    }

    private fun formatMarkdown(text: String): String {
        return text.lines().joinToString("\n") { line ->
            var l = line

            val h3 = Regex("^###\\s+(.+)").find(l)
            if (h3 != null) return@joinToString "  \u2500\u2500\u2500 ${h3.groupValues[1].trim()} \u2500\u2500\u2500"

            val h2 = Regex("^##\\s+(.+)").find(l)
            if (h2 != null) return@joinToString "\u2550\u2550 ${h2.groupValues[1].trim().uppercase()} \u2550\u2550"

            val h1 = Regex("^#\\s+(.+)").find(l)
            if (h1 != null) return@joinToString "\u2550\u2550\u2550 ${h1.groupValues[1].trim().uppercase()} \u2550\u2550\u2550"

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
        inputArea.isEnabled = enabled
        sendButton.isEnabled = enabled
        dryRunCheckbox.isEnabled = enabled
        attachTraceButton.isEnabled = enabled
        clearTraceButton.isEnabled = enabled
        clearButton.isEnabled = enabled
        promptsButton.isEnabled = enabled
        modeComboBox.isEnabled = enabled
    }
}

// ==================== Helper Classes ====================

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