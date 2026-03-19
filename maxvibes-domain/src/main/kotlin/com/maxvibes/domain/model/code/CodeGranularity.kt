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
    ELEMENT
}
