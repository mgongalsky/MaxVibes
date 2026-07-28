package com.maxvibes.application.service

import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.interaction.InteractionModification

/**
 * Holds the modification set proposed by the LLM between the proposal and the user's
 * approve/reject decision, together with the commands and commit message that arrived
 * in the same response.
 *
 * Ownership contract: the held set belongs to exactly one chat session (the sessionId
 * passed to [hold]). Queries from any other session see no pending state — this guards
 * against a cross-session apply when the user switches chats mid-approval.
 *
 * In-memory only BY DESIGN: an IDE restart before Approve loses the pending set —
 * the user re-sends the message and the LLM re-proposes the modifications.
 */
class PendingModificationsStore {

    data class Pending(
        val modifications: List<InteractionModification>,
        val commands: List<CommandRequest>,
        val commitMessage: String?
    )

    private var pending: Pending? = null
    private var owner: String? = null

    /** Holds a proposed set for [sessionId]. Replaces any previously held set — the newest proposal wins. */
    fun hold(
        sessionId: String,
        modifications: List<InteractionModification>,
        commands: List<CommandRequest> = emptyList(),
        commitMessage: String? = null
    ) {
        pending = Pending(modifications, commands, commitMessage)
        owner = sessionId
    }

    /** True when a non-empty modification set is held for exactly this session. */
    fun hasPendingFor(sessionId: String): Boolean =
        owner == sessionId && pending?.modifications?.isNotEmpty() == true

    /**
     * Returns the held set for this session and clears the store — one-shot consumption
     * shared by the approve and reject paths. Null when nothing is held for [sessionId];
     * a foreign session's set stays untouched.
     */
    fun take(sessionId: String): Pending? {
        if (!hasPendingFor(sessionId)) return null
        val result = pending
        clear()
        return result
    }

    fun clear() {
        pending = null
        owner = null
    }
}
