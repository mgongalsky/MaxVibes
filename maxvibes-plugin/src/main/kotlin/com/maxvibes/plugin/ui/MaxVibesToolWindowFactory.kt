package com.maxvibes.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.maxvibes.adapter.psi.operation.PsiNavigator
import com.maxvibes.application.port.input.ContextAwareRequest
import com.maxvibes.application.port.input.ContextAwareResult
import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.application.service.ClipboardStepResult
import com.maxvibes.domain.model.code.ElementPath
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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
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
        val branch = chatHistory.createBranch(parentId, title)
        if (branch != null) {
            chatPanel.loadCurrentSession()
            showChat()
        }
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

    private val planOnlyCheckbox = JBCheckBox("\uD83D\uDCAC Plan").apply {
        toolTipText = "Plan-only mode: discuss without generating code changes"
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

    private val deleteButton = JButton("\uD83D\uDDD1 Del").apply {
        toolTipText = "Delete current chat"
        font = font.deriveFont(11f)
        isFocusPainted = false
    }

    private val promptsButton = JButton("\u2699").apply {
        toolTipText = "Edit prompts (.maxvibes/prompts/)"
        font = font.deriveFont(11f)
    }

    private val contextFilesButton = JButton("\uD83D\uDCCE Ctx").apply {
        toolTipText = "Manage global context files"
        font = font.deriveFont(11f)
        isFocusPainted = false
    }

    private val claudeInstrButton = JButton("\uD83D\uDCCB").apply {
        toolTipText = "Claude instructions (copy for clipboard mode)"
        font = font.deriveFont(11f)
        isFocusPainted = false
    }

    // --- Services ---
    private val service: MaxVibesService by lazy { MaxVibesService.getInstance(project) }
    private val chatHistory: ChatHistoryService by lazy { ChatHistoryService.getInstance(project) }
    private val promptService: PromptService by lazy { PromptService.getInstance(project) }
    private val settings: MaxVibesSettings by lazy { MaxVibesSettings.getInstance() }

    private var currentMode: InteractionMode = InteractionMode.API
    private var attachedTrace: String? = null

    /**
     * Registry: compact display text → full ElementPath value.
     * Used to navigate to specific elements when user clicks on them in chat.
     * Example: "LinesGame.kt/function[updateAnimations]" → "file:src/main/kotlin/.../LinesGame.kt/function[updateAnimations]"
     */
    private val elementNavRegistry = mutableMapOf<String, String>()

    init {
        setupUI()
        setupListeners()
        setupClickableLinks()
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
                    add(contextFilesButton.apply { preferredSize = Dimension(56, 24) })
                    add(claudeInstrButton.apply { preferredSize = Dimension(26, 24) })
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
                    add(deleteButton.apply { preferredSize = Dimension(52, 22) })
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
                add(planOnlyCheckbox)
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
            add(JBLabel("<html><small>Ctrl+Enter send | Click file paths to open</small></html>").apply {
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

        deleteButton.addActionListener { deleteCurrentChat() }

        promptsButton.addActionListener {
            promptService.openOrCreatePrompts()
            statusLabel.text = "Prompts opened"
        }

        contextFilesButton.addActionListener { showContextFilesDialog() }
        claudeInstrButton.addActionListener { showClaudeInstructionsDialog() }

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

    // ==================== Delete Current Chat ====================

    private fun deleteCurrentChat() {
        val session = chatHistory.getActiveSession()
        val childCount = chatHistory.getChildCount(session.id)
        val msg = if (childCount > 0) {
            "Delete \"${session.title}\"?\n$childCount branch(es) will be re-attached to parent."
        } else {
            "Delete \"${session.title}\"?"
        }
        val confirm = JOptionPane.showConfirmDialog(
            this, msg, "Delete Chat", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        )
        if (confirm != JOptionPane.YES_OPTION) return

        resetClipboard()
        clearAttachedTrace()
        chatHistory.deleteSession(session.id)

        if (chatHistory.getAllSessions().isEmpty()) {
            chatHistory.createNewSession()
        }
        loadCurrentSession()
        updateModeIndicator()
        statusLabel.text = "Chat deleted"
    }

    // ==================== Clickable File & Element Links ====================

    private fun setupClickableLinks() {
        chatArea.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val clickTarget = getClickTargetAtPosition(e.point) ?: return
                when (clickTarget) {
                    is ClickTarget.Element -> navigateToElement(clickTarget.fullPath)
                    is ClickTarget.File -> openFileInEditor(clickTarget.relativePath)
                }
            }
        })

        chatArea.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val target = getClickTargetAtPosition(e.point)
                chatArea.cursor = if (target != null) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }
            }
        })
    }

    /**
     * Determines what was clicked: an element path or a file path.
     * Priority: element paths first (they contain file paths), then plain file paths.
     */
    private fun getClickTargetAtPosition(point: Point): ClickTarget? {
        val offset = chatArea.viewToModel2D(point)
        if (offset < 0 || offset >= chatArea.document.length) return null

        val text = chatArea.text
        val lineStart = text.lastIndexOf('\n', offset - 1).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val posInLine = offset - lineStart

        // Pattern 1: Element paths — File.kt/segment[name] or File.kt/seg[a]/seg[b]
        // Matches: LinesGame.kt/function[updateAnimations], User.kt/class[User]/function[validate]
        // Also matches bare segments: File.kt/companion_object, File.kt/init
        val elementPathRegex = Regex("""(?:^|[\s\u2022(])([a-zA-Z][\w.-]*\.(?:kt|java)(?:/(?:\w+\[\w+]|\w+))+)""")
        for (match in elementPathRegex.findAll(line)) {
            val group = match.groups[1] ?: continue
            if (posInLine >= group.range.first && posInLine <= group.range.last + 1) {
                // Look up in registry for full path
                val displayText = group.value
                val fullPath = elementNavRegistry[displayText]
                return if (fullPath != null) {
                    ClickTarget.Element(fullPath)
                } else {
                    // Try to resolve: extract filename, search in project
                    val fileName = displayText.substringBefore('/')
                    ClickTarget.File(fileName)
                }
            }
        }

        // Pattern 2: Plain file paths — src/main/kotlin/..., *.kt, *.java, etc.
        val filePathRegex = Regex("""(?:^|[\s\u2022(])([a-zA-Z][\w./\\-]*\.(?:kt|java|xml|json|md|gradle|kts|yaml|yml|properties|txt))""")
        for (match in filePathRegex.findAll(line)) {
            val group = match.groups[1] ?: continue
            if (posInLine >= group.range.first && posInLine <= group.range.last + 1) {
                return ClickTarget.File(group.value)
            }
        }

        return null
    }

    /**
     * Navigate to a specific PSI element in the editor.
     * Opens the file and scrolls to the element's text offset.
     */
    private fun navigateToElement(fullPathValue: String) {
        val elementPath = ElementPath(fullPathValue)
        val filePath = elementPath.filePath
        val basePath = project.basePath ?: return

        // Find the virtual file
        val virtualFile = LocalFileSystem.getInstance().findFileByPath("$basePath/$filePath")
            ?: findFileRecursively(File(basePath), filePath.substringAfterLast('/'))?.let {
                LocalFileSystem.getInstance().findFileByIoFile(it)
            }

        if (virtualFile == null) {
            statusLabel.text = "File not found: $filePath"
            return
        }

        if (elementPath.segments.isEmpty()) {
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
            statusLabel.text = "Opened: ${filePath.substringAfterLast('/')}"
            return
        }

        // Navigate to the specific element via PSI
        try {
            val navigator = PsiNavigator(project)
            val offset = runReadAction {
                val element = navigator.findElement(elementPath)
                element?.textOffset
            }

            if (offset != null) {
                val descriptor = OpenFileDescriptor(project, virtualFile, offset)
                descriptor.navigate(true)
                val lastSegment = elementPath.segments.lastOrNull()
                statusLabel.text = "Navigated to: ${lastSegment?.toPathString() ?: filePath.substringAfterLast('/')}"
            } else {
                // Element not found (maybe code changed), just open the file
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
                statusLabel.text = "Element not found, opened file"
            }
        } catch (e: Exception) {
            // Fallback: just open the file
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
            statusLabel.text = "Opened: ${filePath.substringAfterLast('/')}"
        }
    }

    private fun openFileInEditor(relativePath: String) {
        val basePath = project.basePath ?: return
        val file = LocalFileSystem.getInstance().findFileByPath("$basePath/$relativePath")
        if (file != null && !file.isDirectory) {
            FileEditorManager.getInstance(project).openFile(file, true)
            statusLabel.text = "Opened: ${relativePath.substringAfterLast('/')}"
            return
        }
        val fileName = relativePath.substringAfterLast('/')
        val found = findFileRecursively(File(basePath), fileName)
        if (found != null) {
            val vf = LocalFileSystem.getInstance().findFileByIoFile(found)
            if (vf != null) {
                FileEditorManager.getInstance(project).openFile(vf, true)
                statusLabel.text = "Opened: $fileName"
                return
            }
        }
        statusLabel.text = "File not found: $relativePath"
    }

    private fun findFileRecursively(dir: File, name: String): File? {
        if (!dir.isDirectory) return null
        val skipDirs = setOf("build", ".gradle", ".idea", "node_modules", ".git")
        for (child in dir.listFiles() ?: return null) {
            if (child.isDirectory) {
                if (child.name in skipDirs) continue
                val found = findFileRecursively(child, name)
                if (found != null) return found
            } else if (child.name == name) {
                return child
            }
        }
        return null
    }

    // ==================== Element Path Formatting ====================

    /**
     * Formats an ElementPath for compact display in chat.
     * Examples:
     *   file:src/.../User.kt → "User.kt"
     *   file:src/.../User.kt/class[User]/function[validate] → "User.kt/class[User]/function[validate]"
     *   file:src/.../User.kt/class[User]/companion_object → "User.kt/class[User]/companion_object"
     */
    private fun formatElementPath(path: ElementPath): String {
        val fileName = path.filePath.substringAfterLast('/')
        val segments = path.segments
        return if (segments.isNotEmpty()) {
            "$fileName/${segments.joinToString("/") { it.toPathString() }}"
        } else {
            fileName
        }
    }

    /**
     * Registers element paths from modification results for click navigation.
     */
    private fun registerElementPaths(modifications: List<ModificationResult>) {
        modifications.filterIsInstance<ModificationResult.Success>().forEach { result ->
            val display = formatElementPath(result.affectedPath)
            elementNavRegistry[display] = result.affectedPath.value
        }
    }

    // ==================== Context Files Dialog ====================

    private fun showContextFilesDialog() {
        val currentFiles = chatHistory.getGlobalContextFiles().toMutableList()

        val listModel = DefaultListModel<String>().apply {
            currentFiles.forEach { addElement(it) }
        }
        val fileList = JList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        }

        val panel = JPanel(BorderLayout(8, 8)).apply {
            preferredSize = Dimension(500, 350)
            border = JBUI.Borders.empty(8)

            add(JBLabel("<html><b>Global Context Files</b><br>" +
                    "<small>These files are included in every LLM request as background context.<br>" +
                    "Useful for architecture docs, coding guidelines, etc.</small></html>").apply {
                border = JBUI.Borders.empty(0, 0, 8, 0)
            }, BorderLayout.NORTH)

            add(JBScrollPane(fileList), BorderLayout.CENTER)

            val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                add(JButton("+ Add file").apply {
                    addActionListener {
                        val path = JOptionPane.showInputDialog(
                            this@ChatPanel,
                            "Relative path from project root\n(e.g. ARCHITECTURE.md, docs/guide.md):",
                            "Add Context File",
                            JOptionPane.PLAIN_MESSAGE
                        ) ?: return@addActionListener
                        val normalized = path.trim().replace('\\', '/')
                        if (normalized.isNotBlank() && !listModel.contains(normalized)) {
                            val basePath = project.basePath
                            val exists = basePath != null && File(basePath, normalized).exists()
                            if (exists) {
                                listModel.addElement(normalized)
                            } else {
                                val addAnyway = JOptionPane.showConfirmDialog(
                                    this@ChatPanel,
                                    "File '$normalized' not found in project.\nAdd anyway?",
                                    "File Not Found",
                                    JOptionPane.YES_NO_OPTION,
                                    JOptionPane.WARNING_MESSAGE
                                )
                                if (addAnyway == JOptionPane.YES_OPTION) {
                                    listModel.addElement(normalized)
                                }
                            }
                        }
                    }
                })
                add(JButton("- Remove").apply {
                    addActionListener {
                        val idx = fileList.selectedIndex
                        if (idx >= 0) listModel.remove(idx)
                    }
                })
                add(JButton("Browse...").apply {
                    addActionListener {
                        val basePath = project.basePath ?: return@addActionListener
                        val chooser = JFileChooser(basePath).apply {
                            fileSelectionMode = JFileChooser.FILES_ONLY
                            isMultiSelectionEnabled = true
                            dialogTitle = "Select context files"
                        }
                        if (chooser.showOpenDialog(this@ChatPanel) == JFileChooser.APPROVE_OPTION) {
                            for (f in chooser.selectedFiles) {
                                val rel = f.toRelativeString(File(basePath)).replace('\\', '/')
                                if (!listModel.contains(rel)) {
                                    listModel.addElement(rel)
                                }
                            }
                        }
                    }
                })
            }
            add(buttonsPanel, BorderLayout.SOUTH)
        }

        val result = JOptionPane.showConfirmDialog(
            this, panel, "Global Context Files",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (result == JOptionPane.OK_OPTION) {
            val newFiles = (0 until listModel.size()).map { listModel.getElementAt(it) }
            chatHistory.setGlobalContextFiles(newFiles)
            updateContextIndicator()
            statusLabel.text = "Context files: ${newFiles.size}"
        }
    }

    private fun updateContextIndicator() {
        val count = chatHistory.getGlobalContextFiles().size
        contextFilesButton.text = if (count > 0) "\uD83D\uDCCE Ctx($count)" else "\uD83D\uDCCE Ctx"
    }

    // ==================== Claude Instructions Dialog ====================

    private fun showClaudeInstructionsDialog() {
        val instrFile = File(project.basePath ?: return, ".maxvibes/claude-instructions.md")

        if (!instrFile.exists()) {
            instrFile.parentFile?.mkdirs()
            instrFile.writeText(DEFAULT_CLAUDE_INSTRUCTIONS)
        }

        val content = instrFile.readText()

        val popup = JPopupMenu().apply {
            add(JMenuItem("\uD83D\uDCCB Copy to clipboard").apply {
                font = font.deriveFont(12f)
                addActionListener {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.setContents(java.awt.datatransfer.StringSelection(content), null)
                    statusLabel.text = "Claude instructions copied!"
                }
            })
            add(JMenuItem("\u270F\uFE0F Edit in editor").apply {
                font = font.deriveFont(12f)
                addActionListener {
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(instrFile)
                    if (vf != null) {
                        FileEditorManager.getInstance(project).openFile(vf, true)
                        statusLabel.text = "Claude instructions opened"
                    }
                }
            })
            add(JMenuItem("\uD83D\uDD04 Reset to default").apply {
                font = font.deriveFont(12f)
                addActionListener {
                    instrFile.writeText(DEFAULT_CLAUDE_INSTRUCTIONS)
                    statusLabel.text = "Claude instructions reset"
                }
            })
        }
        popup.show(claudeInstrButton, 0, claudeInstrButton.height)
    }

    // ==================== Breadcrumb ====================

    fun refreshHeader() {
        updateBreadcrumb()
        updateModeIndicator()
        updateContextIndicator()
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

            if (isLast) {
                val titleText = s.title.take(30) + if (s.title.length > 30) ".." else ""
                val label = JBLabel(titleText).apply {
                    font = font.deriveFont(Font.BOLD, 11f)
                    foreground = JBColor.foreground()
                    border = JBUI.Borders.empty(2, 3)
                    cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
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
                    border = JBUI.Borders.empty(2, 3)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }
                val sid = s.id
                label.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        chatHistory.setActiveSession(sid)
                        loadCurrentSession()
                    }
                })
                breadcrumbPanel.add(label)
            }
        }

        breadcrumbPanel.revalidate()
        breadcrumbPanel.repaint()
    }

    private fun startInlineRename(label: JBLabel, sessionId: String, currentTitle: String) {
        val parent = label.parent ?: return
        val idx = (0 until parent.componentCount).firstOrNull { parent.getComponent(it) === label } ?: return

        val textField = JTextField(currentTitle).apply {
            font = label.font
            preferredSize = Dimension(
                maxOf(label.preferredSize.width + 40, 120),
                label.preferredSize.height + 2
            )
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor(Color(0x2196F3), Color(0x64B5F6)), 1),
                JBUI.Borders.empty(1, 3)
            )
            selectAll()
        }

        var committed = false

        fun commitRename() {
            if (committed) return
            committed = true
            val newTitle = textField.text.trim()
            if (newTitle.isNotBlank() && newTitle != currentTitle) {
                chatHistory.renameSession(sessionId, newTitle)
                statusLabel.text = "Renamed to \"$newTitle\""
            }
            updateBreadcrumb()
        }

        fun cancelRename() {
            if (committed) return
            committed = true
            updateBreadcrumb()
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

        parent.remove(idx)
        parent.add(textField, idx)
        parent.revalidate()
        parent.repaint()
        textField.requestFocusInWindow()
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
        val isPlanOnly = planOnlyCheckbox.isSelected
        val session = chatHistory.getActiveSession()
        val globalCtx = chatHistory.getGlobalContextFiles()
        appendToChat("\n\uD83D\uDC64 You:\n$msg\n")
        if (!trace.isNullOrBlank()) appendToChat("\uD83D\uDCCE [trace: ${trace.lines().size} lines]\n")
        if (isPlanOnly) appendToChat("\uD83D\uDCAC [plan-only mode]\n")
        val fullTask = buildTaskWithTrace(msg, trace)
        session.addMessage(MessageRole.USER, fullTask)
        inputArea.text = ""; setInputEnabled(false)
        statusLabel.text = if (isPlanOnly) "Planning..." else "Thinking..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MaxVibes: Processing...", true) {
            override fun run(indicator: ProgressIndicator) {
                service.notificationService.setProgressIndicator(indicator)
                runBlocking {
                    val dtos = session.messages.dropLast(1).map { it.toChatMessageDTO() }
                    val req = ContextAwareRequest(
                        task = fullTask, history = dtos, dryRun = isDryRun,
                        planOnly = isPlanOnly, globalContextFiles = globalCtx
                    )
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
                appendToChat("\n\uD83D\uDC64 You:\n[Pasted LLM response]\n")
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
        val isPlanOnly = planOnlyCheckbox.isSelected
        val session = chatHistory.getActiveSession()
        val globalCtx = chatHistory.getGlobalContextFiles()
        appendToChat("\n\uD83D\uDC64 You:\n$msg\n")
        if (!trace.isNullOrBlank()) appendToChat("\uD83D\uDCCE [trace: ${trace.lines().size} lines]\n")
        if (isPlanOnly) appendToChat("\uD83D\uDCAC [plan-only mode]\n")
        val fullTask = buildTaskWithTrace(msg, trace)
        session.addMessage(MessageRole.USER, fullTask)
        inputArea.text = ""; setInputEnabled(false); statusLabel.text = "Thinking (cheap)..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MaxVibes: Processing (budget)...", true) {
            override fun run(indicator: ProgressIndicator) {
                service.notificationService.setProgressIndicator(indicator)
                runBlocking {
                    val dtos = session.messages.dropLast(1).map { it.toChatMessageDTO() }
                    val req = ContextAwareRequest(
                        task = fullTask, history = dtos, dryRun = isDryRun,
                        planOnly = isPlanOnly, globalContextFiles = globalCtx
                    )
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
        // Register element paths for click navigation
        registerElementPaths(result.modifications)

        val text = buildResultText(result)
        session.addMessage(MessageRole.ASSISTANT, text)
        appendAssistantMessage(text)
        appendToChat("\u2500".repeat(50) + "\n")
        setInputEnabled(true); statusLabel.text = if (result.success) "Ready" else "Errors"
        updateBreadcrumb()
    }

    /**
     * Handle clipboard result.
     * NOTE: result.message from ClipboardInteractionService already includes modification summary,
     * so we do NOT add a second summary here (fixes duplicate file creation display).
     */
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
                // Register element paths for click navigation
                registerElementPaths(result.modifications)

                val text = result.message.ifBlank { "Done." }
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
            appendLine("\n\n\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500")
            val ok = result.modifications.filterIsInstance<ModificationResult.Success>()
            val fail = result.modifications.filterIsInstance<ModificationResult.Failure>()
            if (ok.isNotEmpty()) {
                appendLine("\u2705 ${ok.size} applied:")
                ok.forEach { appendLine("   \u2022 ${formatElementPath(it.affectedPath)}") }
            }
            if (fail.isNotEmpty()) {
                appendLine("\u274C ${fail.size} failed:")
                fail.forEach { appendLine("   \u2022 ${it.error.message}") }
            }
        }
    }

    // ==================== Session ====================

    fun loadCurrentSession() {
        val session = chatHistory.getActiveSession()
        chatArea.text = ""
        elementNavRegistry.clear()
        updateBreadcrumb()
        updateModeIndicator()
        updateContextIndicator()

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
            InteractionMode.API -> "API \u2014 direct LLM calls"
            InteractionMode.CLIPBOARD -> "Clipboard \u2014 paste JSON into Claude/ChatGPT"
            InteractionMode.CHEAP_API -> "Cheap API \u2014 budget model"
        }
        val session = chatHistory.getActiveSession()
        val branchHint = if (session.depth > 0) {
            val parent = chatHistory.getParent(session.id)
            "\n  \u2514 Branch from: \"${parent?.title ?: "?"}\""
        } else ""

        val ctxCount = chatHistory.getGlobalContextFiles().size
        val ctxHint = if (ctxCount > 0) "\n  \uD83D\uDCCE $ctxCount global context file(s) active" else ""

        appendToChat("""
            |  MaxVibes  \u2022  $mode$branchHint$ctxHint
            |
            |  Type your task, or use Sessions to browse dialogs.
            |  Ctrl+Enter send | Click file paths to open
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
            Regex("^###\\s+(.+)").find(l)?.let {
                return@joinToString "  \u2500\u2500\u2500 ${it.groupValues[1].trim()} \u2500\u2500\u2500"
            }
            Regex("^##\\s+(.+)").find(l)?.let {
                return@joinToString "\u2550\u2550 ${it.groupValues[1].trim().uppercase()} \u2550\u2550"
            }
            Regex("^#\\s+(.+)").find(l)?.let {
                return@joinToString "\u2550\u2550\u2550 ${it.groupValues[1].trim().uppercase()} \u2550\u2550\u2550"
            }
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
        dryRunCheckbox.isEnabled = enabled; planOnlyCheckbox.isEnabled = enabled
        attachTraceButton.isEnabled = enabled
        clearTraceButton.isEnabled = enabled
        promptsButton.isEnabled = enabled; modeComboBox.isEnabled = enabled
        sessionsButton.isEnabled = enabled; branchButton.isEnabled = enabled
        newChatButton.isEnabled = enabled; deleteButton.isEnabled = enabled
        contextFilesButton.isEnabled = enabled; claudeInstrButton.isEnabled = enabled
    }
}

// ==================== Click Target ====================

private sealed class ClickTarget {
    /** Navigate to a specific PSI element */
    data class Element(val fullPath: String) : ClickTarget()
    /** Open a file by relative path */
    data class File(val relativePath: String) : ClickTarget()
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

// ==================== Default Claude Instructions ====================

private val DEFAULT_CLAUDE_INSTRUCTIONS = """
This project uses MaxVibes IDE plugin clipboard protocol for code modifications.

When you receive a JSON message containing "_protocol", "systemInstruction", "task", "fileTree", or "files" fields — this is a MaxVibes protocol message from the IDE plugin. Follow these rules STRICTLY:

1. RESPOND WITH ONLY A JSON OBJECT. Your entire response must be valid JSON — no markdown, no text before/after, no code blocks, no explanations outside the JSON.

2. DO NOT use computer tools, bash, file creation, artifacts, or any other tools. ALL code must go inside the JSON response in the "modifications" array.

3. Response format:
{
    "message": "Your explanation or answer",
    "requestedFiles": ["path/to/file.kt"],
    "reasoning": "why you need those files",
    "modifications": [...]
}

4. All fields are optional except "message" (always recommended):
   - "message" — your explanation or discussion
   - "requestedFiles" — files you need to see (triggers file gathering in IDE)
   - "reasoning" — why you need those files
   - "modifications" — code changes (see types below)

5. Modification types — PREFER element-level for existing files:

   | type             | When                          | path format                                              | content              | extra fields          |
   |------------------|-------------------------------|----------------------------------------------------------|----------------------|-----------------------|
   | REPLACE_ELEMENT  | Change a function/class/prop  | file:path/File.kt/class[Name]/function[method]           | Complete element     | elementKind           |
   | CREATE_ELEMENT   | Add new element to parent     | file:path/File.kt/class[Name]                            | New element code     | elementKind, position |
   | DELETE_ELEMENT    | Remove an element             | file:path/File.kt/class[Name]/function[old]              | (empty)              |                       |
   | ADD_IMPORT        | Add import to file            | file:path/File.kt                                        | (empty)              | importPath            |
   | REMOVE_IMPORT     | Remove import from file       | file:path/File.kt                                        | (empty)              | importPath            |
   | CREATE_FILE       | New file                      | src/main/kotlin/.../File.kt                              | Full file            |                       |
   | REPLACE_FILE      | Rewrite entire file           | src/main/kotlin/.../File.kt                              | Full file            |                       |

6. Element path format:
   file:src/main/kotlin/com/example/User.kt/class[User]/function[validate]
   Segments: class[Name], interface[Name], object[Name], function[Name], property[Name], companion_object, init, constructor[primary], enum_entry[Name]

7. Key rules for modifications:
   - PREFER REPLACE_ELEMENT/CREATE_ELEMENT over REPLACE_FILE — saves tokens!
   - Only use REPLACE_FILE when the majority of the file changes
   - For REPLACE_ELEMENT: content = COMPLETE element (annotations, modifiers, signature, body)
   - For CREATE_ELEMENT: set elementKind and position (LAST_CHILD, FIRST_CHILD)
   - Use ADD_IMPORT/REMOVE_IMPORT for imports — never manually edit the import block
   - "content" must be complete, compilable Kotlin code

8. For regular conversation (not MaxVibes protocol messages), respond normally as usual.
""".trimIndent()