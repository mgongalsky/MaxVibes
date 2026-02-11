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

    private val newChatButton = JButton("New").apply {
        toolTipText = "Start new chat"
    }

    private val clearButton = JButton("Clear").apply {
        toolTipText = "Clear current chat"
    }

    private val promptsButton = JButton("\u2699").apply {
        toolTipText = "Edit prompts (.maxvibes/prompts/)"
    }

    private val sessionComboBox = ComboBox<SessionItem>().apply {
        renderer = SessionListRenderer()
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

        // ===== Header: Two rows to avoid overlapping =====
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 8)
            background = JBColor.background()

            // Row 1: Mode selector + indicator
            val row1 = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                background = JBColor.background()
                add(JBLabel("<html><b>MaxVibes</b></html>"))
                add(modeComboBox.apply { preferredSize = Dimension(200, 24); font = font.deriveFont(11f) })
                add(modeIndicator)
                if (promptService.hasCustomPrompts()) {
                    add(JBLabel("\u270E").apply { toolTipText = "Using custom prompts"; foreground = JBColor.BLUE })
                }
            }

            // Row 2: Session controls
            val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 3, 2)).apply {
                background = JBColor.background()
                add(sessionComboBox.apply { preferredSize = Dimension(180, 24) })
                add(newChatButton.apply { preferredSize = Dimension(50, 24); font = font.deriveFont(11f) })
                add(clearButton.apply { preferredSize = Dimension(50, 24); font = font.deriveFont(11f) })
                add(promptsButton.apply { preferredSize = Dimension(28, 24); font = font.deriveFont(11f) })
            }

            add(row1)
            add(row2)
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

        newChatButton.addActionListener {
            service.clipboardService.reset()
            chatHistory.createNewSession()
            clearAttachedTrace()
            refreshSessionList()
            loadCurrentSession()
            updateModeIndicator()
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

        sessionComboBox.addActionListener {
            val selected = sessionComboBox.selectedItem as? SessionItem ?: return@addActionListener
            if (selected.id != chatHistory.getActiveSession().id) {
                chatHistory.setActiveSession(selected.id)
                loadCurrentSession()
            }
        }

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
                if (cs.isWaitingForResponse()) {
                    val phase = cs.getCurrentPhase()
                    modeIndicator.text = if (phase == ClipboardPhase.PLANNING) "\u23F3 Paste planning response" else "\u23F3 Paste chat response"
                    modeIndicator.isVisible = true
                    sendButton.text = "Paste Response"
                } else {
                    modeIndicator.text = "\uD83D\uDCCB"
                    modeIndicator.isVisible = true
                    sendButton.text = "Generate JSON"
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

            // Показываем preview в чате
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

        // Забираем trace, чтобы прокинуть в запрос
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

        // Показываем в чате
        appendToChat("\n\uD83D\uDC64 You:\n$userMessage\n")
        if (!trace.isNullOrBlank()) {
            appendToChat("\uD83D\uDCCE [trace attached: ${trace.lines().size} lines]\n")
        }

        // В задачу включаем trace
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

    private fun sendClipboardMessage(userInput: String, trace: String? = null) {
        val cs = service.clipboardService
        val session = chatHistory.getActiveSession()
        inputArea.text = ""

        if (cs.isWaitingForResponse()) {
            // Paste response phase — trace не нужен тут
            session.addMessage(MessageRole.USER, "[Pasted LLM response]")
            appendToChat("\n\uD83D\uDCE5 Pasted LLM response (${userInput.length} chars)\n")
            setInputEnabled(false); statusLabel.text = "Processing response..."

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MaxVibes: Processing response...", true) {
                override fun run(indicator: ProgressIndicator) {
                    service.notificationService.setProgressIndicator(indicator)
                    runBlocking {
                        val result = cs.handlePastedResponse(userInput)
                        ApplicationManager.getApplication().invokeLater { handleClipboardResult(result, session) }
                    }
                }
                override fun onCancel() {
                    ApplicationManager.getApplication().invokeLater {
                        cs.reset()
                        appendToChat("\n\u26A0\uFE0F Cancelled. Clipboard session reset.\n")
                        setInputEnabled(true); updateModeIndicator()
                    }
                }
            })
        } else {
            // New task phase — включаем trace
            appendToChat("\n\uD83D\uDC64 You:\n$userInput\n")
            if (!trace.isNullOrBlank()) {
                appendToChat("\uD83D\uDCCE [trace attached: ${trace.lines().size} lines]\n")
            }
            session.addMessage(MessageRole.USER, userInput)
            setInputEnabled(false); statusLabel.text = "Generating JSON..."

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MaxVibes: Generating request...", true) {
                override fun run(indicator: ProgressIndicator) {
                    service.notificationService.setProgressIndicator(indicator)
                    runBlocking {
                        val historyDTOs = session.messages.dropLast(1).map { it.toChatMessageDTO() }
                        val result = cs.startTask(userInput, historyDTOs, attachedContext = trace)
                        ApplicationManager.getApplication().invokeLater { handleClipboardResult(result, session) }
                    }
                }
                override fun onCancel() {
                    ApplicationManager.getApplication().invokeLater {
                        cs.reset()
                        appendToChat("\n\u26A0\uFE0F Cancelled\n")
                        setInputEnabled(true); updateModeIndicator()
                    }
                }
            })
        }
    }

    // --- Cheap API Mode ---

    private fun sendCheapApiMessage(userMessage: String, trace: String? = null) {
        val isDryRun = dryRunCheckbox.isSelected
        val session = chatHistory.getActiveSession()

        // Показываем в чате
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
        refreshSessionList()
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
                statusLabel.text = if (result.success) "Ready" else "Completed with errors"
                refreshSessionList()
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

    private fun refreshSessionList() {
        sessionComboBox.removeAllItems()
        chatHistory.getSessions().forEach { session ->
            sessionComboBox.addItem(SessionItem(session.id, session.title, session.updatedAt))
        }
        val activeId = chatHistory.getActiveSession().id
        for (i in 0 until sessionComboBox.itemCount) {
            if (sessionComboBox.getItemAt(i).id == activeId) { sessionComboBox.selectedIndex = i; break }
        }
    }

    private fun loadCurrentSession() {
        refreshSessionList()
        val session = chatHistory.getActiveSession()
        chatArea.text = ""

        if (session.messages.isEmpty()) {
            showWelcome()
        } else {
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
        appendToChat("""
            |  Welcome to MaxVibes!
            |  $modeHint
            |
            |  Just type your task:
            |  • "Add a Logger class with debug and error methods"
            |  • "Create unit tests for UserService"
            |  • "Fix this error" + 📎 Trace button to attach stacktrace
            |
            |  Shortcuts: Ctrl+Enter send | Ctrl+Shift+V attach trace
            |
        """.trimMargin() + "\n")
    }

    // ==================== Utilities ====================

    /**
     * Склеивает задачу с трейсом/логами для отправки в LLM.
     */
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

    /** Appends assistant message with markdown cleanup */
    private fun appendAssistantMessage(text: String) {
        appendToChat("\n\uD83E\uDD16 MaxVibes:\n${formatMarkdown(text)}\n")
    }

    /**
     * Конвертирует markdown в читаемый plain text для JBTextArea.
     * Обрабатывает: **bold**, *italic*, `code`, заголовки, списки, горизонтальные линии.
     */
    private fun formatMarkdown(text: String): String {
        return text.lines().joinToString("\n") { line ->
            var l = line

            // Заголовки: ### Title → ═══ TITLE ═══
            val h3 = Regex("^###\\s+(.+)").find(l)
            if (h3 != null) return@joinToString "  ─── ${h3.groupValues[1].trim()} ───"

            val h2 = Regex("^##\\s+(.+)").find(l)
            if (h2 != null) return@joinToString "══ ${h2.groupValues[1].trim().uppercase()} ══"

            val h1 = Regex("^#\\s+(.+)").find(l)
            if (h1 != null) return@joinToString "═══ ${h1.groupValues[1].trim().uppercase()} ═══"

            // Горизонтальная линия: --- или *** или ___
            if (l.trim().matches(Regex("^[-*_]{3,}$"))) return@joinToString "─".repeat(40)

            // Списки: - item → • item, * item → • item
            l = l.replace(Regex("^(\\s*)[-*]\\s+"), "$1• ")

            // Нумерованные списки оставляем как есть

            // Bold+italic: ***text*** → TEXT
            l = l.replace(Regex("\\*{3}(.+?)\\*{3}")) { it.groupValues[1].uppercase() }

            // Bold: **text** → TEXT  (капслок привлекает внимание в plain text)
            l = l.replace(Regex("\\*{2}(.+?)\\*{2}")) { it.groupValues[1].uppercase() }

            // Italic: *text* → text (просто убираем звёздочки)
            l = l.replace(Regex("(?<![*])\\*([^*]+?)\\*(?![*])")) { it.groupValues[1] }

            // Inline code: `code` → [code]
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
        newChatButton.isEnabled = enabled
        clearButton.isEnabled = enabled
        promptsButton.isEnabled = enabled
        sessionComboBox.isEnabled = enabled
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

data class SessionItem(val id: String, val title: String, val updatedAt: Long) {
    override fun toString(): String = title
}

data class ModeItem(val id: String, val label: String) {
    override fun toString(): String = label
}

class SessionListRenderer : DefaultListCellRenderer() {
    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        if (value is SessionItem) {
            val date = dateFormat.format(Date(value.updatedAt))
            text = "<html><b>${value.title.take(25)}</b> <small style='color:gray'>$date</small></html>"
        }
        return this
    }
}