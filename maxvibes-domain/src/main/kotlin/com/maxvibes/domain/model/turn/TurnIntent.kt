package com.maxvibes.domain.model.turn

/**
 * Whether the agent considers its work finished after this step.
 *
 * Declared by the agent itself instead of being guessed from prose: phrasing
 * like "part 1 of 3" is a habit of one model, not a contract, and the plugin
 * drives several different agents.
 *
 * Absence of the field is not [DONE] — it means the agent said nothing, and the
 * caller falls back to a weaker signal such as an unfinished plan.
 */
enum class TurnIntent {
    /** More work is left; the agent expects to be asked to go on. */
    CONTINUE,

    /** The task is finished, the turn belongs to the human again. */
    DONE
}
