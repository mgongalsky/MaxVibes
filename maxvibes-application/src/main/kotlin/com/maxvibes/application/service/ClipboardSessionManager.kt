package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.application.port.output.LoggerPort
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus

private const val TAG = "ClipboardSessionManager"

/**
 * Application-layer state machine for clipboard dialog sessions.
 *
 * Owns the complete transition logic between [ClipboardSessionStatus] states.
 * All transitions are driven by [ClipboardEvent]s and persisted via [ChatSessionRepository].
 *
 * This service has zero IntelliJ SDK dependencies and is fully testable via Gradle.
 *
 * Transition matrix:
 * ```
 * Event \ Status   | IDLE              | SESSION_ACTIVE     | AWAITING_PASTE
 * -----------------+-------------------+--------------------+-------------------
 * StartSession     | → SESSION_ACTIVE  | warn → false       | warn → false
 * JsonCopied       | warn → false      | → AWAITING_PASTE   | → AWAITING_PASTE
 * ResponsePasted   | warn → false      | warn → false       | → SESSION_ACTIVE
 * Reset            | no-op → true      | → IDLE             | → IDLE
 * ```
 *
 * @param repository Port for reading and persisting chat sessions.
 * @param logger Optional logger; pass null in unit tests to suppress all output.
 */
class ClipboardSessionManager(
    private val repository: ChatSessionRepository,
    private val logger: LoggerPort? = null
) {

    /**
     * Returns the current clipboard status for the given session.
     * Returns [ClipboardSessionStatus.IDLE] if the session does not exist.
     *
     * @param sessionId The ID of the session to query.
     */
    fun statusFor(sessionId: String): ClipboardSessionStatus =
        repository.getSessionById(sessionId)?.clipboardStatus ?: ClipboardSessionStatus.IDLE

    /**
     * Requests a state transition for the given session based on the provided event.
     *
     * Invalid transitions never throw — they log a warning and return false,
     * keeping the system operational even on unexpected or out-of-order calls.
     *
     * @param sessionId The ID of the session to transition.
     * @param event The event triggering the transition.
     * @return true if the transition was applied (or was a valid no-op); false if invalid.
     */
    fun transition(sessionId: String, event: ClipboardEvent): Boolean {
        // --- Load session; abort early if not found ---
        val session = repository.getSessionById(sessionId)
        if (session == null) {
            logger?.warn(
                TAG, "Transition requested for unknown session",
                data = mapOf("sessionId" to sessionId, "event" to event::class.simpleName)
            )
            return false
        }

        val currentStatus = session.clipboardStatus

        // --- Resolve target status from the transition matrix ---
        val newStatus = resolveTransition(currentStatus, event)
        if (newStatus == null) {
            logger?.warn(
                TAG, "Invalid transition ignored",
                data = mapOf(
                    "sessionId" to sessionId,
                    "status" to currentStatus,
                    "event" to event::class.simpleName
                )
            )
            return false
        }

        // --- Handle no-op: Reset from IDLE has no state change to persist ---
        if (event is ClipboardEvent.Reset && currentStatus == ClipboardSessionStatus.IDLE) {
            logger?.debug(TAG, "No-op transition (Reset from IDLE)", data = mapOf("sessionId" to sessionId))
            return true
        }

        // --- Persist the new status and log the successful transition ---
        repository.saveSession(session.withClipboardStatus(newStatus))
        logger?.info(
            TAG, "Session state transitioned",
            data = mapOf(
                "sessionId" to sessionId,
                "from" to currentStatus,
                "event" to event::class.simpleName,
                "to" to newStatus
            )
        )
        return true
    }

    /**
     * Pure transition function: maps (currentStatus, event) to a target status.
     *
     * Contains no side-effects — all logging and persistence happen in [transition].
     *
     * @return The new [ClipboardSessionStatus], or null if the transition is invalid
     *         for the given current state.
     */
    private fun resolveTransition(
        current: ClipboardSessionStatus,
        event: ClipboardEvent
    ): ClipboardSessionStatus? = when (event) {
        is ClipboardEvent.StartSession -> when (current) {
            ClipboardSessionStatus.IDLE -> ClipboardSessionStatus.SESSION_ACTIVE
            else -> null
        }

        is ClipboardEvent.JsonCopied -> when (current) {
            ClipboardSessionStatus.SESSION_ACTIVE,
            ClipboardSessionStatus.AWAITING_PASTE -> ClipboardSessionStatus.AWAITING_PASTE

            else -> null
        }

        is ClipboardEvent.ResponsePasted -> when (current) {
            ClipboardSessionStatus.AWAITING_PASTE -> ClipboardSessionStatus.SESSION_ACTIVE
            else -> null
        }

        // ForceActivate: user skips the pending paste and resumes the dialog.
        is ClipboardEvent.ForceActivate -> when (current) {
            ClipboardSessionStatus.AWAITING_PASTE -> ClipboardSessionStatus.SESSION_ACTIVE
            else -> null
        }

        // ForceAwaitPaste: user goes back to paste mode from an active session.
        is ClipboardEvent.ForceAwaitPaste -> when (current) {
            ClipboardSessionStatus.SESSION_ACTIVE -> ClipboardSessionStatus.AWAITING_PASTE
            else -> null
        }

        // Reset is always valid — drives the session to IDLE regardless of current state.
        is ClipboardEvent.Reset -> ClipboardSessionStatus.IDLE
    }
}
