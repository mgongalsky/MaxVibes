package com.maxvibes.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.maxvibes.plugin.chat.ChatHistoryService
import com.maxvibes.plugin.chat.ChatSession
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
 * Навигатор по дереву диалогов.
 *
 * Показывает breadcrumb-путь от корня до текущей сессии + кнопки навигации.
 * По клику на "▼" открывается popup с полным деревом.
 * По клику на сегмент breadcrumb — переход к этой сессии.
 */
class ChatTreeNavigator(
    private val project: Project,
    private val onSessionSelected: (String) -> Unit
) : JPanel() {

    private val chatHistory: ChatHistoryService by lazy { ChatHistoryService.getInstance(project) }
    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    // Breadcrumb панель
    private val breadcrumbPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        background = JBColor.background()
    }

    // Кнопка открытия дерева
    private val treeButton = JButton("\u25BC").apply {
        toolTipText = "Browse dialog tree"
        preferredSize = Dimension(28, 24)
        font = font.deriveFont(10f)
        isFocusPainted = false
    }

    // Кнопка создания ветки
    private val branchButton = JButton("\u2442 Branch").apply {
        toolTipText = "Create a new branch from current dialog"
        preferredSize = Dimension(70, 24)
        font = font.deriveFont(11f)
        isFocusPainted = false
    }

    // Кнопка нового корневого диалога
    private val newRootButton = JButton("New").apply {
        toolTipText = "Start new root dialog"
        preferredSize = Dimension(50, 24)
        font = font.deriveFont(11f)
        isFocusPainted = false
    }

    // Информация о ветках
    private val branchInfoLabel = JLabel("").apply {
        foreground = JBColor.GRAY
        font = font.deriveFont(10f)
    }

    /** Callback для создания новой корневой сессии */
    var onNewRoot: (() -> Unit)? = null

    /** Callback для создания ветки */
    var onNewBranch: (() -> Unit)? = null

    /** Callback для удаления сессии */
    var onDeleteSession: ((String) -> Unit)? = null

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = JBColor.background()
        border = JBUI.Borders.empty(2, 8)

        // Row 1: Breadcrumb + tree button
        val row1 = JPanel(BorderLayout(4, 0)).apply {
            background = JBColor.background()
            maximumSize = Dimension(Int.MAX_VALUE, 28)

            val breadcrumbScroll = JScrollPane(breadcrumbPanel).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                preferredSize = Dimension(0, 24) // takes remaining space
                background = JBColor.background()
                viewport.background = JBColor.background()
            }
            add(breadcrumbScroll, BorderLayout.CENTER)

            val buttonsRow1 = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                background = JBColor.background()
                add(treeButton)
            }
            add(buttonsRow1, BorderLayout.EAST)
        }

        // Row 2: Branch controls + info
        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 3, 0)).apply {
            background = JBColor.background()
            maximumSize = Dimension(Int.MAX_VALUE, 26)
            add(newRootButton)
            add(branchButton)
            add(branchInfoLabel)
        }

        add(row1)
        add(row2)

        setupListeners()
    }

    private fun setupListeners() {
        treeButton.addActionListener { showTreePopup() }
        branchButton.addActionListener { onNewBranch?.invoke() }
        newRootButton.addActionListener { onNewRoot?.invoke() }
    }

    /**
     * Обновляет breadcrumb и информацию о ветках для текущей активной сессии.
     */
    fun refresh() {
        val activeSession = chatHistory.getActiveSession()
        updateBreadcrumb(activeSession.id)
        updateBranchInfo(activeSession.id)
    }

    // ==================== Breadcrumb ====================

    private fun updateBreadcrumb(sessionId: String) {
        breadcrumbPanel.removeAll()

        val path = chatHistory.getSessionPath(sessionId)
        if (path.isEmpty()) {
            breadcrumbPanel.add(createBreadcrumbLabel("No session", null, isLast = true))
            breadcrumbPanel.revalidate()
            breadcrumbPanel.repaint()
            return
        }

        for ((index, session) in path.withIndex()) {
            val isLast = index == path.size - 1

            if (index > 0) {
                breadcrumbPanel.add(createSeparatorLabel())
            }

            breadcrumbPanel.add(createBreadcrumbLabel(
                text = session.title.take(20) + if (session.title.length > 20) ".." else "",
                sessionId = session.id,
                isLast = isLast
            ))
        }

        breadcrumbPanel.revalidate()
        breadcrumbPanel.repaint()
    }

    private fun createBreadcrumbLabel(text: String, sessionId: String?, isLast: Boolean): JLabel {
        return JLabel(text).apply {
            font = if (isLast) {
                font.deriveFont(Font.BOLD, 11f)
            } else {
                font.deriveFont(Font.PLAIN, 11f)
            }
            foreground = if (isLast) {
                JBColor.foreground()
            } else {
                JBColor(Color(0x2196F3), Color(0x64B5F6))
            }
            cursor = if (sessionId != null && !isLast) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            border = JBUI.Borders.empty(2, 3)

            if (sessionId != null && !isLast) {
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        onSessionSelected(sessionId)
                    }

                    override fun mouseEntered(e: MouseEvent) {
                        foreground = JBColor(Color(0x1565C0), Color(0x90CAF9))
                    }

                    override fun mouseExited(e: MouseEvent) {
                        foreground = JBColor(Color(0x2196F3), Color(0x64B5F6))
                    }
                })
            }
        }
    }

    private fun createSeparatorLabel(): JLabel {
        return JLabel(" \u203A ").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(11f)
        }
    }

    // ==================== Branch Info ====================

    private fun updateBranchInfo(sessionId: String) {
        val childCount = chatHistory.getChildCount(sessionId)
        val depth = chatHistory.getSessionById(sessionId)?.depth ?: 0

        branchInfoLabel.text = when {
            childCount > 0 && depth > 0 -> "\u2514 depth:$depth, $childCount branch(es)"
            childCount > 0 -> "$childCount branch(es)"
            depth > 0 -> "\u2514 depth:$depth"
            else -> ""
        }
    }

    // ==================== Tree Popup ====================

    private fun showTreePopup() {
        val tree = buildSwingTree()
        val scrollPane = JScrollPane(tree).apply {
            preferredSize = Dimension(350, 300)
            border = JBUI.Borders.empty()
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, tree)
            .setTitle("Dialog Tree")
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .createPopup()

        // Двойной клик — выбор сессии
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val treeNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val sessionNode = treeNode.userObject as? TreeNodeData ?: return
                    onSessionSelected(sessionNode.sessionId)
                    popup.cancel()
                }
            }
        })

        // Контекстное меню
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { maybeShowPopup(e) }
            override fun mouseReleased(e: MouseEvent) { maybeShowPopup(e) }

            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                tree.selectionPath = path
                val treeNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val nodeData = treeNode.userObject as? TreeNodeData ?: return

                val contextMenu = JPopupMenu()

                contextMenu.add(JMenuItem("Open").apply {
                    addActionListener {
                        onSessionSelected(nodeData.sessionId)
                        popup.cancel()
                    }
                })

                contextMenu.add(JMenuItem("New branch here").apply {
                    addActionListener {
                        onSessionSelected(nodeData.sessionId)
                        popup.cancel()
                        onNewBranch?.invoke()
                    }
                })

                contextMenu.addSeparator()

                contextMenu.add(JMenuItem("Delete (keep children)").apply {
                    addActionListener {
                        onDeleteSession?.invoke(nodeData.sessionId)
                        popup.cancel()
                    }
                })

                contextMenu.show(tree, e.x, e.y)
            }
        })

        popup.showUnderneathOf(treeButton)
    }

    private fun buildSwingTree(): Tree {
        val rootNode = DefaultMutableTreeNode("Sessions")
        val sessionTree = chatHistory.buildTree()

        for (treeNode in sessionTree) {
            rootNode.add(buildTreeNodeRecursive(treeNode))
        }

        val treeModel = DefaultTreeModel(rootNode)
        val tree = Tree(treeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            cellRenderer = SessionTreeCellRenderer(chatHistory.getActiveSession().id, dateFormat)
        }

        // Раскрываем путь до активной сессии
        expandToActiveSession(tree, rootNode)

        return tree
    }

    private fun buildTreeNodeRecursive(node: SessionTreeNode): DefaultMutableTreeNode {
        val childCount = node.children.size
        val data = TreeNodeData(
            sessionId = node.id,
            title = node.title,
            updatedAt = node.session.updatedAt,
            messageCount = node.session.messages.size,
            childCount = childCount,
            depth = node.depth
        )

        val swingNode = DefaultMutableTreeNode(data)
        for (child in node.children) {
            swingNode.add(buildTreeNodeRecursive(child))
        }
        return swingNode
    }

    private fun expandToActiveSession(tree: Tree, rootNode: DefaultMutableTreeNode) {
        val activeId = chatHistory.getActiveSession().id
        val targetNode = findTreeNode(rootNode, activeId)
        if (targetNode != null) {
            val path = TreePath(targetNode.path)
            tree.expandPath(path.parentPath)
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        } else {
            // Раскрываем хотя бы первый уровень
            for (i in 0 until rootNode.childCount) {
                tree.expandRow(i)
            }
        }
    }

    private fun findTreeNode(parent: DefaultMutableTreeNode, sessionId: String): DefaultMutableTreeNode? {
        val data = parent.userObject as? TreeNodeData
        if (data?.sessionId == sessionId) return parent

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val found = findTreeNode(child, sessionId)
            if (found != null) return found
        }
        return null
    }
}

// ==================== Tree Data & Renderer ====================

/**
 * Данные узла дерева для отображения в JTree.
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
 * Renderer для узлов дерева сессий.
 * Показывает: иконку (ветка/лист), название, дату, количество сообщений.
 * Активная сессия выделена жирным.
 */
class SessionTreeCellRenderer(
    private val activeSessionId: String,
    private val dateFormat: SimpleDateFormat
) : DefaultTreeCellRenderer() {

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        sel: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ): Component {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)

        val node = (value as? DefaultMutableTreeNode)?.userObject as? TreeNodeData ?: return this

        val isActive = node.sessionId == activeSessionId
        val date = dateFormat.format(Date(node.updatedAt))
        val msgCount = node.messageCount
        val branchCount = node.childCount

        // Иконка
        val icon = when {
            branchCount > 0 -> "\uD83D\uDCC2" // 📂 has branches
            isActive -> "\uD83D\uDCAC"          // 💬 active
            else -> "\uD83D\uDCC4"               // 📄 leaf
        }

        val branchHint = if (branchCount > 0) " [$branchCount]" else ""
        val activeMarker = if (isActive) " \u25C0" else "" // ◀

        text = "<html>$icon <b>${escapeHtml(node.title.take(30))}</b>$branchHint" +
                " <small style='color:gray'>$date \u2022 ${msgCount}msg</small>$activeMarker</html>"

        return this
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}