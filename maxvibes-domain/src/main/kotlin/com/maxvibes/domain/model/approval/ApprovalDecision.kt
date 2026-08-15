package com.maxvibes.domain.model.approval

/**
 * What granted an action, once it was granted.
 *
 * Kept in the decision rather than inferred later: without it, a log or a chat
 * bubble cannot distinguish a human click from autonomy, which is exactly the
 * distinction someone needs when reconstructing what the agent did unattended.
 */
enum class ApprovalSource {
    /** The persisted per-project policy allowed this kind of action. */
    POLICY,

    /** The session-scoped "allow everything" toggle was on. */
    SESSION_OVERRIDE,

    /** A human approved this specific action. */
    USER
}

/**
 * Outcome of consulting the approval policy.
 *
 * [Ask] is not a refusal — it means the decision belongs to a human. Modelling
 * this as a sealed type instead of a boolean keeps that difference impossible to
 * misread at the call site.
 */
sealed interface ApprovalDecision {

    data class Allow(val source: ApprovalSource) : ApprovalDecision

    object Ask : ApprovalDecision
}
