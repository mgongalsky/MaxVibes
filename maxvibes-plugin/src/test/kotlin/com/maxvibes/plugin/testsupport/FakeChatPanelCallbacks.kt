package com.maxvibes.plugin.testsupport

import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.code.RequestedViewInfo
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.modification.AppliedModInfo
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.domain.model.planning.PlanDiagram
import com.maxvibes.plugin.ui.ChatPanelCallbacks
import com.maxvibes.plugin.ui.CommandBatchBarView
import com.maxvibes.plugin.ui.CommandBlockView
import com.maxvibes.plugin.ui.PostApplyErrorsView
import com.maxvibes.plugin.ui.QuestionBlockView

/**
 * Recording fake for [ChatPanelCallbacks].
 *
 * Preferred over a MockK proxy: the interface is large and evolves often, so a
 * hand-written fake fails compilation in exactly one place when a member changes,
 * instead of breaking every mockk-based test at runtime. Interactions are recorded
 * into public lists — assert on them directly.
 */
class FakeChatPanelCallbacks : ChatPanelCallbacks {
    val attachmentsChanges = mutableListOf<Pair<String?, String?>>()
    val sessionChanges = mutableListOf<ChatSession?>()
    val renamedSessions = mutableListOf<ChatSession>()
    val reportedErrors = mutableListOf<String>()
    val statusUpdates = mutableListOf<String>()
    val userBubbles = mutableListOf<String>()
    val assistantBubbles = mutableListOf<String>()
    val sentUserMessages = mutableListOf<String>()
    val commitMessages = mutableListOf<String>()
    val imagesChanges = mutableListOf<List<AttachedImage>>()
    val oneShotLabels = mutableListOf<String?>()
    val appendedIcons = mutableListOf<String>()
    var welcomeShown = false
    var chatCleared = false
    var inputEnabled: Boolean? = null
    var planOnlyMode: Boolean? = null

    override fun appendToChat(text: String) {}

    override fun appendAssistantMessage(text: String) {
        assistantBubbles.add(text)
    }

    override fun setInputEnabled(enabled: Boolean) {
        inputEnabled = enabled
    }

    override fun setStatus(text: String) {
        statusUpdates.add(text)
    }

    override fun updateModeIndicator() {}

    override fun updateBreadcrumb() {}

    override fun registerElementPaths(modifications: List<ModificationResult>) {}

    override fun formatMarkdown(text: String): String = text

    override fun updateTokenDisplay() {}

    override fun addUserMessageBubble(text: String, images: List<AttachedImage>) {
        userBubbles.add(text)
    }

    override fun addAssistantMessageBubble(
        text: String,
        tokenInfo: String?,
        modifications: List<ModificationResult>,
        metaFiles: List<String>,
        reasoning: String?,
        requestedViews: List<RequestedViewInfo>,
        appliedModifications: List<AppliedModInfo>
    ) {
        assistantBubbles.add(text)
    }

    override fun addPostApplyErrorsBubble(
        summary: String,
        details: String,
        onSend: () -> Unit,
        onDismiss: () -> Unit
    ): PostApplyErrorsView = object : PostApplyErrorsView {
        override fun setSent() {}
        override fun setDismissed() {}
    }

    override fun addQuestionBubble(
        question: String,
        options: List<String>,
        onAnswer: (String) -> Unit
    ): QuestionBlockView = object : QuestionBlockView {
        override fun setAnswered(answer: String) {}
        override fun setDismissed() {}
    }

    override fun sendUserMessage(text: String) {
        sentUserMessages.add(text)
    }

    override fun appendIconToLastBubble(icon: String) {
        appendedIcons.add(icon)
    }

    override fun clearChatDisplay() {
        chatCleared = true
    }

    override fun setPlanOnlyMode(enabled: Boolean) {
        planOnlyMode = enabled
    }

    override fun setCommitMessage(message: String) {
        commitMessages.add(message)
    }

    override fun onAttachmentsChanged(trace: String?, errors: String?) {
        attachmentsChanges.add(trace to errors)
    }

    override fun onError(message: String) {
        reportedErrors.add(message)
    }

    override fun onSessionChanged(session: ChatSession?) {
        sessionChanges.add(session)
    }

    override fun onSessionRenamed(session: ChatSession) {
        renamedSessions.add(session)
    }

    override fun onShowWelcome() {
        welcomeShown = true
    }

    override fun addCommandBubble(
        command: String,
        reason: String?,
        warnings: List<String>,
        onRun: () -> Unit,
        onDecline: (String?) -> Unit
    ): CommandBlockView = object : CommandBlockView {
        override fun setRunning() {}
        override fun setQueued() {}
        override fun setResult(headline: String, output: String, ok: Boolean) {}
        override fun setDeclined(comment: String?) {}
    }

    override fun addCommandBatchBar(
        count: Int,
        onRunAll: () -> Unit,
        onDeclineAll: () -> Unit
    ): CommandBatchBarView = object : CommandBatchBarView {
        override fun dismiss() {}
    }

    override fun onImagesChanged(images: List<AttachedImage>) {
        imagesChanges.add(images)
    }

    override fun onOneShotChanged(label: String?) {
        oneShotLabels.add(label)
    }

    override fun showDiagramButton(diagram: PlanDiagram) {}
}
