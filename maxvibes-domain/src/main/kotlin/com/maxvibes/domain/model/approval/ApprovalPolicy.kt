package com.maxvibes.domain.model.approval

/** How much autonomy one [AgentActionKind] is granted. */
enum class ApprovalMode {
    /** Park the turn and wait for an explicit human decision. */
    ASK,

    /** Proceed without asking. */
    AUTO_ALLOW
}

/**
 * Autonomy granted per action kind.
 *
 * Immutable: the UI derives a new policy through [with] instead of mutating a
 * shared instance, so a half-applied settings panel can never be observed by a
 * turn that is already running.
 */
data class ApprovalPolicy(
    private val modes: Map<AgentActionKind, ApprovalMode> = emptyMap()
) {

    fun modeFor(kind: AgentActionKind): ApprovalMode =
        modes[kind] ?: DEFAULT_MODES.getValue(kind)

    fun with(kind: AgentActionKind, mode: ApprovalMode): ApprovalPolicy =
        copy(modes = modes + (kind to mode))

    fun asMap(): Map<AgentActionKind, ApprovalMode> =
        AgentActionKind.values().associateWith { modeFor(it) }

    companion object {
        /**
         * Reading code is the only action without side effects, so it is the one
         * granted by default; anything that writes to the project or the shell
         * starts out asking.
         */
        private val DEFAULT_MODES: Map<AgentActionKind, ApprovalMode> = mapOf(
            AgentActionKind.VIEW_REQUEST to ApprovalMode.AUTO_ALLOW,
            AgentActionKind.MODIFICATION to ApprovalMode.ASK,
            AgentActionKind.COMMAND to ApprovalMode.ASK
        )

        val DEFAULT: ApprovalPolicy = ApprovalPolicy()
    }
}
