package com.maxvibes.application.service

/**
 * Events that drive state transitions in the clipboard / Claude Code dialog state machine.
 *
 * Each event represents a user action or a plugin reaction that may change
 * the [com.maxvibes.domain.model.interaction.ClipboardSessionStatus] of a session.
 *
 * Events fall into three groups:
 *  - **Clipboard mode:** [StartSession], [JsonCopied], [ResponsePasted], [ForceActivate], [ForceAwaitPaste]
 *  - **Claude Code mode:** [ResponseReceived], [Approved] (plus shared [StartSession])
 *  - **Universal:** [Reset]
 *
 * Sending a clipboard event while in [com.maxvibes.domain.model.interaction.ClipboardSessionStatus.AWAITING_APPROVE]
 * (or vice versa) is treated as an invalid transition — the manager logs a warning
 * and returns `false` without changing state.
 *
 * @see ClipboardSessionManager
 */
sealed class ClipboardEvent {

    /**
     * The user sent the first message in a new session.
     * Valid transition: IDLE → SESSION_ACTIVE.
     *
     * Used by both clipboard mode and Claude Code mode.
     */
    object StartSession : ClipboardEvent()

    /**
     * Clipboard mode only.
     * The plugin generated a request JSON and copied it to the system clipboard.
     * Valid transitions:
     * - SESSION_ACTIVE → AWAITING_PASTE (initial send)
     * - AWAITING_PASTE → AWAITING_PASTE (re-send / retry)
     */
    object JsonCopied : ClipboardEvent()

    /**
     * Clipboard mode only.
     * The user pasted an LLM response back into the IDE and the plugin began processing it.
     * Valid transition: AWAITING_PASTE → SESSION_ACTIVE.
     */
    object ResponsePasted : ClipboardEvent()

    /**
     * The session was explicitly reset — triggered by opening a new chat,
     * deleting a session, or switching to a different session.
     * Always drives the target session to IDLE, regardless of the current state.
     *
     * Used by both clipboard mode and Claude Code mode.
     */
    object Reset : ClipboardEvent()

    /**
     * Clipboard mode only.
     * The user manually overrode the AWAITING_PASTE state to continue the dialog
     * without pasting the LLM response. In-memory session workspace is preserved —
     * the next [ClipboardInteractionService.handleUserInput] call routes to continueDialog normally.
     * Valid transition: AWAITING_PASTE → SESSION_ACTIVE.
     */
    object ForceActivate : ClipboardEvent()

    /**
     * Clipboard mode only.
     * The user manually overrode the SESSION_ACTIVE state to go back to awaiting a paste —
     * for example, to paste a previously generated LLM response that was skipped.
     * In-memory session workspace is preserved.
     * Valid transition: SESSION_ACTIVE → AWAITING_PASTE.
     */
    object ForceAwaitPaste : ClipboardEvent()

    /**
     * Claude Code mode only.
     *
     * The plugin received and parsed a response from the Claude Code process.
     * The target status depends on whether the response asked for files.
     *
     * @property hasRequestedViews true when [com.maxvibes.domain.model.interaction.InteractionResponse.codeViewRequests]
     *           is non-empty — the LLM is asking the plugin to gather more files
     *           before continuing.
     *
     * Valid transitions:
     *  - SESSION_ACTIVE → AWAITING_APPROVE  (`hasRequestedViews = true`)
     *  - SESSION_ACTIVE → SESSION_ACTIVE    (`hasRequestedViews = false`)
     *  - AWAITING_APPROVE → AWAITING_APPROVE (`hasRequestedViews = true`,
     *                                          chained file requests in one turn)
     *  - AWAITING_APPROVE → SESSION_ACTIVE  (`hasRequestedViews = false`,
     *                                          final response after Approve flow)
     */
    data class ResponseReceived(val hasRequestedViews: Boolean) : ClipboardEvent()

    /**
     * Claude Code mode only.
     *
     * The user pressed Approve while the session was in AWAITING_APPROVE.
     * The service follows up with a [ClaudeCodeInteractionService.approve]
     * that gathers the requested files and sends the next request.
     *
     * Valid transition: AWAITING_APPROVE → SESSION_ACTIVE.
     */
    object Approved : ClipboardEvent()
}
