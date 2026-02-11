package com.maxvibes.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.maxvibes.plugin.chat.ChatHistoryService
import com.maxvibes.plugin.chat.SessionTreeNode
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Full-window panel for browsing the session tree.
 * Shows all dialogs as a tree with branches, allows opening, creating, deleting, renaming.
 */
class SessionTreePanel(
    private val chatHistory: ChatHistoryService,
    private val onOpenSession: (String) -> Unit,
    private val onNewRoot: () -> Unit,
    private val onNewBranch: (parentId: String) -> Unit,
    private val onDeleteSession: (String) -> Unit,
    private val onBack: () -> Unit
) : JPanel(BorderLayout()) {

    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    private val treeRoot = DefaultMutableTreeNode("Sessions")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        rowHeight = 0  // auto-calculate from renderer
        border = JBUI.Borders.empty(2)
    }

    init {
        background = JBColor.background()
        setupHeader()
        setupTree()
        refresh()
    }

    private fun setupHeader() {
        val header = JPanel(BorderLayout()).apply {
            background = JBColor.background()
            border = JBUI.Borders.compound(
                JBUI.Borders.empty(8, 12),
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            )

            val titlePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                background = JBColor.background()
                add(JBLabel("\uD83D\uDCC2").apply { font = font.deriveFont(16f) }) // 📂
                add(JBLabel("<html><b>Sessions</b></html>").apply {
                    font = font.deriveFont(14f)
                })
            }

            val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                background = JBColor.background()
                add(JButton("+ New Chat").apply {
                    toolTipText = "Create new root dialog"
                    font = font.deriveFont(12f)
                    isFocusPainted = false
                    addActionListener { onNewRoot() }
                })
                add(JButton("\u2190 Back").apply {
                    toolTipText = "Return to chat"
                    font = font.deriveFont(12f)
                    isFocusPainted = false
                    addActionListener { onBack() }
                })
            }

            add(titlePanel, BorderLayout.WEST)
            add(actionsPanel, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
    }

    private fun setupTree() {
        tree.cellRenderer = SessionCellRenderer(dateFormat)

        // Double click → open
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val data = node.userObject as? TreeNodeData ?: return
                    onOpenSession(data.sessionId)
                }
            }
        })

        // Right click → context menu
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { showContextMenu(e) }
            override fun mouseReleased(e: MouseEvent) { showContextMenu(e) }

            private fun showContextMenu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                tree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val data = node.userObject as? TreeNodeData ?: return

                JPopupMenu().apply {
                    add(JMenuItem("\uD83D\uDCAC Open").apply {
                        font = font.deriveFont(12f)
                        addActionListener { onOpenSession(data.sessionId) }
                    })
                    add(JMenuItem("\u270F\uFE0F Rename").apply {
                        font = font.deriveFont(12f)
                        addActionListener { renameSession(data) }
                    })
                    add(JMenuItem("\u2442 New branch here").apply {
                        font = font.deriveFont(12f)
                        addActionListener { onNewBranch(data.sessionId) }
                    })
                    addSeparator()
                    add(JMenuItem("\uD83D\uDDD1 Delete").apply {
                        font = font.deriveFont(12f)
                        foreground = JBColor(Color(0xE53935), Color(0xEF5350))
                        addActionListener { onDeleteSession(data.sessionId) }
                    })
                }.show(tree, e.x, e.y)
            }
        })

        val scrollPane = JBScrollPane(tree).apply {
            border = JBUI.Borders.empty()
        }
        add(scrollPane, BorderLayout.CENTER)

        // Bottom hint
        val hint = JBLabel("<html><small>Double-click to open \u2022 Right-click for actions</small></html>").apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(4, 12)
        }
        add(hint, BorderLayout.SOUTH)
    }

    /**
     * Shows an input dialog to rename a session.
     */
    private fun renameSession(data: TreeNodeData) {
        val newTitle = JOptionPane.showInputDialog(
            this,
            "Rename dialog:",
            "Rename",
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            data.title
        ) as? String

        if (newTitle != null && newTitle.isNotBlank() && newTitle != data.title) {
            chatHistory.renameSession(data.sessionId, newTitle)
            refresh()
        }
    }

    fun refresh() {
        treeRoot.removeAllChildren()
        val sessionTree = chatHistory.buildTree()
        for (node in sessionTree) {
            treeRoot.add(buildNode(node))
        }
        treeModel.reload()
        expandToActive()
    }

    private fun buildNode(node: SessionTreeNode): DefaultMutableTreeNode {
        val data = TreeNodeData(
            sessionId = node.id,
            title = node.title,
            updatedAt = node.session.updatedAt,
            messageCount = node.session.messages.size,
            childCount = node.children.size,
            depth = node.depth
        )
        val swingNode = DefaultMutableTreeNode(data)
        for (child in node.children) {
            swingNode.add(buildNode(child))
        }
        return swingNode
    }

    private fun expandToActive() {
        val activeId = chatHistory.getActiveSession().id
        val target = findNode(treeRoot, activeId)
        if (target != null) {
            val path = TreePath(target.path)
            tree.expandPath(path.parentPath)
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        } else {
            // Expand first level
            for (i in 0 until treeRoot.childCount) {
                tree.expandRow(i)
            }
        }
    }

    private fun findNode(parent: DefaultMutableTreeNode, id: String): DefaultMutableTreeNode? {
        val data = parent.userObject as? TreeNodeData
        if (data?.sessionId == id) return parent
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val found = findNode(child, id)
            if (found != null) return found
        }
        return null
    }
}

// ==================== Tree Data & Renderers ====================

/**
 * Node data for JTree display.
 */
data class TreeNodeData(
    val sessionId: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
    val childCount: Int,
    val depth: Int
) {
    override fun toString(): String = title
}

/**
 * Simple HTML-based tree cell renderer.
 * Uses a single JLabel with HTML — JTree handles sizing correctly.
 * Line 1: icon + title (bold)
 * Line 2: date • msg count • branch count (small, gray)
 */
class SessionCellRenderer(
    private val dateFormat: SimpleDateFormat
) : DefaultTreeCellRenderer() {

    init {
        // Remove default icons — we use text icons
        leafIcon = null
        openIcon = null
        closedIcon = null
    }

    override fun getTreeCellRendererComponent(
        tree: JTree, value: Any?, sel: Boolean, expanded: Boolean,
        leaf: Boolean, row: Int, hasFocus: Boolean
    ): Component {
        // Let default handle selection colors
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)

        val node = (value as? DefaultMutableTreeNode)?.userObject as? TreeNodeData
        if (node == null) {
            text = value?.toString() ?: ""
            return this
        }

        val icon = when {
            node.childCount > 0 -> "\uD83D\uDCC2"  // 📂
            node.messageCount == 0 -> "\uD83D\uDCC4" // 📄
            else -> "\uD83D\uDCAC"                    // 💬
        }

        val title = escapeHtml(node.title)
        val date = dateFormat.format(Date(node.updatedAt))
        val meta = buildString {
            append(date)
            append(" \u2022 ${node.messageCount} msg")
            if (node.childCount > 0) append(" \u2022 ${node.childCount} branch")
        }

        val gray = if (JBColor.isBright()) "#888888" else "#999999"

        text = "<html>" +
                "<span>$icon <b>$title</b></span><br>" +
                "<span style='font-size:9px;color:$gray'>$meta</span>" +
                "</html>"

        border = JBUI.Borders.empty(3, 2)

        return this
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}