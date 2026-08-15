package com.maxvibes.domain.model.approval

/**
 * A class of agent-initiated action the user can grant autonomy for.
 *
 * Deliberately an enum rather than a string key: adding a fourth kind must break
 * compilation at every decision point instead of silently falling through to a
 * default, because the failure mode here is an unapproved side effect.
 */
enum class AgentActionKind {
    /** Reading code: the agent asks for files, signatures, usages or callers. */
    VIEW_REQUEST,

    /** Editing code through PSI. */
    MODIFICATION,

    /** Running an arbitrary shell command. */
    COMMAND
}
