package com.maxvibes.application.service

/**
 * Holds the in-memory Claude Code workspace state together with its owning session id.
 *
 * Ownership invariant: [state] belongs to the session recorded in [owner]; the pair is
 * always written atomically via [install] and dropped atomically via [clear], so a
 * positive [isOwnedBy] guarantees a non-null [state] for that session.
 */
internal class ClaudeCodeWorkspaceHolder {

    var state: ClipboardSessionState? = null
        private set

    var owner: String? = null
        private set

    fun isOwnedBy(sessionId: String): Boolean = state != null && owner == sessionId

    fun install(sessionId: String, state: ClipboardSessionState) {
        this.state = state
        this.owner = sessionId
    }

    fun clear() {
        state = null
        owner = null
    }
}
