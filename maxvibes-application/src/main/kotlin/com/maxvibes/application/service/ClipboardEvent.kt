package com.maxvibes.application.service

/**
 * Events that drive state transitions in the clipboard dialog state machine.
 *
 * Each event represents a user action or a plugin reaction that may change
 * the [com.maxvibes.domain.model.interaction.ClipboardSessionStatus] of a session.
 *
 * @see ClipboardSessionManager
 */
sealed class ClipboardEvent {

    /**
     * The user sent the first message in a new clipboard session.
     * Valid transition: IDLE → SESSION_ACTIVE.
     */
    object StartSession : ClipboardEvent()

    /**
     * The plugin generated a request JSON and copied it to the system clipboard.
     * Valid transitions:
     * - SESSION_ACTIVE → AWAITING_PASTE (initial send)
     * - AWAITING_PASTE → AWAITING_PASTE (re-send / retry)
     */
    object JsonCopied : ClipboardEvent()

    /**
     * The user pasted an LLM response back into the IDE and the plugin began processing it.
     * Valid transition: AWAITING_PASTE → SESSION_ACTIVE.
     */
    object ResponsePasted : ClipboardEvent()

    /**
     * The clipboard session was explicitly reset — triggered by opening a new chat,
     * deleting a session, or switching to a different session.
     * Always drives the target session to IDLE, regardless of the current state.
     */
    object Reset : ClipboardEvent()

    /**
     * The user manually overrode the AWAITING_PASTE state to continue the dialog
     * without pasting the LLM response. In-memory session workspace is preserved —
     * the next [ClipboardInteractionService.handleUserInput] call routes to continueDialog normally.
     * Valid transition: AWAITING_PASTE → SESSION_ACTIVE.
     */
    object ForceActivate : ClipboardEvent()

    /**
     * The user manually overrode the SESSION_ACTIVE state to go back to awaiting a paste —
     * for example, to paste a previously generated LLM response that was skipped.
     * In-memory session workspace is preserved.
     * Valid transition: SESSION_ACTIVE → AWAITING_PASTE.
     */
    object ForceAwaitPaste : ClipboardEvent()
}
