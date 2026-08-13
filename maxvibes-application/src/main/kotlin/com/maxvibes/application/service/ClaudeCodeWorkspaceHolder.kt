package com.maxvibes.application.service

/** Holds the in-memory coding-agent workspace together with its owning chat session id. */
internal class CodingAgentWorkspaceHolder {
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

internal typealias ClaudeCodeWorkspaceHolder = CodingAgentWorkspaceHolder
