package com.maxvibes.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.maxvibes.application.service.ChatTreeService
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
import javax.swing.tree.TreeSelectionModel
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke
import com.maxvibes.domain.model.chat.SessionTreeNode

/**
 * Full-window panel for browsing the session tree.
 * Shows all dialogs as a tree with branches, allows opening, creating, deleting, renaming.
 */
class SessionTreePanel(
    private val chatTreeService: ChatTreeService,
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
        rowHeight = 0
        border = JBUI.Borders.empty(2)
        selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
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
                add(JBLabel("\uD83D\uDCC2").apply { font = font.deriveFont(16f) })
                add(JBLabel("<html><b>Sessions</b></html>").apply {
                    font = font.deriveFont(14f)
                })
            }

            val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                background = JBColor.background()
                add(JButton("\uD83D\uDDD1 Delete").apply {
                    toolTipText = "Delete selected chats"
                    font = font.deriveFont(12f)
                    isFocusPainted = false
                    foreground = JBColor(Color(0xE53935), Color(0xEF5350))
                    addActionListener { deleteSelectedSessions() }
                })
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

        val deleteAction = object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                deleteSelectedSessions()
            }
        }
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0), "deleteSelected"
        )
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_BACK_SPACE, 0), "deleteSelected"
        )
        tree.actionMap.put("deleteSelected", deleteAction)

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val data = node.userObject as? TreeNodeData ?: return
                    onOpenSession(data.sessionId)
                }
            }
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                showContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                showContextMenu(e)
            }

            private fun showContextMenu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return

                val selectedPaths = tree.selectionPaths ?: emptyArray()
                val isMultiSelection = selectedPaths.size > 1 && selectedPaths.contains(path)
                if (!isMultiSelection) {
                    tree.selectionPath = path
                }

                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val data = node.userObject as? TreeNodeData ?: return

                JPopupMenu().apply {
                    if (!isMultiSelection) {
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
                    }
                    val selectedCount = if (isMultiSelection) (tree.selectionPaths?.size ?: 1) else 1
                    add(JMenuItem("\uD83D\uDDD1 Delete${if (isMultiSelection) " ($selectedCount selected)" else " & branches"}").apply {
                        font = font.deriveFont(12f)
                        foreground = JBColor(Color(0xE53935), Color(0xEF5350))
                        addActionListener {
                            if (isMultiSelection) {
                                deleteSelectedSessions()
                            } else {
                                val confirmed = JOptionPane.showConfirmDialog(
                                    this@SessionTreePanel,
                                    "Delete \"${data.title}\" and all its branches?\nThis cannot be undone.",
                                    "Confirm Delete",
                                    JOptionPane.YES_NO_OPTION,
                                    JOptionPane.WARNING_MESSAGE
                                )
                                if (confirmed == JOptionPane.YES_OPTION) {
                                    onDeleteSession(data.sessionId)
                                    refresh()
                                }
                            }
                        }
                    })
                }.show(tree, e.x, e.y)
            }
        })

        val scrollPane = JBScrollPane(tree).apply {
            border = JBUI.Borders.empty()
        }
        add(scrollPane, BorderLayout.CENTER)

        val hint =
            JBLabel("<html><small>Double-click to open \u2022 Right-click for actions \u2022 Ctrl+click to multi-select \u2022 Del to delete</small></html>").apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.empty(4, 12)
            }
        add(hint, BorderLayout.SOUTH)
    }

    private fun deleteSelectedSessions() {
        val paths = tree.selectionPaths ?: return
        val items = paths.mapNotNull { path ->
            (path.lastPathComponent as? DefaultMutableTreeNode)
                ?.userObject as? TreeNodeData
        }
        if (items.isEmpty()) return

        val count = items.size
        val message = if (count == 1) {
            "Delete \"${items.first().title}\" and all its branches?\nThis cannot be undone."
        } else {
            "Delete $count selected chats and all their branches?\nThis cannot be undone."
        }

        val confirmed = JOptionPane.showConfirmDialog(
            this,
            message,
            "Confirm Delete",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (confirmed != JOptionPane.YES_OPTION) return

        val sorted = items.sortedByDescending { it.depth }
        val deletedIds = mutableSetOf<String>()
        for (item in sorted) {
            if (item.sessionId !in deletedIds) {
                onDeleteSession(item.sessionId)
                deletedIds.add(item.sessionId)
            }
        }
        refresh()
    }

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
            chatTreeService.renameSession(data.sessionId, newTitle)
            refresh()
        }
    }

    fun refresh() {
        treeRoot.removeAllChildren()
        val sessionTree = chatTreeService.buildTree()
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
            depth = node.depth,
            totalTokens = node.session.tokenUsage.total
        )
        val swingNode = DefaultMutableTreeNode(data)
        for (child in node.children) {
            swingNode.add(buildNode(child))
        }
        return swingNode
    }

    private fun expandToActive() {
        val activeId = chatTreeService.getActiveSession().id
        val target = findNode(treeRoot, activeId)
        if (target != null) {
            val path = TreePath(target.path)
            tree.expandPath(path.parentPath)
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        } else {
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

data class TreeNodeData(
    val sessionId: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
    val childCount: Int,
    val depth: Int,
    val totalTokens: Int = 0
) {
    override fun toString(): String = title
}

class SessionCellRenderer(
    private val dateFormat: SimpleDateFormat
) : DefaultTreeCellRenderer() {

    init {
        leafIcon = null
        openIcon = null
        closedIcon = null
    }

    override fun getTreeCellRendererComponent(
        tree: JTree, value: Any?, sel: Boolean, expanded: Boolean,
        leaf: Boolean, row: Int, hasFocus: Boolean
    ): Component {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)

        val node = (value as? DefaultMutableTreeNode)?.userObject as? TreeNodeData
        if (node == null) {
            text = value?.toString() ?: ""
            return this
        }

        val icon = when {
            node.childCount > 0 -> "\uD83D\uDCC2"
            node.messageCount == 0 -> "\uD83D\uDCC4"
            else -> "\uD83D\uDCAC"
        }

        val title = escapeHtml(node.title)
        val date = dateFormat.format(Date(node.updatedAt))
        val tokenStr = if (node.totalTokens > 0) " \u2022 ${formatTok(node.totalTokens)} tok" else ""
        val meta = buildString {
            append(date)
            append(" \u2022 ${node.messageCount} msg")
            if (node.childCount > 0) append(" \u2022 ${node.childCount} branch")
            append(tokenStr)
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

    private fun formatTok(n: Int): String = when {
        n >= 1_000_000 -> "${n / 1_000_000}.${(n % 1_000_000) / 100_000}M"
        n >= 1_000 -> "${n / 1_000}k"
        else -> n.toString()
    }
}
