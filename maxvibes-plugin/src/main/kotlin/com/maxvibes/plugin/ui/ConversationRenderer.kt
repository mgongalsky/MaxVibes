package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.MessageRole

/**
 * Transforms a list of [ChatMessage] into [DisplayMessage] objects ready for UI rendering.
 *
 * Encapsulates all message filtering and formatting logic, keeping [ChatPanel] (the View)
 * free from knowledge about the internal message storage format.
 *
 * Responsibilities:
 * - Filter out placeholder messages used in the clipboard workflow.
 * - Strip technical annotations appended to USER messages before sending to LLM
 *   (e.g. "[trace: N lines]", "[attached ide errors]", "[plan-only]").
 * - Remove blank messages that should not be displayed.
 * - Carry persisted bubble metadata ([attachedFiles], [appliedModificationPaths],
 *   [reasoning], [tokenInfo]) into [DisplayMessage] so ASSISTANT bubbles are
 *   reconstructed faithfully after session reload.
 */
class ConversationRenderer {

    // ── Annotation strip patterns ──────────────────────────────────────────────
    // These annotations are injected into USER messages before LLM dispatch but
    // must not be visible in the conversation history.

    private val traceAnnotationRegex = Regex("\\n\\[trace: \\d+ lines]")
    private val errorsAnnotationRegex = Regex("\\n\\[attached ide errors]")
    private val planOnlyAnnotationRegex = Regex("\\n\\[plan-only]")
    private val pastedLlmResponseRegex = Regex("\\[Pasted LLM response]\\n?")

    /** Exact content of clipboard paste placeholders — filtered from display entirely. */
    private val pastedResponseMarker = "[Pasted LLM response]"

    /**
     * Transforms raw session messages into display-ready messages.
     *
     * Pipeline:
     * 1. Filter out messages that should never be shown (clipboard placeholders, blanks).
     * 2. Format content — strip technical annotations from USER messages.
     * 3. Skip USER messages that become blank after formatting.
     * 4. Carry bubble metadata from [ChatMessage] into [DisplayMessage] so ASSISTANT
     *    bubbles can fully reconstruct their footer after session reload.
     *
     * @param messages raw messages from [com.maxvibes.domain.model.chat.ChatSession.messages]
     * @return filtered and formatted messages ready for rendering in [ConversationPanel]
     */
    fun render(messages: List<ChatMessage>): List<DisplayMessage> {
        return messages
            .filter { shouldDisplay(it) }
            .mapNotNull { message ->
                // Only USER messages need content cleanup; ASSISTANT/SYSTEM are stored as-is.
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
                    appliedModificationPaths = message.appliedModificationPaths,
                    reasoning = message.reasoning,
                    tokenInfo = message.tokenInfo
                )
            }
    }

    /**
     * Returns true if the message should appear in the conversation UI.
     *
     * Filters out:
     * - Clipboard workflow placeholder "[Pasted LLM response]"
     * - Completely blank messages
     */
    fun shouldDisplay(message: ChatMessage): Boolean {
        if (message.content.trim() == pastedResponseMarker) return false
        if (message.content.isBlank()) return false
        return true
    }

    /**
     * Removes technical annotations from a USER message's content before display.
     *
     * Strips:
     * - `\n[trace: N lines]` — stack trace summary
     * - `\n[attached ide errors]` — IDE compiler errors block
     * - `\n[plan-only]` — plan-only mode flag
     * - `[Pasted LLM response]` — clipboard workflow artifact
     *
     * @param content raw message content as stored in [com.maxvibes.domain.model.chat.ChatSession]
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
 * A message processed by [ConversationRenderer] and ready for rendering in the UI.
 *
 * Unlike the raw [ChatMessage] domain object, [DisplayMessage]:
 * - Is guaranteed to have non-blank, clean content.
 * - Has been filtered (no clipboard placeholders).
 * - Has had technical annotations stripped from USER messages.
 * - Carries bubble metadata ([attachedFiles], [appliedModificationPaths], [reasoning],
 *   [tokenInfo]) so the ASSISTANT bubble footer is identical before and after IDE restart.
 *
 * @param attachedFiles        Files that were gathered and sent TO the LLM in this round.
 * @param appliedModificationPaths String-encoded ElementPath values for applied mods.
 * @param reasoning            Optional LLM reasoning block shown in the footer.
 * @param tokenInfo            Human-readable token summary for the footer.
 */
data class DisplayMessage(
    val role: MessageRole,
    val content: String,
    val attachedFiles: List<String> = emptyList(),
    val appliedModificationPaths: List<String> = emptyList(),
    val reasoning: String? = null,
    val tokenInfo: String? = null
)
