package com.maxvibes.domain.model.approval

/**
 * A class of agent-initiated action the user can grant autonomy for.
 *
 * Deliberately an enum rather than a string key: adding a kind must break
 * compilation at every decision point instead of silently falling through to a
 * default, because the failure mode here is an unapproved side effect.
 */
enum class AgentActionKind {
    /** Reading code: the agent asks for files, signatures, usages or callers. */
    VIEW_REQUEST,

    /** Editing code through PSI. */
    MODIFICATION,

    /** Running an arbitrary shell command. */
    COMMAND,

    /**
     * Compiling the project through the IDE.
     *
     * Split off from [COMMAND] so the bounded, predictable action the agent needs
     * after nearly every edit can be granted permanently without also handing out
     * an arbitrary shell.
     */
    BUILD,

    /**
     * Running tests through the IDE. Kept apart from [BUILD] because it executes
     * project code rather than merely compiling it.
     */
    TESTS,

    /**
     * Taking another step in the same turn with no human in between, because the
     * agent said it is not finished yet.
     *
     * Unlike the other kinds this one is not a side effect on the project; it is
     * permission to spend the autonomy budget unattended.
     */
    CONTINUATION
}
