package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.domain.model.modification.ModificationResult
import javax.swing.JComponent
import javax.swing.JOptionPane

interface ChatSessionDialogs {
    fun requestBranchTitle(defaultTitle: String): String?
    fun confirmDelete(session: ChatSession, childCount: Int): Boolean
}

class SwingChatSessionDialogs(
    private val parent: JComponent
) : ChatSessionDialogs {

    override fun requestBranchTitle(defaultTitle: String): String? =
        JOptionPane.showInputDialog(
            parent,
            "Name for the new branch:",
            "New Branch",
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            defaultTitle
        ) as? String

    override fun confirmDelete(session: ChatSession, childCount: Int): Boolean {
        val message = if (childCount > 0) {
            "Delete '${session.title}'? $childCount branch(es) will be re -attached to parent."
        } else {
            "Delete '${session.title}'?"
        }
        return JOptionPane.showConfirmDialog(
            parent,
            message,
            "Delete Chat",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        ) == JOptionPane.YES_OPTION
    }
}

/**
 * Coordinates session-facing UI flows while leaving rendering widgets behind a view boundary.
 */
class ChatSessionUiCoordinator(
    private val activeSession: () -> ChatSession,
    private val sessionPath: (String) -> List<ChatSession>,
    private val parentSession: (String) -> ChatSession?,
    private val childCount: (String) -> Int,
    private val setActiveSession: (String) -> Unit,
    private val transcriptRenderer: SessionTranscriptRenderer,
    private val transcriptView: SessionTranscriptView,
    private val dialogs: ChatSessionDialogs,
    private val currentMode: () -> InteractionMode,
    private val contextFilesCount: () -> Int,
    private val clearNavigation: () -> Unit,
    private val registerModifications: (List<ModificationResult>) -> Unit,
    private val clearAttachments: () -> Unit,
    private val createSession: () -> Unit,
    private val branchSession: (String, String) -> Unit,
    private val deleteSession: (String) -> Unit,
    private val renameSession: (String, String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onRefresh: () -> Unit
) {

    fun loadCurrentSession() {
        val session = activeSession()
        clearNavigation()
        val rendered = transcriptRenderer.render(
            session = session,
            sessionPath = sessionPath(session.id),
            view = transcriptView,
            onModificationsRestored = registerModifications
        )
        if (!rendered) showWelcome(session)
        onRefresh()
    }

    fun selectSession(sessionId: String) {
        setActiveSession(sessionId)
        loadCurrentSession()
    }

    fun renameSession(sessionId: String, title: String) {
        renameSession.invoke(sessionId, title)
        onStatus("Renamed to '$title'")
    }

    fun createNewChat() {
        clearAttachments()
        createSession()
        onStatus("New dialog")
    }

    fun createBranch() {
        val active = activeSession()
        val title = dialogs.requestBranchTitle("Branch: ${active.title.take(25)}") ?: return
        clearAttachments()
        branchSession(active.id, title)
        onStatus("Branch: $title")
    }

    fun deleteCurrentChat() {
        val session = activeSession()
        if (!dialogs.confirmDelete(session, childCount(session.id))) return
        clearAttachments()
        deleteSession(session.id)
        onStatus("Chat deleted")
    }

    fun showWelcome(session: ChatSession = activeSession()) {
        val modeLabel = when (currentMode()) {
            InteractionMode.API -> "API — direct LLM calls"
            InteractionMode.CLIPBOARD -> "Clipboard — paste JSON into Claude/ChatGPT"
            InteractionMode.CHEAP_API -> "Cheap API — budget model"
            InteractionMode.CLAUDE_CODE -> "Claude Code — local CLI process"
        }
        transcriptView.addSystem("MaxVibes  •  $modeLabel")
        if (session.depth > 0) {
            val parentTitle = parentSession(session.id)?.title ?: "?"
            transcriptView.addSystem("└ Branch from: '$parentTitle'")
        }
        val contextCount = contextFilesCount()
        if (contextCount > 0) {
            transcriptView.addSystem("📎 $contextCount global context file(s) active")
        }
        transcriptView.addSystem("Type your task • Ctrl+Enter to send")
    }
}
