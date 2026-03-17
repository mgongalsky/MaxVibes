package com.maxvibes.domain.model.interaction

/**
 * Typed status of a clipboard-based LLM dialog for a single [com.maxvibes.domain.model.chat.ChatSession].
 *
 * The clipboard dialog follows a simple state machine:
 * [IDLE] → (user sends message) → [AWAITING_PASTE] → (user pastes LLM response) → [SESSION_ACTIVE] → ...
 *
 * Each session tracks its own status independently, enabling multiple concurrent clipboard sessions.
 */
enum class ClipboardSessionStatus {

    /**
     * No active clipboard dialog exists for this session.
     * This is the initial/default state — the session has not yet started a clipboard exchange,
     * or the previous exchange was fully completed or discarded.
     */
    IDLE,

    /**
     * A clipboard dialog is open and the session is waiting for the next user message.
     * The user has already pasted at least one LLM response, and the conversation can continue.
     */
    SESSION_ACTIVE,

    /**
     * The request JSON has been copied to the system clipboard and the plugin is waiting
     * for the user to paste the LLM response back into the IDE.
     */
    AWAITING_PASTE
}
