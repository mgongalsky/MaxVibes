package com.maxvibes.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.plugin.chat.ChatHistoryService
import com.maxvibes.plugin.chat.ChatMessage
import com.maxvibes.plugin.chat.MessageRole
import java.awt.CardLayout
import javax.swing.JOptionPane
import javax.swing.JPanel

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