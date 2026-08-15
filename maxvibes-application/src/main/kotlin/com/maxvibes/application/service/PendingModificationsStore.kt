package com.maxvibes.application.service

import com.maxvibes.domain.model.check.CheckRequest
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.turn.TurnIntent

/**
 * Holds the modification set proposed by the LLM between the proposal and the user's
 * approve/reject decision, together with the commands, checks, commit message and turn
 * intent that arrived in the same response.
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
        val commitMessage: String?,
        /**
         * Сказал ли агент, что продолжит работу после этих правок. Живёт здесь по той же
         * причине, что и commit message: сказано это было до паузы на решение человека,
         * а понадобится только после неё.
         */
        val turnIntent: TurnIntent? = null,
        /**
         * Сборки и прогоны тестов, запрошенные тем же ответом. Держим их здесь, потому что
         * проверять имеет смысл уже применённый код: до Approve на диске старая версия.
         */
        val checks: List<CheckRequest> = emptyList()
    )

    private var pending: Pending? = null
    private var owner: String? = null

    /** Holds a proposed set for [sessionId]. Replaces any previously held set — the newest proposal wins. */
    fun hold(
        sessionId: String,
        modifications: List<InteractionModification>,
        commands: List<CommandRequest> = emptyList(),
        commitMessage: String? = null,
        turnIntent: TurnIntent? = null,
        checks: List<CheckRequest> = emptyList()
    ) {
        pending = Pending(modifications, commands, commitMessage, turnIntent, checks)
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
