package com.maxvibes.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.maxvibes.application.port.input.ContextAwareRequest
import com.maxvibes.application.port.input.ContextAwareResult
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.plugin.service.MaxVibesService
import kotlinx.coroutines.runBlocking
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.border.EmptyBorder

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
        isEnabled = true
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

    init {
        setupUI()
        setupListeners()
        showWelcome()
    }

    private fun setupUI() {
        border = JBUI.Borders.empty()

        // Header
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            background = JBColor.background()

            add(JBLabel("<html><b>MaxVibes</b> <small>AI Assistant</small></html>"), BorderLayout.WEST)

            val llmInfo = JBLabel(service.getLLMInfo()).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(11f)
            }
            add(llmInfo, BorderLayout.EAST)
        }

        // Chat area
        val chatScroll = JBScrollPane(chatArea).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        // Input panel
        val inputPanel = JPanel(BorderLayout(5, 5)).apply {
            border = JBUI.Borders.empty(10)
            background = JBColor.background()

            // Input area with border
            val inputWrapper = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.customLine(JBColor.border(), 1)
                add(inputArea, BorderLayout.CENTER)
            }
            add(inputWrapper, BorderLayout.CENTER)

            // Buttons panel
            val buttonsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                background = JBColor.background()
                add(dryRunCheckbox)
                add(sendButton)
            }
            add(buttonsPanel, BorderLayout.SOUTH)
        }

        // Status bar
        val statusBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5, 10)
            background = JBColor.background()
            add(statusLabel, BorderLayout.WEST)
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

        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) {
                    sendMessage()
                    e.consume()
                }
            }
        })
    }

    private fun showWelcome() {
        appendToChat("""
            ╔═══════════════════════════════════════════════════════════╗
            ║  Welcome to MaxVibes! 🚀                                  ║
            ╠═══════════════════════════════════════════════════════════╣
            ║  I can help you modify Kotlin code using AI.              ║
            ║                                                           ║
            ║  Just describe what you want to do:                       ║
            ║  • "Add a Logger class with debug and error methods"      ║
            ║  • "Create unit tests for UserService"                    ║
            ║  • "Add validation to all public functions"               ║
            ║                                                           ║
            ║  I'll analyze your project, gather the needed files,      ║
            ║  and generate the code automatically.                     ║
            ║                                                           ║
            ║  💡 Tip: Check "Dry run" to preview before applying       ║
            ║  ⌨️  Press Ctrl+Enter to send                             ║
            ╚═══════════════════════════════════════════════════════════╝
            
        """.trimIndent())
    }

    private fun sendMessage() {
        val task = inputArea.text.trim()
        if (task.isBlank()) return

        val isDryRun = dryRunCheckbox.isSelected

        // Show user message
        appendToChat("\n👤 You:\n$task\n")
        inputArea.text = ""

        // Disable input while processing
        setInputEnabled(false)
        statusLabel.text = "Processing..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "MaxVibes: Processing...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                service.notificationService.setProgressIndicator(indicator)

                runBlocking {
                    val request = ContextAwareRequest(
                        task = task,
                        dryRun = isDryRun
                    )

                    val result = service.contextAwareModifyUseCase.execute(request)

                    ApplicationManager.getApplication().invokeLater {
                        showResult(result, isDryRun)
                        setInputEnabled(true)
                        statusLabel.text = "Ready"
                    }
                }
            }

            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    appendToChat("\n⚠️ Operation cancelled\n")
                    setInputEnabled(true)
                    statusLabel.text = "Cancelled"
                }
            }
        })
    }

    private fun showResult(result: ContextAwareResult, wasDryRun: Boolean) {
        val response = buildString {
            appendLine()
            appendLine("🤖 MaxVibes:")

            if (result.success) {
                if (wasDryRun) {
                    appendLine("✅ Plan created successfully!")
                } else {
                    appendLine("✅ Modifications applied successfully!")
                }
            } else {
                appendLine("❌ Error: ${result.error}")
            }
            appendLine()

            // Planning reasoning
            result.planningReasoning?.let {
                appendLine("📋 Analysis:")
                appendLine(it)
                appendLine()
            }

            // Files info
            if (result.requestedFiles.isNotEmpty()) {
                appendLine("📁 Files analyzed: ${result.requestedFiles.size}")
                result.requestedFiles.take(5).forEach { appendLine("   • $it") }
                if (result.requestedFiles.size > 5) {
                    appendLine("   ... and ${result.requestedFiles.size - 5} more")
                }
                appendLine()
            }

            // Modifications
            if (!wasDryRun && result.modifications.isNotEmpty()) {
                val successCount = result.modifications.count { it is ModificationResult.Success }
                val failCount = result.modifications.size - successCount

                appendLine("🔧 Modifications: $successCount applied, $failCount failed")
                result.modifications.forEach { mod ->
                    when (mod) {
                        is ModificationResult.Success -> {
                            val shortPath = mod.affectedPath.value.substringAfterLast('/')
                            appendLine("   ✅ $shortPath")
                        }
                        is ModificationResult.Failure -> {
                            appendLine("   ❌ ${mod.error.message}")
                        }
                    }
                }
            }

            appendLine()
            appendLine("─".repeat(50))
        }

        appendToChat(response)
    }

    private fun appendToChat(text: String) {
        chatArea.append(text)
        chatArea.caretPosition = chatArea.document.length
    }

    private fun setInputEnabled(enabled: Boolean) {
        inputArea.isEnabled = enabled
        sendButton.isEnabled = enabled
        dryRunCheckbox.isEnabled = enabled
    }
}