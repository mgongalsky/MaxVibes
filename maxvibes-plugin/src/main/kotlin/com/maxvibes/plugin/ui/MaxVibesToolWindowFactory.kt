package com.maxvibes.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.plugin.service.MaxVibesService
import com.maxvibes.plugin.service.MaxVibesLogger
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.chat.ChatMessage
import java.awt.CardLayout
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

// ==================== Factory ====================

class MaxVibesToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MaxVibesToolPanel(project, toolWindow)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

// ==================== Main Panel (CardLayout) ====================

private const val CARD_CHAT = "chat"
private const val CARD_SESSIONS = "sessions"

class MaxVibesToolPanel(private val project: Project, private val toolWindow: ToolWindow) : JPanel(CardLayout()) {

    private val chatTreeService get() = MaxVibesService.getInstance(project).chatTreeService

    private val chatPanel = ChatPanel(project, toolWindow, onShowSessions = { showSessions() })
    private val sessionTreePanel: SessionTreePanel

    init {
        sessionTreePanel = SessionTreePanel(
            chatTreeService = chatTreeService,
            onOpenSession = { id -> openSession(id) },
            onNewRoot = { createNewRoot() },
            onNewBranch = { parentId -> createBranch(parentId) },
            onDeleteSession = { id -> deleteSession(id) },
            onBack = { showChat() }
        )

        add(chatPanel, CARD_CHAT)
        add(sessionTreePanel, CARD_SESSIONS)

        showChat()

        MaxVibesLogger.info("ToolWindow", "init", mapOf("project" to project.name))
        com.intellij.openapi.util.Disposer.register(project, com.intellij.openapi.Disposable {
            MaxVibesLogger.shutdown()
        })
    }

    /**
     * Shows the chat card.
     *
     * Wrapped in [SwingUtilities.invokeLater] to defer [CardLayout.show] past the current
     * event-dispatch cycle. This prevents a re-entrant layout pass that occurs when
     * [EditorTextField] lazily initialises its inner editor inside [BoxLayout.preferredLayoutSize],
     * which would trigger a second layout pass before the first one completes → NPE on `xTotal`.
     */
    private fun showChat() {
        SwingUtilities.invokeLater {
            (layout as CardLayout).show(this, CARD_CHAT)
            chatPanel.refreshHeader()
        }
    }

    /**
     * Shows the session-tree card.
     *
     * Same [SwingUtilities.invokeLater] guard as [showChat] — ensures the card switch
     * happens outside any in-progress layout pass.
     */
    private fun showSessions() {
        SwingUtilities.invokeLater {
            sessionTreePanel.refresh()
            (layout as CardLayout).show(this, CARD_SESSIONS)
        }
    }

    private fun openSession(sessionId: String) {
        chatTreeService.setActiveSession(sessionId)
        chatPanel.loadCurrentSession()
        showChat()
    }

    private fun createNewRoot() {
        chatTreeService.createNewSession()
        chatPanel.loadCurrentSession()
        showChat()
    }

    private fun createBranch(parentId: String) {
        val parent = chatTreeService.getSessionById(parentId) ?: return
        val title = JOptionPane.showInputDialog(
            this,
            "Name for the new branch:",
            "New Branch",
            JOptionPane.PLAIN_MESSAGE,
            null, null,
            "Branch: ${parent.title.take(25)}"
        ) as? String ?: return

        val branch = chatTreeService.createBranch(parentId, title)
        if (branch != null) {
            chatPanel.loadCurrentSession()
            showChat()
        }
    }

    private fun deleteSession(sessionId: String) {
        val session = chatTreeService.getSessionById(sessionId) ?: return
        chatTreeService.deleteSessionCascade(sessionId)
        if (chatTreeService.getAllSessions().isEmpty()) chatTreeService.createNewSession()
        sessionTreePanel.refresh()
        if (chatTreeService.getActiveSession().id != session.id) {
            chatPanel.loadCurrentSession()
        }
    }
}

// ==================== Helpers ====================

data class ModeItem(val id: String, val label: String) {
    override fun toString(): String = label
}

internal fun ChatMessage.toChatMessageDTO(): ChatMessageDTO {
    return ChatMessageDTO(
        role = when (this.role) {
            MessageRole.USER -> ChatRole.USER
            MessageRole.ASSISTANT -> ChatRole.ASSISTANT
            MessageRole.SYSTEM -> ChatRole.SYSTEM
        },
        content = this.content
    )
}
