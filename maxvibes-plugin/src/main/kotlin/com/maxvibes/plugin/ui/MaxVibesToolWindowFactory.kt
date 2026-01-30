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
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.plugin.chat.ChatHistoryService
import com.maxvibes.plugin.chat.ChatSession
import com.maxvibes.plugin.chat.MessageRole
import com.maxvibes.plugin.service.MaxVibesService
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

    private val sessionComboBox = ComboBox<SessionItem>().apply {
        renderer = SessionListRenderer()
    }

    private val dryRunCheckbox = JBCheckBox("Dry run").apply {
        toolTipText = "Show plan without applying changes"
    }

    private val statusLabel = JBLabel("Ready").apply {
        foreground = JBColor.GRAY
    }

    private val service: MaxVibesService by lazy {
        MaxVibesService.getInstance(project)
    }

    private val chatHistory: ChatHistoryService by lazy {
        ChatHistoryService.getInstance(project)
    }

    init {
        setupUI()
        setupListeners()
        loadCurrentSession()
    }

    private fun setupUI() {
        border = JBUI.Borders.empty()

        // Header with session selector
        val headerPanel = JPanel(BorderLayout(5, 0)).apply {
            border = JBUI.Borders.empty(8)
            background = JBColor.background()

            // Left: Title + LLM info
            val titlePanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
                background = JBColor.background()
                add(JBLabel("<html><b>MaxVibes</b></html>"))
                add(JBLabel(service.getLLMInfo()).apply {
                    foreground = JBColor.GRAY
                    font = font.deriveFont(10f)
                })
            }
            add(titlePanel, BorderLayout.WEST)

            // Right: Session controls
            val controlsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 3, 0)).apply {
                background = JBColor.background()
                add(sessionComboBox)
                add(newChatButton.apply {
                    preferredSize = Dimension(60, 24)
                    font = font.deriveFont(11f)
                })
                add(clearButton.apply {
                    preferredSize = Dimension(60, 24)
                    font = font.deriveFont(11f)
                })
            }
            add(controlsPanel, BorderLayout.EAST)
        }

        // Chat area
        val chatScroll = JBScrollPane(chatArea).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        // Input panel
        val inputPanel = JPanel(BorderLayout(5, 5)).apply {
            border = JBUI.Borders.empty(8)
            background = JBColor.background()

            val inputWrapper = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.customLine(JBColor.border(), 1)
                add(inputArea, BorderLayout.CENTER)
            }
            add(inputWrapper, BorderLayout.CENTER)

            val buttonsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                background = JBColor.background()
                add(dryRunCheckbox)
                add(sendButton)
            }
            add(buttonsPanel, BorderLayout.SOUTH)
        }

        // Status bar
        val statusBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(3, 10)
            background = JBColor.background()
            add(statusLabel, BorderLayout.WEST)
            add(JBLabel("<html><small>Ctrl+Enter to send</small></html>").apply {
                foreground = JBColor.GRAY
            }, BorderLayout.EAST)
        }

        // Layout
        add(headerPanel, BorderLayout.NORTH)
        add(chatScroll, BorderLayout.CENTER)

        val bottomPanel = JPanel(BorderLayout()).apply {
            add(inputPanel, BorderLayout.CENTER)
            add(statusBar, BorderLayout.SOUTH)
        }
        add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun setupListeners() {
        sendButton.addActionListener { sendMessage() }

        newChatButton.addActionListener {
            chatHistory.createNewSession()
            refreshSessionList()
            loadCurrentSession()
        }

        clearButton.addActionListener {
            chatHistory.clearActiveSession()
            loadCurrentSession()
        }

        sessionComboBox.addActionListener {
            val selected = sessionComboBox.selectedItem as? SessionItem ?: return@addActionListener
            if (selected.id != chatHistory.getActiveSession().id) {
                chatHistory.setActiveSession(selected.id)
                loadCurrentSession()
            }
        }

        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) {
                    sendMessage()
                    e.consume()
                }
            }
        })
    }

    private fun refreshSessionList() {
        sessionComboBox.removeAllItems()
        chatHistory.getSessions().forEach { session ->
            sessionComboBox.addItem(SessionItem(session.id, session.title, session.updatedAt))
        }

        // Select active
        val activeId = chatHistory.getActiveSession().id
        for (i in 0 until sessionComboBox.itemCount) {
            if (sessionComboBox.getItemAt(i).id == activeId) {
                sessionComboBox.selectedIndex = i
                break
            }
        }
    }

    private fun loadCurrentSession() {
        refreshSessionList()

        val session = chatHistory.getActiveSession()
        chatArea.text = ""

        if (session.messages.isEmpty()) {
            showWelcome()
        } else {
            // Restore messages
            session.messages.forEach { msg ->
                when (msg.role) {
                    MessageRole.USER -> appendToChat("\n👤 You:\n${msg.content}\n")
                    MessageRole.ASSISTANT -> appendToChat("\n🤖 MaxVibes:\n${msg.content}\n")
                    MessageRole.SYSTEM -> appendToChat("\n${msg.content}\n")
                }
            }
        }
    }

    private fun showWelcome() {
        val welcome = """
            ┌─────────────────────────────────────────────────────────┐
            │  Welcome to MaxVibes! 🚀                                │
            ├─────────────────────────────────────────────────────────┤
            │  Describe what you want to do:                          │
            │  • "Add a Logger class with debug and error methods"    │
            │  • "Create unit tests for UserService"                  │
            │  • "Refactor to use dependency injection"               │
            │                                                         │
            │  💡 Use "Dry run" to preview changes first              │
            └─────────────────────────────────────────────────────────┘
            
        """.trimIndent()
        appendToChat(welcome)
    }

    private fun sendMessage() {
        val task = inputArea.text.trim()
        if (task.isBlank()) return

        val isDryRun = dryRunCheckbox.isSelected
        val session = chatHistory.getActiveSession()

        // Save user message
        session.addMessage(MessageRole.USER, task)
        appendToChat("\n👤 You:\n$task\n")
        inputArea.text = ""

        setInputEnabled(false)
        statusLabel.text = "Processing..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "MaxVibes: Processing...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                service.notificationService.setProgressIndicator(indicator)

                indicator.text = "Planning..."
                indicator.fraction = 0.1

                runBlocking {
                    val request = ContextAwareRequest(
                        task = task,
                        dryRun = isDryRun
                    )

                    val result = service.contextAwareModifyUseCase.execute(request)

                    ApplicationManager.getApplication().invokeLater {
                        val responseText = formatResult(result, isDryRun)
                        session.addMessage(MessageRole.ASSISTANT, responseText)
                        appendToChat("\n🤖 MaxVibes:\n$responseText\n")
                        appendToChat("─".repeat(50) + "\n")

                        setInputEnabled(true)
                        statusLabel.text = "Ready"
                        refreshSessionList() // Update title if changed
                    }
                }
            }

            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    session.addMessage(MessageRole.SYSTEM, "⚠️ Operation cancelled")
                    appendToChat("\n⚠️ Operation cancelled\n")
                    setInputEnabled(true)
                    statusLabel.text = "Cancelled"
                }
            }
        })
    }

    private fun formatResult(result: ContextAwareResult, wasDryRun: Boolean): String {
        return buildString {
            if (result.success) {
                appendLine(if (wasDryRun) "✅ Plan created successfully!" else "✅ Modifications applied!")
            } else {
                appendLine("❌ Error: ${result.error}")
            }

            result.planningReasoning?.let {
                appendLine("\n📋 Analysis:\n$it")
            }

            if (result.requestedFiles.isNotEmpty()) {
                appendLine("\n📁 Files: ${result.requestedFiles.size}")
                result.requestedFiles.take(5).forEach { appendLine("   • $it") }
                if (result.requestedFiles.size > 5) {
                    appendLine("   ... +${result.requestedFiles.size - 5} more")
                }
            }

            if (!wasDryRun && result.modifications.isNotEmpty()) {
                val ok = result.modifications.count { it is ModificationResult.Success }
                val fail = result.modifications.size - ok
                appendLine("\n🔧 Modifications: $ok applied" + if (fail > 0) ", $fail failed" else "")
                result.modifications.forEach { mod ->
                    when (mod) {
                        is ModificationResult.Success -> {
                            appendLine("   ✅ ${mod.affectedPath.value.substringAfterLast('/')}")
                        }
                        is ModificationResult.Failure -> {
                            appendLine("   ❌ ${mod.error.message}")
                        }
                    }
                }
            }
        }
    }

    private fun appendToChat(text: String) {
        chatArea.append(text)
        chatArea.caretPosition = chatArea.document.length
    }

    private fun setInputEnabled(enabled: Boolean) {
        inputArea.isEnabled = enabled
        sendButton.isEnabled = enabled
        dryRunCheckbox.isEnabled = enabled
        newChatButton.isEnabled = enabled
        clearButton.isEnabled = enabled
        sessionComboBox.isEnabled = enabled
    }
}

/**
 * Item for session combo box
 */
data class SessionItem(
    val id: String,
    val title: String,
    val updatedAt: Long
) {
    override fun toString(): String = title
}

/**
 * Custom renderer for session list
 */
class SessionListRenderer : DefaultListCellRenderer() {
    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

        if (value is SessionItem) {
            val date = dateFormat.format(Date(value.updatedAt))
            text = "<html><b>${value.title.take(25)}</b> <small style='color:gray'>$date</small></html>"
        }

        return this
    }
}