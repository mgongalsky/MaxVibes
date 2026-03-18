package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.PromptTemplates
import com.maxvibes.domain.model.context.ProjectContext

/**
 * In-memory state of an active clipboard dialog session.
 *
 * Accumulates context gathered across multiple round-trips with the LLM:
 * file contents, dialog history, prompts, and token estimates.
 *
 * Scoped to a single clipboard session — created in [ClipboardInteractionService.startTask]
 * and discarded on [ClipboardInteractionService.reset].
 *
 * Marked [internal] so [ClipboardRequestBuilder] can consume it without
 * leaking the type to the plugin layer.
 */
internal data class ClipboardSessionState(
    /** The user message that started or is continuing the session. */
    val currentMessage: String,
    /** Snapshot of the project taken when the session started. */
    val projectContext: ProjectContext,
    /** Full dialog history for this session (mutated in place). */
    val dialogHistory: MutableList<ChatMessageDTO>,
    /** System prompts resolved at session start. */
    val prompts: PromptTemplates,
    /** All file contents gathered so far, keyed by path. */
    val allGatheredFiles: MutableMap<String, String>,
    /** User-attached context text (stack trace, logs, etc.). */
    val attachedContext: String? = null,
    /** IDE compiler / inspection errors for this turn. */
    val ideErrors: String? = null,
    /** Estimated input tokens for the last request — used for token display. */
    var lastInputTokens: Int = 0,
    /** When true, LLM is instructed to plan only without generating code changes. */
    val planOnly: Boolean = false
)
