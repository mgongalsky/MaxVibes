package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.MessageRole

/**
 * Responsible for transforming a list of [ChatMessage] into [DisplayMessage] objects ready for UI rendering.
 *
 * Encapsulates all message filtering and formatting logic, keeping ChatPanel (the View)
 * free from knowledge about the internal message storage format.
 *
 * Handles:
 * - Filtering out placeholder messages used in the clipboard workflow
 * - Stripping technical annotations appended to USER messages before sending to LLM
 *   (e.g. "[trace: N lines]", "[attached ide errors]", "[plan-only]")
 * - Removing blank messages that should not be displayed
 * - Carrying [ChatMessage.attachedFiles], [ChatMessage.requestedFiles], and
 *   [ChatMessage.appliedModificationPaths] into [DisplayMessage] so ASSISTANT bubbles
 *   fully reconstruct their footer (gathered files + modification links) after session reload
 */
class ConversationRenderer {

    // Regex patterns for stripping technical annotations from USER messages.
    // These annotations are added in sendApiMessage/sendClipboardMessage to provide LLM context,
    // but must not be shown to the user in the conversation history.
    private val traceAnnotationRegex = Regex("\\n\\[trace: \\d+ lines]")
    private val errorsAnnotationRegex = Regex("\\n\\[attached ide errors]")
    private val planOnlyAnnotationRegex = Regex("\\n\\[plan-only]")
    private val pastedLlmResponseRegex = Regex("\\[Pasted LLM response]\\n?")

    // The exact marker used for clipboard paste placeholders — filtered from display.
    private val pastedResponseMarker = "[Pasted LLM response]"

    /**
     * Transforms a list of raw session messages into display-ready messages.
     *
     * Pipeline:
     * 1. Filter out messages that should never be shown (e.g. clipboard placeholders)
     * 2. Format content (strip technical annotations from USER messages)
     * 3. Skip USER messages that become blank after formatting
     * 4. Carry [ChatMessage.attachedFiles], [ChatMessage.requestedFiles], and
     *    [ChatMessage.appliedModificationPaths] into [DisplayMessage] for full footer reconstruction
     *
     * @param messages raw messages from ChatSession.messages
     * @return filtered and formatted messages ready for rendering in ConversationPanel
     */
    fun render(messages: List<ChatMessage>): List<DisplayMessage> {
        return messages
            .filter { shouldDisplay(it) }
            .mapNotNull { message ->
                // Only USER messages need content cleanup — ASSISTANT and SYSTEM are stored as-is.
                val content = if (message.role == MessageRole.USER) {
                    formatContent(message.content)
                } else {
                    message.content
                }
                // A USER message might become blank after stripping annotations — skip it.
                if (content.isBlank()) null
                else DisplayMessage(
                    role = message.role,
                    content = content,
                    attachedFiles = message.attachedFiles,
                    requestedFiles = message.requestedFiles,
                    appliedModificationPaths = message.appliedModificationPaths
                )
            }
    }

    /**
     * Determines whether a message should be shown to the user at all.
     *
     * Filters out:
     * - Clipboard workflow placeholder "[Pasted LLM response]"
     * - Completely blank messages
     *
     * @param message the message to evaluate
     * @return true if the message should appear in the conversation UI
     */
    fun shouldDisplay(message: ChatMessage): Boolean {
        if (message.content.trim() == pastedResponseMarker) return false
        if (message.content.isBlank()) return false
        return true
    }

    /**
     * Formats a message's content for display by removing technical annotations.
     *
     * Strips annotations that were appended during message construction:
     * - "\n[trace: N lines]" — stack trace summary
     * - "\n[attached ide errors]" — IDE compiler errors
     * - "\n[plan-only]" — plan-only mode flag
     * - "[Pasted LLM response]" — clipboard workflow artifact
     *
     * @param content raw message content as stored in ChatSession
     * @return clean content suitable for user-facing display
     */
    fun formatContent(content: String): String {
        return content
            .replace(traceAnnotationRegex, "")
            .replace(errorsAnnotationRegex, "")
            .replace(planOnlyAnnotationRegex, "")
            .replace(pastedLlmResponseRegex, "")
            .trim()
    }
}

/**
 * A message processed by [ConversationRenderer] and ready for display in the UI.
 *
 * Unlike the raw [ChatMessage] domain object, DisplayMessage:
 * - Is guaranteed to have non-blank, clean content
 * - Has been filtered (no clipboard placeholders)
 * - Has had technical annotations stripped from USER messages
 * - Carries [attachedFiles] (files sent TO the LLM), [requestedFiles] (files requested BY the LLM),
 *   and [appliedModificationPaths] (successful code change paths) so ASSISTANT bubbles fully
 *   reconstruct their footer on session reload
 */
data class DisplayMessage(
    val role: MessageRole,
    val content: String,
    /** Files gathered and sent TO the LLM in this round (shown as "Gathered files" in the bubble footer). */
    val attachedFiles: List<String> = emptyList(),
    /** Files requested BY the LLM for the next round. */
    val requestedFiles: List<String> = emptyList(),
    /** ElementPath strings for each successfully applied code modification — reconstructed on reload as clickable links. */
    val appliedModificationPaths: List<String> = emptyList()
)
