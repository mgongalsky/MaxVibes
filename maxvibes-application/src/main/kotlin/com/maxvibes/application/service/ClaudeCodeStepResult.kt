package com.maxvibes.application.service

import com.maxvibes.domain.model.code.RequestedViewInfo
import com.maxvibes.domain.model.modification.ModificationResult

/**
 * Outcome of a single step in the Claude Code interaction flow.
 *
 * Mirrors [ClipboardStepResult] but adapted for the auto-transport model:
 * there is no "waiting for paste" — instead, a response with [requestedViews]
 * suspends the flow in [WaitingForApprove] until the user approves the next round.
 */
sealed class ClaudeCodeStepResult {

    /**
     * The Claude Code response was received and contains [requestedViews] — the UI
     * should display the assistant message together with an Approve button.
     *
     * Pressing Approve calls [ClaudeCodeInteractionService.approve], which gathers
     * the requested files and sends a minimal-context follow-up to the same process.
     *
     * @param assistantMessage  free-text body of the response (already persisted into the dialog history).
     * @param requestedViews    typed file/element view requests with granularity for colour-coded UI.
     * @param inputTokens       estimated tokens sent in this turn.
     * @param outputTokens      estimated tokens received from claude in this turn.
     * @param llmReasoning      optional reasoning text from the LLM (shown collapsed in UI).
     * @param durationMs        wall-clock time the send took, in milliseconds.
     *                          Zero when unavailable (e.g. legacy code paths).
     */
    data class WaitingForApprove(
        val assistantMessage: String,
        val requestedViews: List<RequestedViewInfo>,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val llmReasoning: String? = null,
        val durationMs: Long = 0L
    ) : ClaudeCodeStepResult()

    /**
     * The turn is complete. The response was either text-only or contained
     * modifications that have already been applied to the project.
     *
     * @param message        text shown to the user (LLM message + any extra notices).
     * @param modifications  results of applying [com.maxvibes.domain.model.interaction.InteractionModification]s; empty for text-only turns.
     * @param success        true when all modifications applied successfully (or there were none).
     * @param inputTokens    estimated tokens sent in this turn.
     * @param outputTokens   estimated tokens received from claude in this turn.
     * @param llmReasoning   optional reasoning text from the LLM.
     * @param commitMessage  optional commit message proposed by the LLM — UI may inject it into the IDE commit dialog.
     * @param durationMs     wall-clock time the send took, in milliseconds.
     *                       Zero when unavailable (e.g. legacy code paths).
     */
    data class Completed(
        val message: String,
        val modifications: List<ModificationResult>,
        val success: Boolean,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val llmReasoning: String? = null,
        val commitMessage: String? = null,
        val durationMs: Long = 0L
    ) : ClaudeCodeStepResult()

    /**
     * A general application-level error (no active workspace, session not found,
     * invalid status for the requested operation, etc.). Distinct from [TransportError]
     * which signals process/IO failures.
     */
    data class Error(val message: String) : ClaudeCodeStepResult()

    /**
     * The underlying [com.maxvibes.application.port.output.ClaudeCodePort] reported a transport
     * failure (process crashed, binary not found, timeout, parse error). UI may suggest
     * checking Claude Code settings and retrying.
     */
    data class TransportError(val detail: String) : ClaudeCodeStepResult()
}
