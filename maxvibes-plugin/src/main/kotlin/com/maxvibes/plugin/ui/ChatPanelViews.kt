package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.modification.AppliedModInfo
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.domain.model.planning.PlanDiagram

/** Chat transcript rendering: bubbles, markdown, token info, commit message, plan diagram. */
interface ConversationView {
    fun appendToChat(text: String)
    fun appendAssistantMessage(text: String)
    fun formatMarkdown(text: String): String
    fun updateTokenDisplay()
    fun registerElementPaths(modifications: List<ModificationResult>)

    /** Adds a user message bubble, optionally with attached image thumbnails. */
    fun addUserMessageBubble(text: String, images: List<AttachedImage> = emptyList())
    fun addAssistantMessageBubble(
        text: String,
        tokenInfo: String?,
        modifications: List<ModificationResult>,
        metaFiles: List<String> = emptyList(),
        reasoning: String? = null,
        requestedViews: List<com.maxvibes.domain.model.code.RequestedViewInfo> = emptyList(),
        appliedModifications: List<AppliedModInfo> = emptyList()
    )

    /** Renders a post-apply errors block with Send to model / Dismiss buttons. */
    fun addPostApplyErrorsBubble(
        summary: String,
        details: String,
        onSend: () -> Unit,
        onDismiss: () -> Unit
    ): PostApplyErrorsView

    fun appendIconToLastBubble(icon: String)
    fun clearChatDisplay()

    /** Sets the commit message in the IDE VCS commit dialog. */
    fun setCommitMessage(message: String)

    /** Adds a "Схема" button under the last assistant bubble; opens the plan diagram viewer. */
    fun showDiagramButton(diagram: PlanDiagram)
}

/** Input field, status line and mode indicators. */
interface InputStatusView {
    fun setInputEnabled(enabled: Boolean)
    fun setStatus(text: String)
    fun updateModeIndicator()
    fun updateBreadcrumb()
    fun setPlanOnlyMode(enabled: Boolean)

    /** Called when a background operation encounters an error. */
    fun onError(message: String)

    /** Submits text through the exact same path as the main input's Send button. */
    fun sendUserMessage(text: String)
}

/** Combined transcript and input/status surface used by message dispatchers. */
interface MessageFlowView : ConversationView, InputStatusView

/** Attached trace/errors, image strip and one-shot skill chip. */
interface AttachmentView {
    /** Called when attached trace or errors change. */
    fun onAttachmentsChanged(trace: String?, errors: String?)

    /** Rebuilds the attached-images preview strip (empty list hides it). */
    fun onImagesChanged(images: List<AttachedImage>)

    /** Shows/hides the one-shot editor-skill chip; null label hides it. */
    fun onOneShotChanged(label: String?)
}

/** Session lifecycle notifications. */
interface SessionView {
    /** Called when the active session changes (create, delete, branch, load). */
    fun onSessionChanged(session: ChatSession?)

    /** Called when a session is renamed. */
    fun onSessionRenamed(session: ChatSession)

    /** Called to show the welcome screen (e.g. no sessions). */
    fun onShowWelcome()
}

/** Interactive question blocks. */
interface QuestionView {
    /** Renders an interactive question block; returns a handle for freeze/status updates. */
    fun addQuestionBubble(
        question: String,
        options: List<String>,
        onAnswer: (String) -> Unit
    ): QuestionBlockView
}

/** Interactive shell-command blocks. */
interface CommandView {
    /** Renders an interactive shell-command block; returns a view handle for status updates. */
    fun addCommandBubble(
        command: String,
        reason: String?,
        warnings: List<String>,
        onRun: () -> Unit,
        onDecline: (String?) -> Unit
    ): CommandBlockView

    /** Renders a Run all / Decline all bar above a multi-command batch. */
    fun addCommandBatchBar(count: Int, onRunAll: () -> Unit, onDeclineAll: () -> Unit): CommandBatchBarView
}
