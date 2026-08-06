package com.maxvibes.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.plugin.settings.MaxVibesSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ScrollPaneConstants

/**
 * Header strip of the chat tool window: the mode selector with its indicator and CC-log
 * link, the tool-window/context buttons, the session breadcrumb and the session nav row.
 *
 * Owns its widgets, its layout and its listeners. Every decision is delegated upward
 * through the constructor callbacks, so the panel holds no project or service reference
 * and can be reasoned about without the rest of the chat.
 */
class ChatHeaderPanel(
    private val onModeSelected: (InteractionMode) -> Unit,
    private val onIndicatorAction: (IndicatorAction) -> Unit,
    private val onOpenCcLog: () -> Unit,
    private val onShowSessions: () -> Unit,
    private val onNewChat: () -> Unit,
    private val onBranch: () -> Unit,
    private val onDeleteChat: () -> Unit,
    private val onOpenPrompts: () -> Unit,
    private val onContextFiles: () -> Unit,
    private val onClaudeInstructions: (JButton) -> Unit,
    private val onToggleMaximize: () -> Unit,
    private val onToggleWindowed: () -> Unit,
    private val onSelectSession: (String) -> Unit,
    private val onRenameSession: (String, String) -> Unit
) : JPanel() {

    private val modeComboBox = ComboBox<ModeItem>().apply {
        MaxVibesSettings.INTERACTION_MODES.forEach { (id, label) -> addItem(ModeItem(id, label)) }
        toolTipText = "Interaction mode"
        preferredSize = Dimension(180, 24)
        font = font.deriveFont(11f)
    }

    private val modeIndicator = JBLabel("").apply {
        foreground = JBColor(Color(0x2196F3), Color(0x64B5F6))
        font = font.deriveFont(Font.BOLD, 11f)
        isVisible = false
    }

    /**
     * Clickable link to the per-dialog Claude Code transcript. Visible only in
     * [InteractionMode.CLAUDE_CODE]; re-clicking refreshes the already-open editor.
     */
    private val ccLogLink = JBLabel("\uD83D\uDCC4 CC log").apply {
        foreground = JBColor(Color(0x2196F3), Color(0x64B5F6))
        font = font.deriveFont(11f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Open Claude Code dialog transcript (re-click to refresh)"
        isVisible = false
    }

    private val contextFilesButton = JButton("\uD83D\uDCCE Ctx").apply {
        font = font.deriveFont(11f); isFocusPainted = false; preferredSize = Dimension(56, 24)
    }
    private val claudeInstrButton = JButton("\uD83D\uDCCB").apply {
        font = font.deriveFont(11f); isFocusPainted = false; preferredSize = Dimension(26, 24)
    }
    private val windowedButton = JButton(AllIcons.Actions.MoveToWindow).apply {
        toolTipText = "Floating Mode / Dock"
        font = font.deriveFont(11f); isFocusPainted = false; preferredSize = Dimension(26, 24)
    }
    private val maximizeButton = JButton(AllIcons.General.ExpandComponent).apply {
        toolTipText = "Maximize / Restore"
        font = font.deriveFont(11f); isFocusPainted = false; preferredSize = Dimension(26, 24)
    }
    private val promptsButton = JButton("\u2699").apply {
        toolTipText = "Edit prompts"; font = font.deriveFont(11f); preferredSize = Dimension(26, 24)
    }

    private val newChatButton = JButton("+ New").apply {
        font = font.deriveFont(11f); isFocusPainted = false; preferredSize = Dimension(52, 22)
    }
    private val branchButton = JButton("\u2442 Branch").apply {
        font = font.deriveFont(11f); isFocusPainted = false; preferredSize = Dimension(64, 22)
    }
    private val deleteButton = JButton("\uD83D\uDDD1 Del").apply {
        font = font.deriveFont(11f); isFocusPainted = false; preferredSize = Dimension(52, 22)
    }
    private val sessionsButton = JButton("\uD83D\uDCC2 Sessions").apply {
        font = font.deriveFont(11f); isFocusPainted = false; preferredSize = Dimension(86, 22)
    }

    private val breadcrumbPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        background = JBColor.background()
    }

    /**
     * Last path handed to [updateBreadcrumb]. Inline rename repaints from this snapshot,
     * so a cancelled edit restores the crumbs without a round trip to the session tree.
     */
    private var currentPath: List<ChatSession> = emptyList()

    /** Held so it can be detached before re-attaching, preventing listener accumulation. */
    private var indicatorListener: MouseAdapter? = null

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = JBColor.background()
        border = JBUI.Borders.compound(
            JBUI.Borders.empty(4, 8),
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
        )
        add(buildControlRow())
        add(buildNavRow())
        wireListeners()
    }

    private fun buildControlRow(): JPanel = JPanel(BorderLayout()).apply {
        background = JBColor.background()
        maximumSize = Dimension(Int.MAX_VALUE, 30)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            background = JBColor.background()
            add(modeComboBox)
            add(modeIndicator)
            add(ccLogLink)
        }, BorderLayout.WEST)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 3, 0)).apply {
            background = JBColor.background()
            add(contextFilesButton)
            add(claudeInstrButton)
            add(windowedButton)
            add(maximizeButton)
            add(promptsButton)
        }, BorderLayout.EAST)
    }

    private fun buildNavRow(): JPanel = JPanel(BorderLayout(4, 0)).apply {
        background = JBColor.background()
        maximumSize = Dimension(Int.MAX_VALUE, 28)
        border = JBUI.Borders.empty(2, 0, 0, 0)
        add(JScrollPane(breadcrumbPanel).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            background = JBColor.background()
            viewport.background = JBColor.background()
        }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 3, 0)).apply {
            background = JBColor.background()
            add(newChatButton)
            add(branchButton)
            add(deleteButton)
            add(sessionsButton)
        }, BorderLayout.EAST)
    }

    private fun wireListeners() {
        modeComboBox.addActionListener {
            val selected = modeComboBox.selectedItem as? ModeItem ?: return@addActionListener
            val mode = runCatching { InteractionMode.valueOf(selected.id) }.getOrNull() ?: return@addActionListener
            onModeSelected(mode)
        }
        ccLogLink.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onOpenCcLog()
        })
        sessionsButton.addActionListener { onShowSessions() }
        newChatButton.addActionListener { onNewChat() }
        branchButton.addActionListener { onBranch() }
        deleteButton.addActionListener { onDeleteChat() }
        promptsButton.addActionListener { onOpenPrompts() }
        contextFilesButton.addActionListener { onContextFiles() }
        claudeInstrButton.addActionListener { onClaudeInstructions(claudeInstrButton) }
        maximizeButton.addActionListener { onToggleMaximize() }
        windowedButton.addActionListener { onToggleWindowed() }
    }

    /**
     * Moves the combo to [mode]. This re-fires the action listener, so the owner's
     * handler must be a no-op when the selected mode already equals the current one.
     */
    fun selectMode(mode: InteractionMode) {
        for (i in 0 until modeComboBox.itemCount) {
            if (modeComboBox.getItemAt(i).id == mode.name) {
                modeComboBox.selectedIndex = i
                return
            }
        }
    }

    /**
     * Applies the header-owned part of a [ModeUiPolicy] decision.
     *
     * A null `indicatorText` or `indicatorDecoration` means "leave the widget as it is" —
     * the API modes never touched cursor or tooltip, and that must stay true.
     */
    fun applyModeDecision(decision: ModeUiDecision) {
        indicatorListener?.let { modeIndicator.removeMouseListener(it) }
        indicatorListener = null

        ccLogLink.isVisible = decision.ccLogLinkVisible
        modeIndicator.isVisible = decision.indicatorVisible
        decision.indicatorText?.let { modeIndicator.text = it }

        val decoration = decision.indicatorDecoration ?: return
        modeIndicator.cursor =
            if (decoration.handCursor) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            else Cursor.getDefaultCursor()
        modeIndicator.toolTipText = decoration.tooltip

        val action = decoration.action ?: return
        val listener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onIndicatorAction(action)
        }
        indicatorListener = listener
        modeIndicator.addMouseListener(listener)
    }

    fun updateBreadcrumb(path: List<ChatSession>) {
        currentPath = path
        breadcrumbPanel.removeAll()
        for ((i, session) in path.withIndex()) {
            if (i > 0) {
                breadcrumbPanel.add(JBLabel(" \u203A ").apply {
                    foreground = JBColor.GRAY
                    font = font.deriveFont(11f)
                })
            }
            breadcrumbPanel.add(
                if (i == path.size - 1) buildActiveCrumb(session) else buildParentCrumb(session)
            )
        }
        breadcrumbPanel.revalidate()
        breadcrumbPanel.repaint()
    }

    private fun buildActiveCrumb(session: ChatSession): JBLabel {
        val label = JBLabel(session.title.take(30) + if (session.title.length > 30) ".." else "").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = JBColor.foreground()
            border = JBUI.Borders.empty(2, 3)
            cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
            toolTipText = "Click to rename"
        }
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = startInlineRename(label, session.id, session.title)
        })
        return label
    }

    private fun buildParentCrumb(session: ChatSession): JBLabel {
        val label = JBLabel(session.title.take(20) + if (session.title.length > 20) ".." else "").apply {
            font = font.deriveFont(11f)
            foreground = JBColor(Color(0x2196F3), Color(0x64B5F6))
            border = JBUI.Borders.empty(2, 3)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        val sessionId = session.id
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onSelectSession(sessionId)
        })
        return label
    }

    private fun startInlineRename(label: JBLabel, sessionId: String, currentTitle: String) {
        val parent = label.parent ?: return
        val idx = (0 until parent.componentCount).firstOrNull { parent.getComponent(it) === label } ?: return

        val textField = JTextField(currentTitle).apply {
            font = label.font
            preferredSize = Dimension(maxOf(label.preferredSize.width + 40, 120), label.preferredSize.height + 2)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor(Color(0x2196F3), Color(0x64B5F6)), 1),
                JBUI.Borders.empty(1, 3)
            )
            selectAll()
        }

        // Enter, focus loss and Escape can all fire for one edit; the flag keeps it to one commit.
        var committed = false

        fun commitRename() {
            if (committed) return
            committed = true
            val newTitle = textField.text.trim()
            if (newTitle.isNotBlank() && newTitle != currentTitle) onRenameSession(sessionId, newTitle)
            else updateBreadcrumb(currentPath)
        }

        fun cancelRename() {
            if (committed) return
            committed = true
            updateBreadcrumb(currentPath)
        }

        textField.addActionListener { commitRename() }
        textField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    cancelRename(); e.consume()
                }
            }
        })
        textField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) = commitRename()
        })

        parent.remove(idx)
        parent.add(textField, idx)
        parent.revalidate()
        parent.repaint()
        textField.requestFocusInWindow()
    }

    fun updateContextCount(count: Int) {
        contextFilesButton.text = if (count > 0) "\uD83D\uDCCE Ctx($count)" else "\uD83D\uDCCE Ctx"
    }

    fun updateToolWindowIcons(maximized: Boolean, floating: Boolean) {
        maximizeButton.icon =
            if (maximized) AllIcons.General.CollapseComponent else AllIcons.General.ExpandComponent
        windowedButton.icon = AllIcons.Actions.MoveToWindow
        windowedButton.toolTipText = if (floating) "Dock Tool Window" else "Floating Mode"
    }

    /** [windowedButton] is deliberately excluded: docking stays reachable while a turn is in flight. */
    fun setControlsEnabled(enabled: Boolean) {
        listOf<JComponent>(
            modeComboBox, promptsButton, sessionsButton, branchButton,
            newChatButton, deleteButton, contextFilesButton, claudeInstrButton, maximizeButton
        ).forEach { it.isEnabled = enabled }
    }
}
