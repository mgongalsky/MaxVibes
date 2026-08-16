package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.plugin.service.AttachmentNote

/** Rendering boundary used by [SessionTranscriptRenderer]. */
interface SessionTranscriptView {
    fun clear()
    fun addUser(text: String)
    fun addAssistant(message: DisplayMessage, modifications: List<ModificationResult>)
    fun addSystem(text: String)

    /** Дефолт деградирует до обычного системного сообщения — для двойников без UI. */
    fun addAttachment(relativePath: String, caption: String) = addSystem(caption)
}

/** Thin adapter that keeps [ConversationPanel] free from transcript orchestration logic. */
class ConversationPanelTranscriptView(
    private val panel: ConversationPanel
) : SessionTranscriptView {

    override fun clear() {
        panel.clearMessages()
    }

    override fun addUser(text: String) {
        panel.addUserBubble(text)
    }

    override fun addAssistant(
        message: DisplayMessage,
        modifications: List<ModificationResult>
    ) {
        panel.addAssistantBubble(
            text = message.content,
            tokenInfo = message.tokenInfo,
            modifications = modifications,
            metaFiles = message.attachedFiles,
            reasoning = message.reasoning,
            requestedViews = message.requestedViews,
            appliedModifications = message.appliedModifications
        )
    }

    override fun addSystem(text: String) {
        panel.addSystemBubble(text)
    }
    override fun addAttachment(relativePath: String, caption: String) {
        panel.addAttachmentBubble(relativePath, caption)
    }
}

/**
 * Rebuilds the visible transcript of one persisted chat session.
 *
 * Returns false for an empty session so the owner can render its welcome state instead.
 */
class SessionTranscriptRenderer(
    private val conversationRenderer: ConversationRenderer = ConversationRenderer()
) {

    fun render(
        session: ChatSession,
        sessionPath: List<ChatSession>,
        view: SessionTranscriptView,
        onModificationsRestored: (List<ModificationResult>) -> Unit
    ): Boolean {
        view.clear()
        if (session.messages.isEmpty()) return false

        if (sessionPath.size > 1) {
            val chain = sessionPath.dropLast(1).joinToString(" › ") { it.title.take(25) }
            view.addSystem("└ Branch of: $chain")
        }

        conversationRenderer.render(session.messages).forEach { message ->
            when (message.role) {
                MessageRole.USER -> view.addUser(message.content)
                MessageRole.SYSTEM -> {
                    val attachmentPath = AttachmentNote.parsePath(message.content)
                    if (attachmentPath != null) {
                        view.addAttachment(attachmentPath, AttachmentNote.caption(message.content))
                    } else {
                        view.addSystem(message.content)
                    }
                }

                MessageRole.ASSISTANT -> {
                    val modifications = restoreModifications(message.appliedModificationPaths)
                    view.addAssistant(message, modifications)
                    onModificationsRestored(modifications)
                }
            }
        }
        return true
    }

    private fun restoreModifications(paths: List<String>): List<ModificationResult> {
        return paths.mapNotNull { path ->
            runCatching {
                val elementPath = ElementPath(path)
                ModificationResult.Success(
                    modification = Modification.ReplaceElement(
                        targetPath = elementPath,
                        newContent = ""
                    ),
                    affectedPath = elementPath,
                    resultContent = null
                )
            }.getOrNull()
        }
    }
}
