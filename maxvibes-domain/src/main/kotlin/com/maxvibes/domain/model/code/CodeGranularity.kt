package com.maxvibes.domain.model.code

/**
 * Granularity level when requesting file content.
 *
 * Used to minimise incoming tokens: the LLM requests exactly as much
 * context as it needs for the current task, rather than always receiving
 * full file contents.
 */
enum class CodeGranularity {

    /** Full file — the current default behaviour. */
    FULL,

    /**
     * Signatures only: all top-level and class-member declarations
     * without function bodies. Useful for understanding file structure
     * without the implementation noise.
     */
    SIGNATURES,

    /**
     * Class outline: superclasses, properties (name + type),
     * method signatures. More compact than [SIGNATURES] for large classes.
     */
    OUTLINE,

    /**
     * A single element identified by [CodeViewRequest.elementPath].
     * Returns the full text of the element including its body.
     *
     * [CodeViewRequest.elementPath] is mandatory when this value is used.
     */
    ELEMENT,

    /**
     * Not a code view: the path is a SKILL NAME, not a file. Resolved by the
     * interaction services from the skill repository — must never reach the PSI adapter.
     */
    SKILL,

    /**
     * Flat list of usages of a single element across the project:
     * semantic Find Usages (ReferencesSearch), grouped by file with
     * line numbers and the containing declaration.
     *
     * [CodeViewRequest.elementPath] is mandatory when this value is used:
     * usages are searched for a specific declaration, never a whole file.
     */
    USAGES,

    /**
     * Multi-level tree of CALLING functions (upward call hierarchy): who calls
     * the target, who calls those callers, and so on — the programmatic analogue
     * of the IDE Call Hierarchy action. Calls made through super declarations
     * (ports/interfaces, base classes) are included and tagged `(via Owner.fn)`.
     * Depth- and node-limited; leaf markers support iterative deepening.
     *
     * [CodeViewRequest.elementPath] is mandatory and must address a function.
     */
    CALLERS,

    /**
     * Multi-level tree of CALLED functions (downward call hierarchy): what the
     * target's body invokes, recursively. Project declarations are expanded;
     * external (library) calls are terminal `[external]` leaves.
     *
     * [CodeViewRequest.elementPath] is mandatory and must address a function.
     */
    CALLEES,
}
