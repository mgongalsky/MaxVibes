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
import com.maxvibes.plugin.service.MaxVibesLogger

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

    private val chatPanel = ChatPanel(project, toolWindow, onShowSessions = { showSessions() })
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

        MaxVibesLogger.info("ToolWindow", "init", mapOf("project" to project.name))
        com.intellij.openapi.util.Disposer.register(project, com.intellij.openapi.Disposable {
            MaxVibesLogger.shutdown()
        })
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
        chatPanel.resetClipboard()
        chatHistory.deleteSessionCascade(sessionId)
        if (chatHistory.getAllSessions().isEmpty()) chatHistory.createNewSession()
        sessionTreePanel.refresh()
        // Если удалили активную сессию — перезагружаем chat
        if (chatHistory.getActiveSession().id != session.id) {
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