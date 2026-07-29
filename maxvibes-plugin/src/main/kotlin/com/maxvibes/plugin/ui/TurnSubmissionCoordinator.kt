package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.plugin.service.MaxVibesLogger

/**
 * Owns top-level send, approve and clipboard-redo orchestration.
 *
 * Mode dispatch and UI effects are injected as narrow functions so this class
 * has no dependency on the aggregate ChatPanelCallbacks or concrete dispatchers.
 */
internal class TurnSubmissionCoordinator(
    private val documentSaver: DocumentSaver,
    private val dismissQuestionTurn: () -> Unit,
    private val attachments: AttachmentCoordinator,
    private val appendToChat: (String) -> Unit,
    private val dispatchApi: (
        message: String,
        trace: String?,
        errors: String?,
        isPlanOnly: Boolean,
        isDryRun: Boolean
    ) -> Unit,
    private val dispatchClipboard: (
        message: String,
        trace: String?,
        errors: String?,
        isPlanOnly: Boolean,
        addHistory: Boolean,
        promptName: String?
    ) -> Unit,
    private val dispatchCheapApi: (
        message: String,
        trace: String?,
        errors: String?,
        isPlanOnly: Boolean,
        isDryRun: Boolean
    ) -> Unit,
    private val dispatchClaudeCode: (
        message: String,
        trace: String?,
        errors: String?,
        isPlanOnly: Boolean,
        promptName: String?,
        images: List<AttachedImage>
    ) -> Unit,
    private val approveClaudeCode: (trace: String?, errors: String?) -> Unit,
    private val redoClipboardJson: () -> Unit
) {
    fun sendMessage(
        userInput: String,
        isPlanOnly: Boolean,
        isDryRun: Boolean,
        mode: InteractionMode,
        addHistory: Boolean = false,
        selectedSpecificPromptName: String? = null
    ) {
        documentSaver.saveAllDocuments()
        dismissQuestionTurn()

        val pending = attachments.consume()
        val prepared = SendPreparationPolicy.prepare(
            pending = pending,
            selectedSpecificPromptName = selectedSpecificPromptName,
            mode = mode
        )

        MaxVibesLogger.info(
            "TurnSubmission",
            "sendMessage",
            mapOf(
                "mode" to mode.name,
                "msgLen" to userInput.length,
                "isPlanOnly" to isPlanOnly,
                "hasTrace" to (pending.trace != null),
                "hasErrors" to (prepared.errors != null),
                "images" to prepared.images.size,
                "addHistory" to addHistory,
                "specificPrompt" to (prepared.effectivePromptName ?: "null"),
                "oneShot" to (prepared.oneShotLabel ?: "null")
            )
        )

        prepared.warnings.forEach { warning -> appendToChat(warning) }

        when (mode) {
            InteractionMode.API -> dispatchApi(
                userInput,
                prepared.effectiveTrace,
                prepared.errors,
                isPlanOnly,
                isDryRun
            )

            InteractionMode.CLIPBOARD -> dispatchClipboard(
                userInput,
                prepared.effectiveTrace,
                prepared.errors,
                isPlanOnly,
                addHistory,
                prepared.effectivePromptName
            )

            InteractionMode.CHEAP_API -> dispatchCheapApi(
                userInput,
                prepared.effectiveTrace,
                prepared.errors,
                isPlanOnly,
                isDryRun
            )

            InteractionMode.CLAUDE_CODE -> dispatchClaudeCode(
                userInput,
                prepared.effectiveTrace,
                prepared.errors,
                isPlanOnly,
                prepared.effectivePromptName,
                prepared.images
            )
        }
    }

    fun approve() {
        documentSaver.saveAllDocuments()
        val pending = attachments.snapshot()

        if (pending.images.isNotEmpty()) {
            appendToChat(
                "⚠️ ${pending.images.size} attached image(s) dropped — attach them to a regular message, not to Approve"
            )
        }
        if (pending.oneShot != null) {
            appendToChat(
                "⚠️ One-shot editor skill dropped — invoke it with a regular message, not with Approve"
            )
        }

        attachments.clearAfterSend()
        approveClaudeCode(pending.trace, pending.errors)
    }

    fun redoClipboardJson() {
        documentSaver.saveAllDocuments()
        redoClipboardJson.invoke()
    }
}
