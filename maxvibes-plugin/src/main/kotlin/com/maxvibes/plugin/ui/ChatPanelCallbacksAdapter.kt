package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.code.RequestedViewInfo
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.modification.AppliedModInfo
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.domain.model.planning.PlanDiagram

internal fun normalizeSystemMessage(text: String): String? {
    val value = text.trim()
    if (value.isBlank()) return null
    if (value.all { it == '─' || it == '═' || it == '━' || it == '-' }) return null
    if (value.contains("Paste this into")) return null
    if (value.contains("JSON copied")) return null
    if (value.startsWith("📋")) return null
    return value
}

/** Implements the controller-facing UI port without making ChatPanel own every callback body. */
class ChatPanelCallbacksAdapter(
    private val conversationPanel: ConversationPanel,
    private val inputPanel: ChatInputPanel,
    private val headerPanel: ChatHeaderPanel,
    private val specificPromptPanel: SpecificPromptPanel,
    private val claudeCliSettingsPanel: ClaudeCliSettingsPanel,
    private val onStatus: (String) -> Unit,
    private val onRender: () -> Unit,
    private val onUpdateBreadcrumb: () -> Unit,
    private val onUpdateTokenDisplay: () -> Unit,
    private val onRegisterElementPaths: (List<ModificationResult>) -> Unit,
    private val onCommitMessage: (String) -> Unit,
    private val onLoadSession: () -> Unit,
    private val onShowWelcome: () -> Unit,
    private val onSendCurrentInput: () -> Unit,
    private val onOpenDiagram: (PlanDiagram) -> Unit,
    private val onClearNavigation: () -> Unit
) : ChatPanelCallbacks {

    override fun appendToChat(text: String) {
        normalizeSystemMessage(text)?.let(conversationPanel::addSystemBubble)
    }

    override fun appendAssistantMessage(text: String) {
        conversationPanel.addAssistantBubble(formatMarkdown(text))
    }

    override fun addUserMessageBubble(text: String, images: List<AttachedImage>) {
        conversationPanel.addUserBubble(text, images)
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
        conversationPanel.addAssistantBubble(
            text = text,
            tokenInfo = tokenInfo,
            modifications = modifications,
            metaFiles = metaFiles,
            reasoning = reasoning,
            requestedViews = requestedViews,
            appliedModifications = appliedModifications
        )
        onRegisterElementPaths(modifications)
    }

    override fun addCommandBubble(
        command: String,
        reason: String?,
        warnings: List<String>,
        onRun: () -> Unit,
        onDecline: (String?) -> Unit
    ): CommandBlockView =
        conversationPanel.addCommandBubble(command, reason, warnings, onRun, onDecline)

    override fun addQuestionBubble(
        question: String,
        options: List<String>,
        onAnswer: (String) -> Unit
    ): QuestionBlockView = conversationPanel.addQuestionBubble(question, options, onAnswer)

    override fun sendUserMessage(text: String) {
        inputPanel.setText(text)
        onSendCurrentInput()
    }

    override fun addCommandBatchBar(
        count: Int,
        onRunAll: () -> Unit,
        onDeclineAll: () -> Unit
    ): CommandBatchBarView =
        conversationPanel.addCommandBatchBar(count, onRunAll, onDeclineAll)

    override fun clearChatDisplay() {
        conversationPanel.clearMessages()
        onClearNavigation()
    }

    override fun appendIconToLastBubble(icon: String) {
        conversationPanel.appendIconToLastBubble(icon)
    }

    override fun setInputEnabled(enabled: Boolean) {
        inputPanel.setControlsEnabled(enabled)
        headerPanel.setControlsEnabled(enabled)
        specificPromptPanel.setControlsEnabled(enabled)
        claudeCliSettingsPanel.setControlsEnabled(enabled)
    }

    override fun setStatus(text: String) {
        onStatus(text)
    }

    override fun updateModeIndicator() {
        onRender()
    }

    override fun updateBreadcrumb() {
        onUpdateBreadcrumb()
    }

    override fun registerElementPaths(modifications: List<ModificationResult>) {
        onRegisterElementPaths(modifications)
    }

    override fun formatMarkdown(text: String): String = text

    override fun updateTokenDisplay() {
        onUpdateTokenDisplay()
    }

    override fun setCommitMessage(message: String) {
        onCommitMessage(message)
    }

    override fun setPlanOnlyMode(enabled: Boolean) {
        inputPanel.setPlanOnly(enabled)
    }

    override fun onAttachmentsChanged(trace: String?, errors: String?) {
        onRender()
    }

    override fun onImagesChanged(images: List<AttachedImage>) {
        inputPanel.showImages(images)
    }

    override fun onError(message: String) {
        onStatus(message)
    }

    override fun onSessionChanged(session: ChatSession?) {
        onLoadSession()
    }

    override fun onSessionRenamed(session: ChatSession) {
        onRender()
    }

    override fun onShowWelcome() {
        onShowWelcome.invoke()
    }

    override fun addPostApplyErrorsBubble(
        summary: String,
        details: String,
        onSend: () -> Unit,
        onDismiss: () -> Unit
    ): PostApplyErrorsView =
        conversationPanel.addPostApplyErrorsBubble(summary, details, onSend, onDismiss)

    override fun onOneShotChanged(label: String?) {
        inputPanel.showOneShot(label)
    }

    override fun showDiagramButton(diagram: PlanDiagram) {
        conversationPanel.addDiagramButton { onOpenDiagram(diagram) }
    }
}
